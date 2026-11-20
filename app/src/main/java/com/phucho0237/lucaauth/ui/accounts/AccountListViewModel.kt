package com.phucho0237.lucaauth.ui.accounts

import android.database.SQLException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.phucho0237.lucaauth.LucaAuthApplication
import com.phucho0237.lucaauth.domain.crypto.SecretCipherException
import com.phucho0237.lucaauth.domain.model.AccountSummary
import com.phucho0237.lucaauth.domain.model.OtpAccount
import com.phucho0237.lucaauth.domain.model.OtpType
import com.phucho0237.lucaauth.domain.otp.Hotp
import com.phucho0237.lucaauth.domain.otp.OtpAuthUri
import com.phucho0237.lucaauth.domain.otp.Totp
import com.phucho0237.lucaauth.domain.repository.AccountRepository
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
 * Holds the decrypted accounts in memory only (never in saved state, never logged) and
 * turns them into [AccountListUiState], recomputed every second.
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
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) : ViewModel() {

    private sealed interface Entry {
        val summary: AccountSummary

        class Ready(override val summary: AccountSummary, val account: OtpAccount) : Entry {
            val totp: Totp? = if (account.type == OtpType.TOTP) {
                Totp(account.secret, account.algorithm, account.digits, account.period)
            } else {
                null
            }
        }

        class Unavailable(override val summary: AccountSummary) : Entry
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

    val uiState: StateFlow<AccountListUiState> =
        combine(entries, hotpResults, ticker, loadFailed, actionFailed) { loaded, results, now, loadError, actionError ->
            AccountListUiState(
                isLoading = loaded == null && !loadError,
                accounts = loaded.orEmpty().map { it.toItem(results, now) },
                loadFailed = loadError,
                actionFailed = actionError,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AccountListUiState())

    private val _addState = MutableStateFlow(AddState())
    val addState: StateFlow<AddState> = _addState

    init {
        viewModelScope.launch {
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
     * Parses [uri] and stores the account; the outcome is published in [addState]. Ignored
     * while a previous call is still saving, so a double tap stores one account.
     */
    fun add(uri: String) {
        if (_addState.value.saving) return
        _addState.value = AddState(saving = true)
        viewModelScope.launch {
            _addState.value = try {
                repository.add(OtpAuthUri.parse(uri))
                AddState(saved = true)
            } catch (_: IllegalArgumentException) {
                AddState(error = AddError.INVALID_LINK)
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
     * The counter is saved first, so a code is never shown that could be generated again.
     * Does nothing if the account is gone or is not a usable HOTP account. At the counter
     * limit (`Long.MAX_VALUE`) the counter is not touched and the row shows
     * [HotpError.LIMIT_REACHED]; if the repository rejects the counter the row shows
     * [HotpError.FAILED]. If storage fails no code is shown and
     * [AccountListUiState.actionFailed] is set.
     */
    fun nextHotpCode(id: String) {
        viewModelScope.launch {
            hotpMutex.withLock {
                val entry = entries.value?.firstOrNull { it.summary.id == id } as? Entry.Ready ?: return@withLock
                val account = entry.account
                if (account.type != OtpType.HOTP) return@withLock
                val counter = account.counter
                if (counter == Long.MAX_VALUE) {
                    // counter + 1 would overflow: do not touch the repository or the stored counter.
                    hotpResults.update { it + (id to HotpResult(error = HotpError.LIMIT_REACHED)) }
                    return@withLock
                }
                try {
                    repository.updateCounter(id, counter + 1)
                } catch (_: NoSuchElementException) {
                    hotpResults.update { it + (id to HotpResult(error = HotpError.FAILED)) }
                    return@withLock
                } catch (_: IllegalArgumentException) {
                    hotpResults.update { it + (id to HotpResult(error = HotpError.FAILED)) }
                    return@withLock
                } catch (_: SQLException) {
                    actionFailed.value = true
                    return@withLock
                }
                val code = Hotp(account.secret, account.algorithm, account.digits).generate(counter)
                hotpResults.update { it + (id to HotpResult(code = code)) }
                entries.update { list ->
                    list?.map { if (it === entry) Entry.Ready(it.summary, account.copy(counter = counter + 1)) else it }
                }
            }
        }
    }

    private suspend fun load(summary: AccountSummary): Entry? =
        try {
            repository.getAccount(summary.id)?.let { Entry.Ready(summary, it) }
        } catch (_: SecretCipherException) {
            Entry.Unavailable(summary)
        } catch (_: IllegalArgumentException) {
            Entry.Unavailable(summary)
        }

    private fun Entry.toItem(results: Map<String, HotpResult>, now: Long): AccountItem {
        val id = summary.id
        return when (this) {
            is Entry.Unavailable -> AccountItem.UnavailableItem(id, summary.issuer, summary.accountName)
            is Entry.Ready -> if (totp != null) {
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
            } else {
                AccountItem.HotpItem(id, summary.issuer, summary.accountName, results[id]?.code, results[id]?.error)
            }
        }
    }

    companion object {
        private const val TICK_MILLIS = 1_000L
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /** Reads the repository from the application's container. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                AccountListViewModel((app as LucaAuthApplication).container.accountRepository)
            }
        }
    }
}
