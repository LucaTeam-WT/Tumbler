package io.github.lucateam_wt.tumbler.ui.accounts

import io.github.lucateam_wt.tumbler.domain.crypto.KeyTemporarilyUnavailableException
import io.github.lucateam_wt.tumbler.domain.crypto.KeyUnavailableException
import io.github.lucateam_wt.tumbler.domain.crypto.NewStorageKeyRequiredException
import io.github.lucateam_wt.tumbler.domain.model.OtpAccount
import io.github.lucateam_wt.tumbler.domain.model.OtpType
import io.github.lucateam_wt.tumbler.domain.otp.OtpAlgorithm
import io.github.lucateam_wt.tumbler.domain.repository.StorageGate
import io.github.lucateam_wt.tumbler.domain.repository.StorageState
import io.github.lucateam_wt.tumbler.domain.repository.WriteAccess
import io.github.lucateam_wt.tumbler.ui.accounts.AccountItem.TotpItem
import io.github.lucateam_wt.tumbler.ui.accounts.AccountItem.UnavailableItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** How the accounts list follows the storage gate: progress, banners, the new-key and retry actions. */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountListStorageTest {

    /** A gate whose state each test sets; counts the calls the list makes. */
    private class FakeStorageGate(initial: StorageState = StorageState.Preparing()) : StorageGate {
        override val state = MutableStateFlow(initial)
        var starts = 0
        var retries = 0
        var newKeys = 0

        override fun start() {
            starts++
        }

        override fun retry() {
            retries++
        }

        override suspend fun startNewKey(): Boolean {
            newKeys++
            return true
        }

        override suspend fun awaitReady() {
            state.first { it is StorageState.Ready }
        }

        fun ready(access: WriteAccess = WriteAccess.AVAILABLE, retryAdvised: Boolean = false) {
            val generation = ((state.value as? StorageState.Ready)?.generation ?: lastGeneration) + 1
            lastGeneration = generation
            state.value = StorageState.Ready(access, retryAdvised, generation)
        }

        private var lastGeneration = 0
    }

    private val repository = FakeAccountRepository()
    private val gate = FakeStorageGate()
    private val rfcSecret = "12345678901234567890".toByteArray(Charsets.US_ASCII)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.createViewModel(): AccountListViewModel {
        val viewModel = AccountListViewModel(repository, clock = { 59L }, storage = gate)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect { } }
        return viewModel
    }

    private suspend fun addTotp(name: String) = repository.add(
        OtpAccount(
            issuer = "Example",
            accountName = name,
            secret = rfcSecret.copyOf(),
            type = OtpType.TOTP,
            algorithm = OtpAlgorithm.SHA1,
            digits = 6,
            period = 30,
            counter = 0,
        ),
    )

    @Test
    fun `the list is not read while storage is being prepared and shows N of M`() = runTest {
        addTotp("alice")
        val viewModel = createViewModel()
        assertEquals(1, gate.starts)

        gate.state.value = StorageState.Preparing(done = 3, total = 8)

        val state = viewModel.uiState.value
        assertEquals(MigrationProgress(3, 8), state.migration)
        assertTrue(state.isLoading)
        assertTrue(state.accounts.isEmpty())
        assertNull(state.storageNotice)
        assertEquals(0, repository.observeCalls)

        gate.ready()

        assertNull(viewModel.uiState.value.migration)
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(1, viewModel.uiState.value.accounts.size)
        assertEquals(1, repository.observeCalls)
    }

    @Test
    fun `nothing to migrate shows the plain loading state, not a progress`() = runTest {
        val viewModel = createViewModel()

        assertNull(viewModel.uiState.value.migration)
        assertTrue(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.nothingToExport)
    }

    @Test
    fun `no banner when every account reads and storage is fine`() = runTest {
        addTotp("alice")
        val viewModel = createViewModel()
        gate.ready()

        assertNull(viewModel.uiState.value.storageNotice)
        assertFalse(viewModel.uiState.value.writesBlocked)
    }

    @Test
    fun `a storage key that cannot be used blocks writes and counts the unreadable accounts`() = runTest {
        val lost = addTotp("lost")
        addTotp("fine")
        repository.getAccountFailures[lost] = KeyUnavailableException()
        val viewModel = createViewModel()

        gate.ready(WriteAccess.NEW_KEY_REQUIRED)

        assertEquals(StorageNotice(StorageNoticeKind.NEW_KEY_REQUIRED, unreadableCount = 1), viewModel.uiState.value.storageNotice)
        assertTrue(viewModel.uiState.value.writesBlocked)
        // The unreadable account stays in the list; nothing deletes it.
        assertEquals(2, viewModel.uiState.value.accounts.size)
        assertEquals(1, repository.storedAccounts.count { it.accountName == "lost" })
    }

    @Test
    fun `a new key is required even when no account is unreadable`() = runTest {
        addTotp("alice")
        val viewModel = createViewModel()

        gate.ready(WriteAccess.NEW_KEY_REQUIRED)

        assertEquals(StorageNotice(StorageNoticeKind.NEW_KEY_REQUIRED, unreadableCount = 0), viewModel.uiState.value.storageNotice)
    }

    @Test
    fun `unreadable accounts with a usable key point to a backup and do not block writes`() = runTest {
        val broken = addTotp("broken")
        addTotp("fine")
        repository.getAccountFailures[broken] = KeyUnavailableException()
        val viewModel = createViewModel()

        gate.ready()

        assertEquals(StorageNotice(StorageNoticeKind.UNREADABLE, unreadableCount = 1), viewModel.uiState.value.storageNotice)
        assertFalse(viewModel.uiState.value.writesBlocked)
    }

    @Test
    fun `a transient read failure is a temporary row and a try-again banner, never unreadable`() = runTest {
        val busy = addTotp("busy")
        repository.getAccountFailures[busy] = KeyTemporarilyUnavailableException()
        val viewModel = createViewModel()

        gate.ready()

        val item = viewModel.uiState.value.accounts.single()
        assertTrue(item is UnavailableItem && item.temporary)
        assertEquals(StorageNotice(StorageNoticeKind.TEMPORARY, unreadableCount = 0), viewModel.uiState.value.storageNotice)
        assertFalse(viewModel.uiState.value.writesBlocked)
    }

    @Test
    fun `a definite read failure is not a temporary row`() = runTest {
        val broken = addTotp("broken")
        repository.getAccountFailures[broken] = KeyUnavailableException()
        val viewModel = createViewModel()
        gate.ready()

        val item = viewModel.uiState.value.accounts.single()
        assertTrue(item is UnavailableItem && !item.temporary)
    }

    @Test
    fun `a key that could not be checked, or a run that advises a retry, shows try again`() = runTest {
        addTotp("alice")
        val viewModel = createViewModel()

        gate.ready(WriteAccess.TEMPORARILY_UNAVAILABLE, retryAdvised = true)
        assertEquals(StorageNoticeKind.TEMPORARY, viewModel.uiState.value.storageNotice?.kind)
        assertFalse(viewModel.uiState.value.writesBlocked)

        gate.ready(WriteAccess.AVAILABLE, retryAdvised = true)
        assertEquals(StorageNoticeKind.TEMPORARY, viewModel.uiState.value.storageNotice?.kind)
    }

    @Test
    fun `each new storage outcome reads the list again with cached failures forgotten`() = runTest {
        val busy = addTotp("busy")
        repository.getAccountFailures[busy] = KeyTemporarilyUnavailableException()
        val viewModel = createViewModel()
        gate.ready(retryAdvised = true)
        assertTrue(viewModel.uiState.value.accounts.single() is UnavailableItem)
        val forgets = repository.forgetCachedReadFailuresCalls

        repository.getAccountFailures.clear()
        viewModel.retryStorage()
        assertEquals(1, gate.retries)
        gate.state.value = StorageState.Preparing()
        assertNull(viewModel.uiState.value.storageNotice) // no stale banner during the run
        gate.ready()

        assertTrue(viewModel.uiState.value.accounts.single() is TotpItem)
        assertNull(viewModel.uiState.value.storageNotice)
        assertEquals(forgets + 1, repository.forgetCachedReadFailuresCalls)
    }

    @Test
    fun `the same outcome emitted again does not read the list again`() = runTest {
        addTotp("alice")
        createViewModel()
        gate.ready()
        val observed = repository.observeCalls
        val same = gate.state.value as StorageState.Ready

        gate.state.value = same.copy()

        assertEquals(observed, repository.observeCalls)
    }

    @Test
    fun `the new-key and retry actions reach the gate and are ignored while storage is prepared`() = runTest {
        val viewModel = createViewModel()

        viewModel.startNewStorageKey()
        viewModel.retryStorage()
        assertEquals(0, gate.newKeys)
        assertEquals(0, gate.retries)

        gate.ready(WriteAccess.NEW_KEY_REQUIRED)
        viewModel.startNewStorageKey()
        assertEquals(1, gate.newKeys)
        viewModel.retryStorage()
        assertEquals(1, gate.retries)
    }

    @Test
    fun `an add started while storage is prepared waits for it`() = runTest {
        val viewModel = createViewModel()
        gate.state.value = StorageState.Preparing(done = 1, total = 4)

        viewModel.add("otpauth://totp/Example:alice?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
        assertEquals(AddState(saving = true), viewModel.addState.value)
        assertTrue(repository.storedAccounts.isEmpty())

        gate.ready()

        assertEquals(AddState(saved = true), viewModel.addState.value)
        assertEquals(1, repository.storedAccounts.size)
    }

    @Test
    fun `add refused for want of a storage key says so`() = runTest {
        repository.addFailure = NewStorageKeyRequiredException()
        val viewModel = createViewModel()
        gate.ready(WriteAccess.NEW_KEY_REQUIRED)

        viewModel.add("otpauth://totp/Example:alice?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")

        assertEquals(AddState(error = AddError.STORAGE_KEY_REQUIRED), viewModel.addState.value)
    }
}
