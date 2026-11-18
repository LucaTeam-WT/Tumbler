package com.phucho0237.lucaauth.ui.accounts

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

/**
 * Holds the decrypted accounts in memory only (never in saved state, never logged) and
 * turns them into [AccountListUiState], recomputed every second.
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

    /** `null` until the first load has finished. */
    private val entries = MutableStateFlow<List<Entry>?>(null)
    private val hotpCodes = MutableStateFlow<Map<String, String>>(emptyMap())
    private val hotpMutex = Mutex()

    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(clock())
            delay(TICK_MILLIS)
        }
    }

    val uiState: StateFlow<AccountListUiState> =
        combine(entries, hotpCodes, ticker) { loaded, codes, now ->
            if (loaded == null) {
                AccountListUiState()
            } else {
                AccountListUiState(isLoading = false, accounts = loaded.map { it.toItem(codes, now) })
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AccountListUiState())

    private val _addState = MutableStateFlow(AddState())
    val addState: StateFlow<AddState> = _addState

    init {
        viewModelScope.launch {
            repository.observeAccounts().collectLatest { summaries ->
                val loaded = summaries.mapNotNull { load(it) }
                val ids = loaded.mapTo(HashSet()) { it.summary.id }
                hotpCodes.update { codes -> codes.filterKeys { it in ids } }
                entries.value = loaded
            }
        }
    }

    /** Parses [uri] and stores the account; the outcome is published in [addState]. */
    fun add(uri: String) {
        viewModelScope.launch {
            _addState.value = try {
                repository.add(OtpAuthUri.parse(uri))
                AddState(saved = true)
            } catch (_: IllegalArgumentException) {
                AddState(error = AddError.INVALID_LINK)
            } catch (_: SecretCipherException) {
                AddState(error = AddError.STORAGE_FAILED)
            }
        }
    }

    /** Clears the outcome of the last [add], for example when the add screen is left. */
    fun resetAddState() {
        _addState.value = AddState()
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }

    /**
     * Shows the code for the HOTP account's current counter and stores the next counter.
     * The counter is saved first, so a code is never shown that could be generated again.
     * Does nothing if the account is gone or is not a usable HOTP account.
     */
    fun nextHotpCode(id: String) {
        viewModelScope.launch {
            hotpMutex.withLock {
                val entry = entries.value?.firstOrNull { it.summary.id == id } as? Entry.Ready ?: return@withLock
                val account = entry.account
                if (account.type != OtpType.HOTP) return@withLock
                val counter = account.counter
                try {
                    repository.updateCounter(id, counter + 1)
                } catch (_: NoSuchElementException) {
                    return@withLock
                }
                val code = Hotp(account.secret, account.algorithm, account.digits).generate(counter)
                hotpCodes.update { it + (id to code) }
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

    private fun Entry.toItem(codes: Map<String, String>, now: Long): AccountItem {
        val id = summary.id
        return when (this) {
            is Entry.Unavailable -> AccountItem.UnavailableItem(id, summary.issuer, summary.accountName)
            is Entry.Ready -> if (totp != null) {
                AccountItem.TotpItem(
                    id = id,
                    issuer = summary.issuer,
                    accountName = summary.accountName,
                    code = totp.generate(now),
                    secondsRemaining = totp.secondsRemaining(now),
                    period = totp.period,
                )
            } else {
                AccountItem.HotpItem(id, summary.issuer, summary.accountName, codes[id])
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
