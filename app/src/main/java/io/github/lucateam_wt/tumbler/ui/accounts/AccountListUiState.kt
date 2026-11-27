package io.github.lucateam_wt.tumbler.ui.accounts

/**
 * What the accounts list shows. Contains generated codes (sensitive): keep it in memory
 * only, never in saved instance state or logs.
 */
data class AccountListUiState(
    val isLoading: Boolean = true,
    val accounts: List<AccountItem> = emptyList(),
    /** The accounts could not be read from storage; [accounts] may be stale or empty. */
    val loadFailed: Boolean = false,
    /** A delete or next-code action failed in storage; clear it with `actionFailureShown()`. */
    val actionFailed: Boolean = false,
    /**
     * Set while accounts are being moved to the storage key ("N of M"); the list, adding and the
     * backup menu are not shown until it is `null` again.
     */
    val migration: MigrationProgress? = null,
    /** A message about the storage key above the list, or `null`. */
    val storageNotice: StorageNotice? = null,
)

/** [done] of [total] accounts handled by the storage migration; [total] is at least 1. */
data class MigrationProgress(val done: Int, val total: Int)

/**
 * What the storage banner says. [unreadableCount] counts the accounts whose secret cannot be read
 * for good (not those that failed only this time).
 */
data class StorageNotice(val kind: StorageNoticeKind, val unreadableCount: Int)

enum class StorageNoticeKind {
    /**
     * The storage key cannot be used any more: adding and importing are refused until the user
     * confirms "Start a new storage key". Unreadable accounts (if any) can only come back from a backup.
     */
    NEW_KEY_REQUIRED,

    /** Some accounts cannot be read for good; they can only come back from a backup. */
    UNREADABLE,

    /** A storage failure that may be transient: "Try again". */
    TEMPORARY,
}

/**
 * The list has loaded without a storage failure and holds no account, so an export would be
 * empty: the menu shows a short message instead of the password dialog.
 */
internal val AccountListUiState.nothingToExport: Boolean
    get() = !isLoading && !loadFailed && accounts.isEmpty()

/** Adding and importing are refused until a new storage key is started. */
internal val AccountListUiState.writesBlocked: Boolean
    get() = storageNotice?.kind == StorageNoticeKind.NEW_KEY_REQUIRED

sealed interface AccountItem {
    val id: String
    val issuer: String
    val accountName: String

    /**
     * A time-based account; [secondsRemaining] is in `1..period`. [nextCode] is the code of
     * the next time step, set only in the last seconds of the current one (derived on every
     * tick, never stored); it is display-only and is never copied.
     */
    data class TotpItem(
        override val id: String,
        override val issuer: String,
        override val accountName: String,
        val code: String,
        val secondsRemaining: Int,
        val period: Int,
        val nextCode: String? = null,
    ) : AccountItem

    /**
     * A counter-based account; [code] is `null` until the user asks for the next code, and
     * while [error] is set.
     */
    data class HotpItem(
        override val id: String,
        override val issuer: String,
        override val accountName: String,
        val code: String?,
        val error: HotpError? = null,
    ) : AccountItem

    /**
     * An account whose secret cannot be decrypted: for good (for example after the key was lost),
     * or, if [temporary], only this time (a Keystore error that may go away; "Try again").
     */
    data class UnavailableItem(
        override val id: String,
        override val issuer: String,
        override val accountName: String,
        val temporary: Boolean = false,
    ) : AccountItem
}

/** Why the last "next code" request of an HOTP row produced no code. The UI maps each value to a fixed message. */
enum class HotpError {
    /** The counter is `Long.MAX_VALUE`, so it cannot be advanced and no more codes can be made. */
    LIMIT_REACHED,

    /**
     * The repository made no code (an unexpected state: the account is gone or no longer HOTP,
     * or its secret cannot be read); nothing was changed.
     */
    FAILED,
}

/** Why saving a pasted link failed. The UI maps each value to a fixed message. */
enum class AddError {
    INVALID_LINK,

    /** The issuer or account name is over the backup format's label limit. */
    LABEL_TOO_LONG,

    /** The secret is over the backup format's secret limit. */
    SECRET_TOO_LONG,

    STORAGE_FAILED,

    /** The storage key cannot be used any more; a new one must be started from the accounts list first. */
    STORAGE_KEY_REQUIRED,
}

/** Result of the last [AccountListViewModel.add] call. */
data class AddState(
    val error: AddError? = null,
    val saved: Boolean = false,
    /** A save is running; further saves are ignored and the Save button is disabled. */
    val saving: Boolean = false,
)
