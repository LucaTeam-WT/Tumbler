package io.github.lucateam_wt.tumbler.data.repository

import io.github.lucateam_wt.tumbler.data.crypto.DataKeyHolder
import io.github.lucateam_wt.tumbler.data.crypto.EnvelopeFormat
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
import java.util.UUID

/**
 * [AccountRepository] backed by Room, with each secret encrypted and bound to its account id as
 * associated data, so a secret copied into another row fails to decrypt.
 *
 * New secrets are encrypted by [cipher] in the legacy format (`keyVersion` 0). Stored secrets
 * are read in either format through [StoredSecretReader]: legacy rows with [cipher], rows
 * sealed with a data key with that key from [dataKeys] (`docs/storage-format.md`).
 *
 * Cipher calls run on [dispatcher] (by default [Dispatchers.IO]) because Android
 * Keystore operations block on IPC to the keystore service and secure hardware. Cipher
 * exceptions propagate unchanged, except in [nextHotpCode], which returns
 * [NextHotpCodeResult.Failed]; secrets are never logged or put in messages.
 */
class RoomAccountRepository internal constructor(
    private val dao: AccountDao,
    private val cipher: SecretCipher,
    dataKeys: DataKeyHolder,
    private val transactions: TransactionRunner,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) : AccountRepository {

    constructor(database: TumblerDatabase, cipher: SecretCipher, dataKeys: DataKeyHolder) :
        this(database.accountDao(), cipher, dataKeys, RoomTransactionRunner(database))

    private val secrets = StoredSecretReader(cipher, dataKeys)

    override fun observeAccounts(): Flow<List<AccountSummary>> =
        dao.observeAll().map { rows -> rows.map { it.toSummary() } }

    override suspend fun add(account: OtpAccount): String {
        val id = idGenerator()
        val secretBlob = withContext(dispatcher) {
            cipher.encrypt(account.secret, associatedData(id))
        }
        dao.insert(account.toEntity(id, secretBlob, EnvelopeFormat.LEGACY_KEY_VERSION, clock()))
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
     * One transaction: read the row, decrypt its secret, make the code for the counter read,
     * then a compare-and-set that adds 1 only while the row still holds that counter and
     * secret. The transaction already keeps other writers out; the compare-and-set makes a
     * changed row fail ([NextHotpCodeResult.Failed]) instead of being overwritten. The
     * counter limit is checked before anything is decrypted or written.
     */
    override suspend fun nextHotpCode(id: String): NextHotpCodeResult = transactions.inTransaction {
        val row = dao.getById(id) ?: return@inTransaction NextHotpCodeResult.NotFound
        if (row.type != OtpType.HOTP.name) return@inTransaction NextHotpCodeResult.NotHotp
        if (row.counter == Long.MAX_VALUE) return@inTransaction NextHotpCodeResult.LimitReached
        // Set inside the cipher block, so the plaintext is wiped even if withContext drops
        // its result because the caller was cancelled.
        var plaintext: ByteArray? = null
        try {
            // In the transaction's coroutine: a data key's record is read in this transaction.
            val open = secrets.opener(row)
            val secret = withContext(dispatcher) { open().also { plaintext = it } }
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

    override suspend fun delete(id: String) {
        dao.delete(id)
    }

    private fun associatedData(id: String): ByteArray = accountAssociatedData(id)
}
