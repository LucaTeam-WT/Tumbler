package io.github.lucateam_wt.tumbler.data.repository

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import io.github.lucateam_wt.tumbler.data.crypto.DataKey
import io.github.lucateam_wt.tumbler.data.crypto.DataKeyCipher
import io.github.lucateam_wt.tumbler.data.crypto.DataKeyHolder
import io.github.lucateam_wt.tumbler.data.crypto.SealedSecret
import io.github.lucateam_wt.tumbler.data.db.AccountDao
import io.github.lucateam_wt.tumbler.data.db.AccountEntity
import io.github.lucateam_wt.tumbler.data.db.TumblerDatabase
import io.github.lucateam_wt.tumbler.domain.crypto.KeyTemporarilyUnavailableException
import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipher
import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipherException
import io.github.lucateam_wt.tumbler.domain.model.OtpAccount
import io.github.lucateam_wt.tumbler.domain.model.StoredAccount
import io.github.lucateam_wt.tumbler.domain.repository.AccountBackupStore
import io.github.lucateam_wt.tumbler.domain.repository.AccountBackupWriter
import io.github.lucateam_wt.tumbler.domain.repository.UnreadableAccountException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.util.UUID

/**
 * [AccountBackupStore] backed by Room. It uses the same table, encryption and associated data as
 * [RoomAccountRepository] (each secret bound to its account id), so each reads what the other
 * wrote: it seals new secrets with the newest data key from [dataKeys] and reads either format
 * through [StoredSecretReader]. Replacing a row clears its legacy copy of the secret.
 *
 * [beforeAccess] runs before every read and transaction; the app passes the wait for the storage
 * migration, so a backup never reads or writes while rows are being converted.
 *
 * No unwrap and no Keystore call runs inside the write transaction: before it opens, the write key
 * is resolved and every secret that needs the Keystore is opened ([StoredSecretReader.prepare]).
 * Keystore calls outside it run on [dispatcher] (by default [Dispatchers.IO]), because they block
 * on IPC. A read that fails wipes the secrets it had already decrypted, including the one being
 * decrypted when the caller is cancelled or memory runs out. A failure that may be transient
 * propagates as [KeyTemporarilyUnavailableException], not as an unreadable account. Nothing is
 * logged, and no message holds an id, a label or a secret.
 */
