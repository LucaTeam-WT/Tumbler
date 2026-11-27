package io.github.lucateam_wt.tumbler.data.repository

import android.database.SQLException
import io.github.lucateam_wt.tumbler.data.crypto.DataKey
import io.github.lucateam_wt.tumbler.data.crypto.DataKeyCipher
import io.github.lucateam_wt.tumbler.data.crypto.DataKeyHolder
import io.github.lucateam_wt.tumbler.data.db.AccountDao
import io.github.lucateam_wt.tumbler.data.db.LegacySecretRow
import io.github.lucateam_wt.tumbler.domain.crypto.KeyTemporarilyUnavailableException
import io.github.lucateam_wt.tumbler.domain.crypto.KeyUnavailableException
import io.github.lucateam_wt.tumbler.domain.crypto.NewStorageKeyRequiredException
import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipherException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Converts every legacy row (`keyVersion` 0) to the data-key format (`docs/storage-format.md`,
 * "Migration of existing rows"), one row at a time:
 *
 * 1. Once per run, outside any transaction, [DataKeyHolder.prepareWriteKey]: on the first run it
 *    creates data key version 1 and commits its record before any row uses it. If no key can be
 *    used (one must be started by the user, or the Keystore failed for now), nothing is converted.
 * 2. Per row, with no transaction open: decrypt with the legacy key (one Keystore operation), seal
 *    with the data key (software; the associated data binds the purpose, the key version and the
 *    account id), open the result again and compare it with the plaintext, then one
 *    compare-and-set [AccountDao.migrateSecret]: written only if the row is still legacy with the
 *    same bytes and the key's record is stored. The old payload stays in `legacySecretBlob`.
 *
 * Progress is the data: a run reads the legacy rows once, every row is either fully old or fully
 * new at any point, and the next run continues with the rows that are still legacy. Each row is
 * tried at most once per run, so an unreadable row never makes it loop. A row that was changed or
 * deleted meanwhile is skipped and never overwritten; its counter and labels are never written.
 *
 * Every plaintext and its reopened copy is wiped on every path. Nothing is logged.
 *
 * @param openSealed opens a sealed secret for the check; a seam so tests can make the check fail.
 */
