package com.phucho0237.lucaauth.ui.accounts

import com.phucho0237.lucaauth.domain.model.AccountSummary
import com.phucho0237.lucaauth.domain.model.OtpAccount
import com.phucho0237.lucaauth.domain.repository.AccountRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [AccountRepository] whose failures can be switched on per test. */
class FakeAccountRepository : AccountRepository {

    private val accounts = LinkedHashMap<String, OtpAccount>()
    private val summaries = MutableStateFlow<List<AccountSummary>>(emptyList())
    private var nextId = 1

    /** If set for an id, [getAccount] throws it. */
    val getAccountFailures = HashMap<String, Exception>()

    /** If set, [add] throws it. */
    var addFailure: Exception? = null

    /** If set, [updateCounter] throws it and changes nothing. */
    var updateCounterFailure: Exception? = null

    val storedAccounts: List<OtpAccount> get() = accounts.values.toList()

    fun counterOf(id: String): Long = accounts.getValue(id).counter

    override fun observeAccounts(): Flow<List<AccountSummary>> = summaries

    override suspend fun add(account: OtpAccount): String {
        addFailure?.let { throw it }
        val id = "id-${nextId++}"
        accounts[id] = account
        publish()
        return id
    }

    override suspend fun getAccount(id: String): OtpAccount? {
        getAccountFailures[id]?.let { throw it }
        return accounts[id]
    }

    override suspend fun updateCounter(id: String, counter: Long) {
        updateCounterFailure?.let { throw it }
        val account = accounts[id] ?: throw NoSuchElementException("No HOTP account to update")
        accounts[id] = account.copy(counter = counter)
        publish()
    }

    override suspend fun delete(id: String) {
        accounts.remove(id)
        publish()
    }

    private fun publish() {
        summaries.value = accounts.map { (id, account) ->
            AccountSummary(
                id = id,
                issuer = account.issuer,
                accountName = account.accountName,
                type = account.type,
                algorithm = account.algorithm,
                digits = account.digits,
                period = account.period,
                counter = account.counter,
            )
        }
    }
}
