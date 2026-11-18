package com.phucho0237.lucaauth.domain.repository

import com.phucho0237.lucaauth.domain.crypto.KeyUnavailableException
import com.phucho0237.lucaauth.domain.crypto.SecretCipherException
import com.phucho0237.lucaauth.domain.model.AccountSummary
import com.phucho0237.lucaauth.domain.model.OtpAccount
import kotlinx.coroutines.flow.Flow

/** Stores OTP accounts with their secrets encrypted. */
interface AccountRepository {

    /** Emits all accounts, oldest first, and again after every change. Never includes secrets. */
    fun observeAccounts(): Flow<List<AccountSummary>>

    /**
     * Encrypts and stores [account].
     *
     * @return the new account's id.
     * @throws SecretCipherException if the secret cannot be encrypted.
     */
    suspend fun add(account: OtpAccount): String

    /**
     * Loads and decrypts the account with [id].
     *
     * @return the account, or `null` if there is no account with [id].
     * @throws KeyUnavailableException if the encryption key is gone (for example after
     *   app data was cleared); the secret cannot be recovered.
     * @throws SecretCipherException if the stored secret is corrupted or was tampered with.
     */
    suspend fun getAccount(id: String): OtpAccount?

    /**
     * Sets the HOTP moving factor of the account with [id]. TOTP accounts are never
     * changed.
     *
     * @throws IllegalArgumentException if [counter] is negative.
     * @throws NoSuchElementException if there is no HOTP account with [id] (unknown id or a
     *   TOTP account); nothing is changed. The message is fixed and never contains the id.
     */
    suspend fun updateCounter(id: String, counter: Long)

    /** Deletes the account with [id]. Does nothing if there is none. */
    suspend fun delete(id: String)
}