internal class SecretMigrator(
    private val dao: AccountDao,
    private val legacy: LegacySecretReader,
    private val dataKeys: DataKeyHolder,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val random: SecureRandom = SecureRandom(),
    private val openSealed: (DataKey, String, ByteArray) -> ByteArray = DataKeyCipher::open,
) {

    private enum class RowOutcome { MIGRATED, CHANGED, UNREADABLE, RETRY_LATER, LEGACY_KEY_GONE, CHECK_FAILED }

    /**
     * Runs the conversion once over the rows that are legacy now. [onProgress] gets `(done, total)`:
     * `(0, total)` before the first row and once after each row, in order.
     *
     * @throws SQLException if the legacy rows or the data-key records cannot be read; nothing was
     *   converted by this run.
     */
    suspend fun run(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): MigrationResult {
        val pending = dao.legacyRows()
        val total = pending.size
        if (total == 0) return MigrationResult(total = 0)
        onProgress(0, total)
        val key = try {
            dataKeys.writeKey(dataKeys.prepareWriteKey())
        } catch (_: NewStorageKeyRequiredException) {
            return MigrationResult(total, stop = MigrationStop.NEW_KEY_REQUIRED)
        } catch (_: SecretCipherException) {
            return MigrationResult(total, stop = MigrationStop.KEY_TEMPORARILY_UNAVAILABLE)
        }
        var migrated = 0
        var changed = 0
        var unreadable = 0
        var retryLater = 0
        fun result(stop: MigrationStop? = null) = MigrationResult(total, migrated, changed, unreadable, retryLater, stop)
        for ((index, row) in pending.withIndex()) {
            currentCoroutineContext().ensureActive()
            val outcome = try {
                migrate(row, key)
            } catch (_: SQLException) {
                return result(MigrationStop.STORAGE_FAILED)
            }
            when (outcome) {
                RowOutcome.MIGRATED -> migrated++
                RowOutcome.CHANGED -> changed++
                RowOutcome.UNREADABLE -> unreadable++
                RowOutcome.RETRY_LATER -> retryLater++
                RowOutcome.LEGACY_KEY_GONE -> {
                    // No other legacy row can be read either: count them all, touch none.
                    unreadable += total - index
                    onProgress(total, total)
                    return result(MigrationStop.LEGACY_KEY_GONE)
                }
                RowOutcome.CHECK_FAILED -> return result(MigrationStop.CHECK_FAILED)
            }
            onProgress(index + 1, total)
        }
        return result()
    }

    private suspend fun migrate(row: LegacySecretRow, key: DataKey): RowOutcome {
        // Set inside the cipher block, so the plaintext is wiped even if withContext drops its
        // result because the caller was cancelled.
        var plaintext: ByteArray? = null
        var reopened: ByteArray? = null
        try {
            try {
                withContext(dispatcher) { legacy.decrypt(row.secretBlob, row.id).also { plaintext = it } }
            } catch (_: KeyUnavailableException) {
                return RowOutcome.LEGACY_KEY_GONE
            } catch (_: KeyTemporarilyUnavailableException) {
                return RowOutcome.RETRY_LATER
            } catch (_: SecretCipherException) {
                return RowOutcome.UNREADABLE
            }
            val secret = checkNotNull(plaintext)
            val sealed = try {
                DataKeyCipher.seal(key, row.id, secret, random)
            } catch (_: KeyTemporarilyUnavailableException) {
                return RowOutcome.RETRY_LATER
            } catch (_: SecretCipherException) {
                // A secret or id the data-key format refuses (an empty secret): left as it is.
                return RowOutcome.UNREADABLE
            }
            reopened = try {
                openSealed(key, row.id, sealed.blob)
            } catch (_: SecretCipherException) {
                return RowOutcome.CHECK_FAILED
            }
            if (!MessageDigest.isEqual(reopened, secret)) return RowOutcome.CHECK_FAILED
            val updated = dao.migrateSecret(row.id, sealed.blob, sealed.keyVersion, row.secretBlob)
            return if (updated == 1) RowOutcome.MIGRATED else RowOutcome.CHANGED
        } finally {
            plaintext?.fill(0)
            reopened?.fill(0)
        }
    }
}

/**
 * What one [SecretMigrator.run] did with the [total] rows that were legacy when it started:
 * [migrated] converted, [changed] skipped because they were changed or deleted meanwhile,
 * [unreadable] left as they are because their legacy payload cannot be read for good, [retryLater]
 * left for the next run after a failure that may be transient. [stop] says why the run ended
 * early, or is `null`; rows after a stop are left as they are.
 */
internal data class MigrationResult(
    val total: Int,
    val migrated: Int = 0,
    val changed: Int = 0,
    val unreadable: Int = 0,
    val retryLater: Int = 0,
    val stop: MigrationStop? = null,
) {
    /** A later run may get further: a transient failure, a database error or a failed check. */
    val retryAdvised: Boolean
        get() = retryLater > 0 || stop == MigrationStop.KEY_TEMPORARILY_UNAVAILABLE ||
            stop == MigrationStop.STORAGE_FAILED || stop == MigrationStop.CHECK_FAILED
}

/** Why a [SecretMigrator.run] ended before the last row. */
internal enum class MigrationStop {
    /** No data key can be used until the user starts a new one; nothing was converted. */
    NEW_KEY_REQUIRED,

    /** The data key could not be used or made this time; nothing was converted. */
    KEY_TEMPORARILY_UNAVAILABLE,

    /** The legacy key is gone, so no legacy row can be read; every remaining row counts as unreadable. */
    LEGACY_KEY_GONE,

    /** A sealed secret did not open to the same bytes; that row and the rest were not written. */
    CHECK_FAILED,

    /** The database failed while writing; the rest were not written. */
    STORAGE_FAILED,
}
