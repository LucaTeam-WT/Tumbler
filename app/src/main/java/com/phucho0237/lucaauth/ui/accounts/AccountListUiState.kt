package com.phucho0237.lucaauth.ui.accounts

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
)

sealed interface AccountItem {
    val id: String
    val issuer: String
    val accountName: String

    /** A time-based account; [secondsRemaining] is in `1..period`. */
    data class TotpItem(
        override val id: String,
        override val issuer: String,
        override val accountName: String,
        val code: String,
        val secondsRemaining: Int,
        val period: Int,
    ) : AccountItem

    /** A counter-based account; [code] is `null` until the user asks for the next code. */
    data class HotpItem(
        override val id: String,
        override val issuer: String,
        override val accountName: String,
        val code: String?,
    ) : AccountItem

    /** An account whose secret cannot be decrypted (for example after the key was lost). */
    data class UnavailableItem(
        override val id: String,
        override val issuer: String,
        override val accountName: String,
    ) : AccountItem
}

/** Why saving a pasted link failed. The UI maps each value to a fixed message. */
enum class AddError { INVALID_LINK, STORAGE_FAILED }

/** Result of the last [AccountListViewModel.add] call. */
data class AddState(
    val error: AddError? = null,
    val saved: Boolean = false,
    /** A save is running; further saves are ignored and the Save button is disabled. */
    val saving: Boolean = false,
)