class RoomAccountBackupStore internal constructor(
    private val dao: AccountDao,
    cipher: SecretCipher,
    private val dataKeys: DataKeyHolder,
    private val transactions: TransactionRunner,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val random: SecureRandom = SecureRandom(),
    private val beforeAccess: suspend () -> Unit = {},
) : AccountBackupStore {

    constructor(
        database: TumblerDatabase,
        cipher: SecretCipher,
        dataKeys: DataKeyHolder,
        beforeAccess: suspend () -> Unit = {},
    ) : this(
        database.accountDao(), cipher, dataKeys, RoomTransactionRunner(database),
        beforeAccess = beforeAccess,
    )

    private val secrets = StoredSecretReader(legacySecretReader(cipher), dataKeys)

    override suspend fun readAll(): List<StoredAccount> {
        beforeAccess()
        return decryptAll(dao.getAll()) { row -> secrets.opener(row) }
    }

    override suspend fun <R> writeTransaction(block: suspend AccountBackupWriter.() -> R): R {
        beforeAccess()
        // Outside the transaction: the write key (created on a fresh install), kept as a failure
        // until a write needs it, so a block that only reads still runs.
        var writeVersion = 0
        var writeFailure: SecretCipherException? = null
        try {
            writeVersion = dataKeys.prepareWriteKey()
        } catch (e: SecretCipherException) {
            writeFailure = e
        }
        return secrets.prepare(dao.getAll(), dispatcher).use { prepared ->
            transactions.inTransaction {
                val writer = Writer(writeVersion, writeFailure, prepared)
                try {
                    writer.block()
                } finally {
                    writer.close()
                }
            }
        }
    }

    /** Valid only while its transaction runs. */
    private inner class Writer(
        private val writeVersion: Int,
        private val writeFailure: SecretCipherException?,
        private val prepared: StoredSecretReader.Prepared,
    ) : AccountBackupWriter {

        @Volatile
        private var open = true

        private var writeKey: DataKey? = null

        fun close() {
            open = false
        }

        private fun checkOpen() = check(open) { "The writer is only valid inside its transaction" }

        override suspend fun readAll(): List<StoredAccount> {
            checkOpen()
            return decryptAll(dao.getAll()) { row -> secrets.openerInTransaction(row, prepared) }
        }

        override suspend fun insert(account: OtpAccount, createdAtMillis: Long): String {
            checkOpen()
            require(createdAtMillis >= 0) { "Creation time must not be negative" }
            val id = idGenerator()
            dao.insert(account.toEntity(id, seal(account, id), createdAtMillis))
            return id
        }

        override suspend fun replace(id: String, account: OtpAccount) {
            checkOpen()
            val sealed = seal(account, id)
            val updated = dao.replace(
                id = id,
                issuer = account.issuer,
                accountName = account.accountName,
                type = account.type.name,
                algorithm = account.algorithm.name,
                digits = account.digits,
                period = account.period,
                counter = account.counter,
                secretBlob = sealed.blob,
                keyVersion = sealed.keyVersion,
            )
            if (updated == 0) throw NoSuchElementException("No account to replace")
        }

        /** Software only: the key was unwrapped before the transaction; its record is read in it. */
        private suspend fun seal(account: OtpAccount, id: String): SealedSecret {
            val key = writeKey ?: run {
                writeFailure?.let { throw it }
                dataKeys.writeKey(writeVersion).also { writeKey = it }
            }
            return DataKeyCipher.seal(key, id, account.secret, random)
        }
    }

    private suspend fun decryptAll(
        rows: List<AccountEntity>,
        openerFor: suspend (AccountEntity) -> () -> ByteArray,
    ): List<StoredAccount> {
        val accounts = ArrayList<StoredAccount>(rows.size)
        var complete = false
        try {
            for (row in rows) accounts += decrypt(row, openerFor)
            complete = true
            return accounts
        } finally {
            // Any failure (an unreadable row, cancellation, running out of memory) wipes the
            // secrets read so far. An index loop allocates nothing, which matters after an
            // OutOfMemoryError.
            if (!complete) for (i in accounts.indices) accounts[i].account.secret.fill(0)
        }
    }

    /**
     * Decrypts [row]. The plaintext is wiped on every failure: an invalid row, running out of
     * memory while the account is built, and a cancellation of the caller while the cipher
     * runs. In the last case `withContext` drops the block's result and throws, so the block
     * also stores its result in [plaintext], which the catch can still reach.
     */
    private suspend fun decrypt(
        row: AccountEntity,
        openerFor: suspend (AccountEntity) -> () -> ByteArray,
    ): StoredAccount {
        var plaintext: ByteArray? = null
        try {
            val open = openerFor(row)
            val secret = withContext(dispatcher) { open().also { plaintext = it } }
            return StoredAccount(row.id, row.toOtpAccount(secret), row.createdAt)
        } catch (e: KeyTemporarilyUnavailableException) {
            // May be transient: not an unreadable account. Nothing was decrypted.
            throw e
        } catch (e: SecretCipherException) {
            // Nothing was decrypted.
            throw UnreadableAccountException(row.id, e)
        } catch (e: IllegalArgumentException) {
            plaintext?.fill(0)
            throw UnreadableAccountException(row.id, e)
        } catch (t: Throwable) {
            plaintext?.fill(0)
            throw t
        }
    }
}

/** Runs a block in one database transaction. A seam so unit tests can run without Room. */
internal interface TransactionRunner {
    suspend fun <R> inTransaction(block: suspend () -> R): R
}

/** [TransactionRunner] with Room's [withTransaction]: commit when the block returns, roll back if it throws. */
internal class RoomTransactionRunner(private val database: RoomDatabase) : TransactionRunner {
    override suspend fun <R> inTransaction(block: suspend () -> R): R = database.withTransaction(block)
}
