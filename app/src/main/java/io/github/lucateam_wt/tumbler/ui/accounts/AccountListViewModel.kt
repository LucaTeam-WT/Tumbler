package io.github.lucateam_wt.tumbler.ui.accounts

import android.database.SQLException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.lucateam_wt.tumbler.TumblerApplication
import io.github.lucateam_wt.tumbler.domain.crypto.KeyTemporarilyUnavailableException
import io.github.lucateam_wt.tumbler.domain.crypto.NewStorageKeyRequiredException
import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipherException
import io.github.lucateam_wt.tumbler.domain.model.AccountLimitException
import io.github.lucateam_wt.tumbler.domain.model.AccountSummary
import io.github.lucateam_wt.tumbler.domain.model.OtpAccount
import io.github.lucateam_wt.tumbler.domain.model.OtpType
import io.github.lucateam_wt.tumbler.domain.otp.OtpAuthUri
import io.github.lucateam_wt.tumbler.domain.otp.Totp
import io.github.lucateam_wt.tumbler.domain.repository.AccountRepository
import io.github.lucateam_wt.tumbler.domain.repository.NextHotpCodeResult
import io.github.lucateam_wt.tumbler.domain.repository.StorageGate
import io.github.lucateam_wt.tumbler.domain.repository.StorageState
import io.github.lucateam_wt.tumbler.domain.repository.WriteAccess
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Seconds before a TOTP code expires from which the code for the next step is shown as well. */
internal const val NEXT_CODE_SECONDS = 10

/**
 * How many last seconds of a step show the next code: [NEXT_CODE_SECONDS], or half the
 * [period] for periods of 20 seconds or less, so a short period does not show it for most
 * of its life.
 */
internal fun nextCodeWindowSeconds(period: Int): Int =
    if (period <= 2 * NEXT_CODE_SECONDS) period / 2 else NEXT_CODE_SECONDS

/**
 * Turns the stored accounts into [AccountListUiState], recomputed every second. Each
 * account's secret is decrypted when the list loads: a TOTP account keeps it, inside its
 * [Totp] generator (which copies it), in memory only (never in saved state, never logged),
 * for the codes every second; a HOTP account keeps none, because "next code" decrypts the
 * stored row again ([AccountRepository.nextHotpCode]). The array the repository decrypts is
 * wiped as soon as the row was checked (and, for TOTP, the generator made), also when that
 * fails or a reload cancels the load ([load]); a cancellation while it is being decrypted
 * never hands it over ([AccountRepository.getAccount] wipes it). Not wiped: the copy each
 * TOTP generator keeps (held until the generator is dropped, also when a reload cancels a
 * load that had made some generators already, then left to the garbage collector), and the
 * copy the platform's `SecretKeySpec` makes each time a code is generated (every second
 * for every TOTP account while the list is observed, twice while the next code shows).
 *
 * Storage failures ([SecretCipherException], [SQLException]) never crash the app: they
 * become [AddError.STORAGE_FAILED], [AccountListUiState.loadFailed] or
 * [AccountListUiState.actionFailed]. Nothing is logged and no failure text reaches the UI.
 * Cancellation is never caught.
 *
 * @param clock current Unix time in seconds.
 */
