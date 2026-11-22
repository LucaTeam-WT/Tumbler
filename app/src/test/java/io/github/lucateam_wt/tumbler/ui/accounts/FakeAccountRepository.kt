package io.github.lucateam_wt.tumbler.ui.accounts

import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipherException
import io.github.lucateam_wt.tumbler.domain.model.AccountSummary
import io.github.lucateam_wt.tumbler.domain.model.OtpAccount
import io.github.lucateam_wt.tumbler.domain.model.OtpType
import io.github.lucateam_wt.tumbler.domain.otp.Hotp
import io.github.lucateam_wt.tumbler.domain.repository.AccountRepository
import io.github.lucateam_wt.tumbler.domain.repository.NextHotpCodeResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

/** In-memory [AccountRepository] whose failures can be switched on per test. */
class FakeAccountRepository : AccountRepository {

    private val accounts = LinkedHashMap<String, OtpAccount>()
    private val summaries = MutableStateFlow<List<AccountSummary>>(emptyList())
    private var nextId = 1

    /**
     * If set for an id, [getAccount] throws it, and [nextHotpCode] returns
     * [NextHotpCodeResult.Failed] for a [SecretCipherException] (the secret does not decrypt).
     */
    val getAccountFailures = HashMap<String, Exception>()

    /** If set, [add] throws it. */
    var addFailure: Exception? = null

    /** If set, [nextHotpCode] throws it and changes nothing. */
    var nextHotpCodeFailure: Exception? = null

    /** If set, [nextHotpCode] suspends on it after reading the account and before writing. */
    var nextHotpCodeGate: CompletableDeferred<Unit>? = null

    /** If set, [delete] throws it. */
    var deleteFailure: Exception? = null

    /** If set, the flow from [observeAccounts] fails with it. */
    var observeFailure: Exception? = null

    /** If set, [add] suspends until it is completed, after checking [addFailure]. */
    var addGate: CompletableDeferred<Unit>? = null

    /** How many times [nextHotpCode] was called, including calls that failed. */
    var nextHotpCodeCalls = 0
        private set

    /** How many times [nextHotpCode] wrote a counter. */
    var counterWrites = 0
        private set

    val storedAccounts: List<OtpAccount> get() = accounts.values.toList()

    fun counterOf(id: String): Long = accounts.getValue(id).counter

    /**
     * Overwrites account [id] (or removes it, for `null`) like a committed backup import
     * does, without telling [observeAccounts] yet: the list keeps showing what it decrypted
     * before, as it does until it has decrypted every account again.
     */
    fun replaceWithoutPublishing(id: String, account: OtpAccount?) {
        if (account == null) accounts.remove(id) else accounts[id] = account
    }

    override fun observeAccounts(): Flow<List<AccountSummary>> {
        observeFailure?.let { failure -> return flow { throw failure } }
        return summaries
    }

    override suspend fun add(account: OtpAccount): String {
        addFailure?.let { throw it }
        addGate?.await()
        val id = "id-${nextId++}"
        accounts[id] = account
        publish()
        return id
    }

    override suspend fun getAccount(id: String): OtpAccount? {
        getAccountFailures[id]?.let { throw it }
        return accounts[id]
    }

    /** Same contract as the real repository: reads the stored account, not a cached copy. */
    override suspend fun nextHotpCode(id: String): NextHotpCodeResult {
        nextHotpCodeCalls++
        nextHotpCodeFailure?.let { throw it }
        val account = accounts[id] ?: return NextHotpCodeResult.NotFound
        if (account.type != OtpType.HOTP) return NextHotpCodeResult.NotHotp
        if (account.counter == Long.MAX_VALUE) return NextHotpCodeResult.LimitReached
        if (getAccountFailures[id] is SecretCipherException) return NextHotpCodeResult.Failed
        val secret = account.secret.copyOf() // the "decrypted" copy
        try {
            val code = Hotp(secret, account.algorithm, account.digits).generate(account.counter)
            nextHotpCodeGate?.await()
            // Compare-and-set, like the real repository.
            if (accounts[id] != account) return NextHotpCodeResult.Failed
            accounts[id] = account.copy(counter = account.counter + 1)
            counterWrites++
            publish()
            return NextHotpCodeResult.Code(code, account.counter + 1)
        } finally {
            secret.fill(0)
        }
    }

    override suspend fun delete(id: String) {
        deleteFailure?.let { throw it }
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
