package io.github.lucateam_wt.tumbler.data.repository

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import io.github.lucateam_wt.tumbler.data.db.AccountDao
import io.github.lucateam_wt.tumbler.data.db.AccountEntity
import io.github.lucateam_wt.tumbler.data.db.TumblerDatabase
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
import java.util.UUID

/**
 * [AccountBackupStore] backed by Room. It uses the same table, encryption and associated
 * data as [RoomAccountRepository] (each secret bound to its account id), so each reads what
 * the other wrote. The schema is unchanged.
 *
 * Cipher calls run on [dispatcher] (by default [Dispatchers.IO]), because Android Keystore
 * operations block on IPC. A read that fails wipes the secrets it had already decrypted.
 * Nothing is logged, and no message holds an id, a label or a secret.
 */
class RoomAccountBackupStore internal constructor(
    private val dao: AccountDao,
    private val cipher: SecretCipher,
    private val transactions: TransactionRunner,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) : AccountBackupStore {

    constructor(database: TumblerDatabase, cipher: SecretCipher) :
        this(database.accountDao(), cipher, RoomTransactionRunner(database))

    override suspend fun readAll(): List<StoredAccount> = decryptAll(dao.getAll())

    override suspend fun <R> writeTransaction(block: suspend AccountBackupWriter.() -> R): R =
        transactions.inTransaction {
            val writer = Writer()
            try {
                writer.block()
            } finally {
                writer.close()
            }
        }

    /** Valid only while its transaction runs. */
    private inner class Writer : AccountBackupWriter {

        @Volatile
        private var open = true

        fun close() {
            open = false
        }

        private fun checkOpen() = check(open) { "The writer is only valid inside its transaction" }

        override suspend fun readAll(): List<StoredAccount> {
            checkOpen()
            return decryptAll(dao.getAll())
        }

        override suspend fun insert(account: OtpAccount, createdAtMillis: Long): String {
            checkOpen()
            require(createdAtMillis >= 0) { "Creation time must not be negative" }
            val id = idGenerator()
            dao.insert(account.toEntity(id, encrypt(account, id), createdAtMillis))
            return id
        }

        override suspend fun replace(id: String, account: OtpAccount) {
            checkOpen()
            val secretBlob = encrypt(account, id)
            val updated = dao.replace(
                id = id,
                issuer = account.issuer,
                accountName = account.accountName,
                type = account.type.name,
                algorithm = account.algorithm.name,
                digits = account.digits,
                period = account.period,
                counter = account.counter,
                secretBlob = secretBlob,
            )
            if (updated == 0) throw NoSuchElementException("No account to replace")
        }
    }

    private suspend fun encrypt(account: OtpAccount, id: String): ByteArray =
        withContext(dispatcher) { cipher.encrypt(account.secret, accountAssociatedData(id)) }

    private suspend fun decryptAll(rows: List<AccountEntity>): List<StoredAccount> {
        val accounts = ArrayList<StoredAccount>(rows.size)
        var complete = false
        try {
            for (row in rows) accounts += decrypt(row)
            complete = true
            return accounts
        } finally {
            if (!complete) accounts.forEach { it.account.secret.fill(0) }
        }
    }

    private suspend fun decrypt(row: AccountEntity): StoredAccount {
        val secret = try {
            withContext(dispatcher) { cipher.decrypt(row.secretBlob, accountAssociatedData(row.id)) }
        } catch (e: SecretCipherException) {
            throw UnreadableAccountException(row.id, e)
        }
        try {
            return StoredAccount(row.id, row.toOtpAccount(secret), row.createdAt)
        } catch (e: IllegalArgumentException) {
            secret.fill(0)
            throw UnreadableAccountException(row.id, e)
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