class AccountListViewModel(
    private val repository: AccountRepository,
    private val storage: StorageGate = AlwaysReadyStorage,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) : ViewModel() {

    private sealed interface Entry {
        val summary: AccountSummary

        /** A TOTP account; [totp] holds its own copy of the secret. */
        class TotpReady(override val summary: AccountSummary, val totp: Totp) : Entry

        /** A HOTP account whose secret decrypts. No secret is kept: "next code" reads the stored row. */
        class HotpReady(override val summary: AccountSummary) : Entry

        class Unavailable(override val summary: AccountSummary) : Entry

        /** The secret could not be read this time (a failure that may be transient). */
        class TemporarilyUnavailable(override val summary: AccountSummary) : Entry
    }

    /** The outcome of the last "next code" request of one HOTP account: a code or an error, never both. */
    private data class HotpResult(val code: String? = null, val error: HotpError? = null)

    /** `null` until the first load has finished. */
    private val entries = MutableStateFlow<List<Entry>?>(null)
    private val hotpResults = MutableStateFlow<Map<String, HotpResult>>(emptyMap())
    private val loadFailed = MutableStateFlow(false)
    private val actionFailed = MutableStateFlow(false)
    private val hotpMutex = Mutex()

    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(clock())
            delay(TICK_MILLIS)
        }
    }

    private val listState: Flow<AccountListUiState> =
        combine(entries, hotpResults, ticker, loadFailed, actionFailed) { loaded, results, now, loadError, actionError ->
            AccountListUiState(
                isLoading = loaded == null && !loadError,
                accounts = loaded.orEmpty().map { it.toItem(results, now) },
                loadFailed = loadError,
                actionFailed = actionError,
            )
        }

    val uiState: StateFlow<AccountListUiState> =
        combine(listState, storage.state) { list, storageState ->
            val preparing = storageState as? StorageState.Preparing
            list.copy(
                // Before the first run has ended nothing is read, so the list is still loading.
                isLoading = list.isLoading || (preparing != null && entries.value == null),
                migration = preparing?.takeIf { it.total > 0 }?.let { MigrationProgress(it.done, it.total) },
                storageNotice = (storageState as? StorageState.Ready)?.let { notice(it, list.accounts) },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AccountListUiState())

    private val _addState = MutableStateFlow(AddState())
    val addState: StateFlow<AddState> = _addState

    private var observation: Job? = null

    /** The last [StorageState.Ready.generation] the list was read for. */
    private var readForGeneration = -1

    init {
        storage.start()
        // The list is read only once storage is ready, and again after every later run (a retry or
        // a new storage key), since rows may have changed format and cached failures were cleared.
        viewModelScope.launch {
            storage.state.collect { state ->
                when {
                    state is StorageState.Ready && state.generation != readForGeneration -> {
                        val first = readForGeneration == -1
                        readForGeneration = state.generation
                        if (first) observe() else reload()
                    }
                    // A later run (retry, new key) may convert rows: each converted row changes the
                    // table, which would reload the whole list once per row. Stop reading until it
                    // has ended; its outcome reads the list again. The rows already shown stay.
                    state is StorageState.Preparing -> pauseObservation()
                }
            }
        }
    }

    private fun pauseObservation() {
        observation?.cancel()
        observation = null
    }

    /** Runs the storage check again after a failure that may be transient ("Try again"). */
    fun retryStorage() {
        if (storage.state.value is StorageState.Preparing) return
        storage.retry()
    }

    /**
     * Starts a new storage key; call only after the user confirmed it. Accounts that cannot be
     * read stay as they are. Ignored while storage is being prepared.
     */
    fun startNewStorageKey() {
        if (storage.state.value is StorageState.Preparing) return
        viewModelScope.launch { storage.startNewKey() }
    }

    private fun notice(state: StorageState.Ready, accounts: List<AccountItem>): StorageNotice? {
        val unavailable = accounts.filterIsInstance<AccountItem.UnavailableItem>()
        val unreadable = unavailable.count { !it.temporary }
        val kind = when {
            state.writeAccess == WriteAccess.NEW_KEY_REQUIRED -> StorageNoticeKind.NEW_KEY_REQUIRED
            state.writeAccess == WriteAccess.TEMPORARILY_UNAVAILABLE || state.retryAdvised ||
                unavailable.any { it.temporary } -> StorageNoticeKind.TEMPORARY
            unreadable > 0 -> StorageNoticeKind.UNREADABLE
            else -> return null
        }
        return StorageNotice(kind, unreadable)
    }

    /**
     * Reads the accounts again from storage, for example after an import that may have
     * changed them. The list already follows every change through the repository's flow;
     * this also restarts that flow if a storage failure had stopped it, and lets accounts whose
     * storage key was found unreadable be tried again.
     */
    fun reload() {
        repository.forgetCachedReadFailures()
        // While storage is prepared nothing is read; the run's outcome reads the list again.
        if (storage.state.value is StorageState.Ready) observe()
    }

    private fun observe() {
        observation?.cancel()
        loadFailed.value = false
        observation = viewModelScope.launch {
            try {
                repository.observeAccounts().collectLatest { summaries ->
                    val loaded = summaries.mapNotNull { load(it) }
                    val ids = loaded.mapTo(HashSet()) { it.summary.id }
                    hotpResults.update { results -> results.filterKeys { it in ids } }
                    entries.value = loaded
                }
            } catch (_: SQLException) {
                loadFailed.value = true
            } catch (_: SecretCipherException) {
                loadFailed.value = true
            }
        }
    }

    /**
     * Parses [uri] and stores the account; the outcome is published in [addState]. A link
     * over the backup format's size limits gets its own error, every other bad link
     * [AddError.INVALID_LINK]. Ignored while a previous call is still saving, so a double tap
     * stores one account.
     */
    fun add(uri: String) {
        if (_addState.value.saving) return
        _addState.value = AddState(saving = true)
        viewModelScope.launch {
            _addState.value = try {
                // The add screen can come back after process death while the migration runs: wait for it.
                storage.awaitReady()
                repository.add(OtpAuthUri.parse(uri))
                AddState(saved = true)
            } catch (e: AccountLimitException) {
                AddState(
                    error = when (e.field) {
                        AccountLimitException.Field.ISSUER,
                        AccountLimitException.Field.ACCOUNT_NAME,
                        -> AddError.LABEL_TOO_LONG
                        AccountLimitException.Field.SECRET -> AddError.SECRET_TOO_LONG
                    },
                )
            } catch (_: IllegalArgumentException) {
                AddState(error = AddError.INVALID_LINK)
            } catch (_: NewStorageKeyRequiredException) {
                AddState(error = AddError.STORAGE_KEY_REQUIRED)
            } catch (_: SecretCipherException) {
                AddState(error = AddError.STORAGE_FAILED)
            } catch (_: SQLException) {
                AddState(error = AddError.STORAGE_FAILED)
            }
        }
    }

    /**
     * Clears the outcome of the last [add], for example when the add screen is left. A save
     * that is still running keeps [AddState.saving] set, so it cannot be started twice.
     */
    fun resetAddState() {
        _addState.value = AddState(saving = _addState.value.saving)
    }

    /** Deletes the account; if storage fails, [AccountListUiState.actionFailed] is set. */
    fun delete(id: String) {
        viewModelScope.launch {
            try {
                repository.delete(id)
            } catch (_: SQLException) {
                actionFailed.value = true
            }
        }
    }

    /** Call after the UI has shown the [AccountListUiState.actionFailed] message. */
    fun actionFailureShown() {
        actionFailed.value = false
    }

    /**
     * Shows the code for the HOTP account's current counter and stores the next counter.
     * Both come from one repository call ([AccountRepository.nextHotpCode]) that reads the
     * stored row, so the cached list never decides which code or counter is used. The
     * counter is saved before the code is shown, so a code is never shown that could be
     * generated again. Does nothing if the list has no usable HOTP row with [id]. At the
     * counter limit (`Long.MAX_VALUE`) the counter is not touched and the row shows
     * [HotpError.LIMIT_REACHED]; if the repository makes no code for another reason the row
     * shows [HotpError.FAILED]. If storage fails no code is shown and
     * [AccountListUiState.actionFailed] is set. Requests run one at a time.
     */
    fun nextHotpCode(id: String) {
        viewModelScope.launch {
            hotpMutex.withLock {
                // The list only decides whether the row offers "next code".
                val entry = entries.value?.firstOrNull { it.summary.id == id } as? Entry.HotpReady ?: return@withLock
                if (entry.summary.type != OtpType.HOTP) return@withLock
                val result = try {
                    repository.nextHotpCode(id)
                } catch (_: SQLException) {
                    actionFailed.value = true
                    return@withLock
                }
                val shown = when (result) {
                    is NextHotpCodeResult.Code -> HotpResult(code = result.code)
                    NextHotpCodeResult.LimitReached -> HotpResult(error = HotpError.LIMIT_REACHED)
                    NextHotpCodeResult.NotHotp,
                    NextHotpCodeResult.NotFound,
                    NextHotpCodeResult.Failed,
                    -> HotpResult(error = HotpError.FAILED)
                }
                // The stored counter changed, so the repository's flow reloads the list.
                hotpResults.update { it + (id to shown) }
            }
        }
    }

    /**
     * Decrypts the account to tell whether its row is usable. For TOTP the generator copies
     * the secret; for HOTP nothing is kept. Decrypting and using the secret happen in one
     * `try` whose `finally` wipes the array the repository returned, on every exit:
     * the result, a failure and a cancellation. A cancellation while the repository decrypts
     * never hands an array over ([AccountRepository.getAccount] wipes it itself).
     */
    private suspend fun load(summary: AccountSummary): Entry? {
        var account: OtpAccount? = null
        try {
            val read = repository.getAccount(summary.id) ?: return null
            account = read
            return if (read.type == OtpType.TOTP) {
                Entry.TotpReady(summary, Totp(read.secret, read.algorithm, read.digits, read.period))
            } else {
                Entry.HotpReady(summary)
            }
        } catch (_: KeyTemporarilyUnavailableException) {
            return Entry.TemporarilyUnavailable(summary)
        } catch (_: SecretCipherException) {
            return Entry.Unavailable(summary)
        } catch (_: IllegalArgumentException) {
            return Entry.Unavailable(summary)
        } finally {
            account?.secret?.fill(0)
        }
    }

    private fun Entry.toItem(results: Map<String, HotpResult>, now: Long): AccountItem {
        val id = summary.id
        return when (this) {
            is Entry.Unavailable -> AccountItem.UnavailableItem(id, summary.issuer, summary.accountName)
            is Entry.TemporarilyUnavailable -> AccountItem.UnavailableItem(id, summary.issuer, summary.accountName, temporary = true)
            is Entry.HotpReady -> AccountItem.HotpItem(id, summary.issuer, summary.accountName, results[id]?.code, results[id]?.error)
            is Entry.TotpReady -> {
                val secondsRemaining = totp.secondsRemaining(now)
                AccountItem.TotpItem(
                    id = id,
                    issuer = summary.issuer,
                    accountName = summary.accountName,
                    code = totp.generate(now),
                    secondsRemaining = secondsRemaining,
                    period = totp.period,
                    // Derived from the same `now` as the current code and never stored.
                    nextCode = if (secondsRemaining <= nextCodeWindowSeconds(totp.period)) {
                        totp.generate(now + totp.period)
                    } else {
                        null
                    },
                )
            }
        }
    }

    /** Storage that needs no preparation, for previews and tests that do not care about it. */
    internal object AlwaysReadyStorage : StorageGate {
        override val state: StateFlow<StorageState> =
            MutableStateFlow(StorageState.Ready(WriteAccess.AVAILABLE, retryAdvised = false, generation = 0))

        override fun start() = Unit
        override fun retry() = Unit
        override suspend fun startNewKey(): Boolean = true
        override suspend fun awaitReady() = Unit
    }

    companion object {
        private const val TICK_MILLIS = 1_000L
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /** Reads the repository from the application's container. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                val container = (app as TumblerApplication).container
                AccountListViewModel(container.accountRepository, storage = container.storageGate)
            }
        }
    }
}
