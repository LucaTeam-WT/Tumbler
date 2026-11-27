package io.github.lucateam_wt.tumbler.domain.repository

import kotlinx.coroutines.flow.StateFlow

/**
 * Brings secret storage up to date before the accounts are used (`docs/storage-format.md`,
 * "Migration of existing rows"), once per process, and reports whether new secrets can be saved.
 * The accounts list and the backup wait until [state] is [StorageState.Ready].
 */
interface StorageGate {

    /** [StorageState.Preparing] until the first run has ended, then [StorageState.Ready]; again while a run repeats. */
    val state: StateFlow<StorageState>

    /** Starts the first run if it has not started yet. Returns at once. */
    fun start()

    /**
     * Runs again (after a failure that may be transient): the accounts still in the old format are
     * tried once more and the storage key is checked again. Returns at once.
     */
    fun retry()

    /**
     * Starts a new storage key, which the user has confirmed, then runs again. A new key is made
     * only if the current one cannot be used for good; a failure that may be transient makes none.
     * Accounts sealed with an old key are kept as they are.
     *
     * @return false if no key could be made this time (the state then advises a retry).
     */
    suspend fun startNewKey(): Boolean

    /** Starts the first run if needed and waits until [state] is [StorageState.Ready]. */
    suspend fun awaitReady()
}

sealed interface StorageState {

    /**
     * A run is going on. [total] is the number of accounts still in the old format when it
     * started (0 while that is not known yet, or when there is none); [done] of them are handled.
     */
    data class Preparing(val done: Int = 0, val total: Int = 0) : StorageState

    /**
     * The last run has ended. [generation] counts the runs that ended, so a reader can tell a new
     * outcome from an old one. [retryAdvised]: a later run may get further (a failure that may be
     * transient left accounts in the old format, or the key could not be checked).
     */
    data class Ready(
        val writeAccess: WriteAccess,
        val retryAdvised: Boolean,
        val generation: Int,
    ) : StorageState
}

/** Whether new secrets can be saved now. */
enum class WriteAccess {
    AVAILABLE,

    /**
     * The storage key cannot be used for good (gone, invalidated, unreadable, or accounts name a
     * key that is not stored). Saving is refused until the user starts a new storage key.
     */
    NEW_KEY_REQUIRED,

    /** The storage key could not be checked this time; a retry may work. Nothing new is made. */
    TEMPORARILY_UNAVAILABLE,
}
