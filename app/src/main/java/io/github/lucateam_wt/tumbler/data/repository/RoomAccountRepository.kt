package io.github.lucateam_wt.tumbler.data.repository

import io.github.lucateam_wt.tumbler.data.crypto.DataKeyCipher
import io.github.lucateam_wt.tumbler.data.crypto.DataKeyHolder
import io.github.lucateam_wt.tumbler.data.db.AccountDao
import io.github.lucateam_wt.tumbler.data.db.TumblerDatabase
import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipher
import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipherException
import io.github.lucateam_wt.tumbler.domain.model.AccountSummary
import io.github.lucateam_wt.tumbler.domain.model.OtpAccount
import io.github.lucateam_wt.tumbler.domain.model.OtpType
import io.github.lucateam_wt.tumbler.domain.otp.Hotp
import io.github.lucateam_wt.tumbler.domain.repository.AccountRepository
import io.github.lucateam_wt.tumbler.domain.repository.NextHotpCodeResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.util.UUID

/**
 * [AccountRepository] backed by Room, with each secret encrypted and bound to its account id as
 * associated data, so a secret copied into another row fails to decrypt.
 *
 * New secrets are sealed with the newest data key from [dataKeys] (`docs/storage-format.md`), in
 * software. Stored secrets are read in either format through [StoredSecretReader]: rows sealed with
 * a data key with that key, and legacy rows (and the legacy copy of a row whose data key is lost)
 * with [cipher], which is only ever used to decrypt.
 *
 * No unwrap and no Keystore call runs inside a database transaction: keys and legacy secrets are
 * resolved before the transaction opens. Keystore calls outside one run on [dispatcher] (by default
 * [Dispatchers.IO]) because they block on IPC to the keystore service and secure hardware. Cipher
 * exceptions propagate unchanged, except in [nextHotpCode], which returns
 * [NextHotpCodeResult.Failed]; secrets are never logged or put in messages.
 */
class RoomAccountRepository internal constructor(
    private val dao: AccountDao,
    cipher: SecretCipher,
    private val dataKeys: DataKeyHolder,
    private val transactions: TransactionRunner,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val random: SecureRandom = SecureRandom(),
) : AccountRepository {

    constructor(database: TumblerDatabase, cipher: SecretCipher, dataKeys: DataKeyHolder) :
        this(database.accountDao(), cipher, dataKeys, RoomTransactionRunner(database))

    private val secrets = StoredSecretReader(legacySecretReader(cipher), dataKeys)

    override fun observeAccounts(): Flow<List<AccountSummary>> =
        dao.observeAll().map { rows -> rows.map { it.toSummary() } }

    /** Forgets the data-key failures [dataKeys] cached, so the next read of those rows unwraps again. */
    override fun forgetCachedReadFailures() {
        dataKeys.clearFailures()
    }

    /**
     * Seals the secret with the newest data key and stores the account. The key is resolved (and
     * on a fresh install created) before the transaction; inside it, the key's record is read in
     * that transaction, so the row is never committed without it.
     *
     * @throws io.github.lucateam_wt.tumbler.domain.crypto.NewStorageKeyRequiredException if the
     *   newest storage key cannot be used and the user has to start a new one.
     * @throws io.github.lucateam_wt.tumbler.domain.crypto.KeyTemporarilyUnavailableException if the
     *   Keystore failed in a way that may be transient.
     */
    override suspend fun add(account: OtpAccount): String {
        val id = idGenerator()
        val version = dataKeys.prepareWriteKey()
        transactions.inTransaction {
            val key = dataKeys.writeKey(version)
            val sealed = DataKeyCipher.seal(key, id, account.secret, random)
            dao.insert(account.toEntity(id, sealed, clock()))
        }
        return id
    }

    override suspend fun getAccount(id: String): OtpAccount? {
        val entity = dao.getById(id) ?: return null
        val open = secrets.opener(entity)
        // Set inside the cipher block, so the plaintext is wiped even if withContext drops
        // its result because the caller was cancelled (as in nextHotpCode).
        var plaintext: ByteArray? = null
        try {
            val secret = withContext(dispatcher) { open().also { plaintext = it } }
            return entity.toOtpAccount(secret)
        } catch (t: Throwable) {
            // Not handed over (cancelled, or the row holds an unknown enum name): wipe it here.
            plaintext?.fill(0)
            throw t
        }
    }

    /**
     * Outside the transaction: a first look at the row, and whatever its secret needs from the
     * Keystore (nothing once its data key is unwrapped). Then one transaction: read the row again,
     * open its secret from memory, make the code for the counter read, then a compare-and-set that
     * adds 1 only while the row still holds that counter and secret. The transaction keeps other
     * writers out; the compare-and-set makes a changed row fail ([NextHotpCodeResult.Failed])
     * instead of being overwritten. The counter limit is checked before anything is decrypted or
     * written.
     */
    override suspend fun nextHotpCode(id: String): NextHotpCodeResult {
        val before = dao.getById(id) ?: return NextHotpCodeResult.NotFound
        if (before.type != OtpType.HOTP.name) return NextHotpCodeResult.NotHotp
        if (before.counter == Long.MAX_VALUE) return NextHotpCodeResult.LimitReached
        return secrets.prepare(listOf(before), dispatcher).use { prepared ->
            transactions.inTransaction {
                val row = dao.getById(id) ?: return@inTransaction NextHotpCodeResult.NotFound
                if (row.type != OtpType.HOTP.name) return@inTransaction NextHotpCodeResult.NotHotp
                if (row.counter == Long.MAX_VALUE) return@inTransaction NextHotpCodeResult.LimitReached
                var plaintext: ByteArray? = null
                try {
                    // Memory only: a ready data key or the secret prepared above.
                    val secret = secrets.openerInTransaction(row, prepared)().also { plaintext = it }
                    val account = row.toOtpAccount(secret)
                    val code = Hotp(account.secret, account.algorithm, account.digits).generate(account.counter)
                    if (dao.advanceCounter(id, row.counter, row.secretBlob) != 1) {
                        return@inTransaction NextHotpCodeResult.Failed
                    }
                    NextHotpCodeResult.Code(code, row.counter + 1)
                } catch (_: SecretCipherException) {
                    NextHotpCodeResult.Failed
                } catch (_: IllegalArgumentException) {
                    NextHotpCodeResult.Failed
                } finally {
                    plaintext?.fill(0)
                }
            }
        }
    }

    override suspend fun delete(id: String) {
        dao.delete(id)
    }
}
