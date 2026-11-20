package io.github.lucateam_wt.tumbler.backup

import io.github.lucateam_wt.tumbler.domain.model.OtpAccount

/**
 * One account as stored in a backup: every field of [account] plus its creation time.
 * The secret array is shared with [account], not copied.
 *
 * [toString] prints no secret and no label.
 *
 * @property createdAtMillis epoch milliseconds when the account was first added.
 * @throws IllegalArgumentException if [createdAtMillis] is negative.
 */
internal data class BackupEntry(val account: OtpAccount, val createdAtMillis: Long) {

    init {
        require(createdAtMillis >= 0) { "Creation time must not be negative" }
    }

    override fun toString(): String = "BackupEntry(type=${account.type}, <redacted>)"
}

/** Result of [readBackup]: the accounts, or one of a closed set of failures. */
internal sealed interface BackupReadResult {

    /**
     * The file decrypted and every account in it is valid. The caller owns the secret
     * arrays and should wipe them once they are stored. [toString] prints only the count.
     */
    class Success(val accounts: List<BackupEntry>) : BackupReadResult {
        override fun toString(): String = "Success(accounts=${accounts.size})"
    }

    sealed interface Failure : BackupReadResult

    /** The file does not start with the LucaAuth backup magic. */
    data object NotABackup : Failure

    /** The format version, KDF id or payload version is unknown (likely a newer app). */
    data object UnsupportedVersion : Failure

    /** The scrypt parameters or salt length are outside the hard bounds; scrypt did not run. */
    data object ParametersOutOfBounds : Failure

    /**
     * Authenticated decryption failed, or the file is cut short. Deliberately the same for
     * a wrong password and a damaged file: the format cannot tell them apart.
     */
    data object WrongPasswordOrDamaged : Failure

    /** Decryption succeeded but the content is malformed or an account is invalid. */
    data object InvalidPayload : Failure

    /** The file is larger than [BackupFormat.MAX_FILE_BYTES]; nothing else was read. */
    data object TooLarge : Failure

    /**
     * scrypt could not allocate its memory (at most [ScryptParams.MAX_MEMORY_BYTES]) on this
     * device. Nothing was decrypted; the file and password may both be fine.
     */
    data object NotEnoughMemory : Failure

    /**
     * The input stream failed before the whole file was read: it threw an `IOException`, or
     * a read returned 0 bytes. The bytes read so far were zeroed without being parsed.
     */
    data object ReadFailed : Failure
}
