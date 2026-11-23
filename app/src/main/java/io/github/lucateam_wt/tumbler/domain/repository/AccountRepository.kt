package io.github.lucateam_wt.tumbler.domain.repository

import io.github.lucateam_wt.tumbler.domain.crypto.KeyUnavailableException
import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipherException
import io.github.lucateam_wt.tumbler.domain.model.AccountSummary
import io.github.lucateam_wt.tumbler.domain.model.OtpAccount
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
     * The caller owns the returned secret array and should wipe it once used. If this throws
     * (including a cancellation of the caller while the secret is decrypted), no secret is
     * handed over, and a secret array it had already decrypted is wiped first.
     *
     * @return the account, or `null` if there is no account with [id].
     * @throws KeyUnavailableException if the encryption key is gone (for example after
     *   app data was cleared); the secret cannot be recovered.
     * @throws SecretCipherException if the stored secret is corrupted or was tampered with.
     */
    suspend fun getAccount(id: String): OtpAccount?

    /**
     * Makes the HOTP code of the account with [id] for the counter it holds now, and stores
     * that counter + 1, in one transaction. The code comes from the stored row (secret,
     * algorithm, digits and counter as read inside that transaction), never from a copy the
     * caller holds, so overlapping calls get consecutive counters and different codes.
     *
     * Only [NextHotpCodeResult.Code] changes the row; every other result writes nothing.
     * The decrypted secret is wiped before this returns, whatever the outcome.
     *
     * @throws android.database.SQLException if the database fails; nothing is changed.
     */
    suspend fun nextHotpCode(id: String): NextHotpCodeResult

    /** Deletes the account with [id]. Does nothing if there is none. */
    suspend fun delete(id: String)
}

/** The outcome of [AccountRepository.nextHotpCode]. No result holds a secret. */
sealed interface NextHotpCodeResult {

    /**
     * [code] is the code for the counter the row held; the row now holds [counter], one
     * more. [toString] shows neither.
     */
    data class Code(val code: String, val counter: Long) : NextHotpCodeResult {
        override fun toString(): String = "Code(<redacted>)"
    }

    /** The counter is `Long.MAX_VALUE` and cannot be advanced; nothing is changed. */
    data object LimitReached : NextHotpCodeResult

    /** The account is not a HOTP account; nothing is changed. */
    data object NotHotp : NextHotpCodeResult

    /** There is no account with that id. */
    data object NotFound : NextHotpCodeResult

    /**
     * No code could be made and nothing is changed: the secret does not decrypt (the key is
     * gone or the row was tampered with), the row holds invalid values, or the row changed
     * between the read and the write.
     */
    data object Failed : NextHotpCodeResult
}
