package io.github.lucateam_wt.tumbler.domain.repository

import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipherException
import io.github.lucateam_wt.tumbler.domain.model.OtpAccount
import io.github.lucateam_wt.tumbler.domain.model.StoredAccount

/**
 * Bulk access to the stored accounts for a backup export or import. It is separate from
 * [AccountRepository], whose contract and users stay as they are.
 *
 * Every secret it returns is a new decrypted array that the caller owns and should wipe.
 */
interface AccountBackupStore {

    /**
     * Reads every account with its decrypted secret, oldest first (by creation time, then
     * id), from one query, so the list is a consistent snapshot.
     *
     * @throws UnreadableAccountException for the first account that cannot be read; the
     *   secrets decrypted before it are wiped.
     */
    suspend fun readAll(): List<StoredAccount>

    /**
     * Runs [block] in one write transaction. Everything it writes through its
     * [AccountBackupWriter] is committed together when it returns, and nothing is written if
     * it throws. Other writers wait until it has ended. The writer must not be used after
     * [block] returns; it then throws [IllegalStateException].
     */
    suspend fun <R> writeTransaction(block: suspend AccountBackupWriter.() -> R): R
}

/** What [AccountBackupStore.writeTransaction] can do inside its transaction. */
interface AccountBackupWriter {

    /** [AccountBackupStore.readAll], read inside the transaction. */
    suspend fun readAll(): List<StoredAccount>

    /**
     * Encrypts and stores [account] as a new account created at [createdAtMillis].
     *
     * @return the new account's id.
     * @throws IllegalArgumentException if [createdAtMillis] is negative.
     * @throws SecretCipherException if the secret cannot be encrypted.
     */
    suspend fun insert(account: OtpAccount, createdAtMillis: Long): String

    /**
     * Overwrites every field of the account with [id] with [account], the counter exactly as
     * given, and encrypts the secret again. The id and creation time are kept.
     *
     * @throws NoSuchElementException if there is no account with [id]; nothing is changed
     *   and the message never contains the id.
     * @throws SecretCipherException if the secret cannot be encrypted.
     */
    suspend fun replace(id: String, account: OtpAccount)
}

/**
 * The stored account [accountId] cannot be read: its secret does not decrypt (the key is
 * gone, or the data was corrupted or tampered with; [cause] is the [SecretCipherException]),
 * or its row holds values the app rejects ([cause] is the [IllegalArgumentException]).
 *
 * The message is fixed and never contains the id, a label or a secret.
 */
class UnreadableAccountException(val accountId: String, cause: Throwable) :
    Exception("Stored account cannot be read", cause)
