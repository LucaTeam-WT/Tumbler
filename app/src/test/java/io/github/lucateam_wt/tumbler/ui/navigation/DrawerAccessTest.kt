package io.github.lucateam_wt.tumbler.ui.navigation

import io.github.lucateam_wt.tumbler.domain.crypto.KeyTemporarilyUnavailableException
import io.github.lucateam_wt.tumbler.domain.crypto.KeyUnavailableException
import io.github.lucateam_wt.tumbler.domain.model.OtpAccount
import io.github.lucateam_wt.tumbler.domain.model.OtpType
import io.github.lucateam_wt.tumbler.domain.otp.OtpAlgorithm
import io.github.lucateam_wt.tumbler.domain.repository.StorageGate
import io.github.lucateam_wt.tumbler.domain.repository.StorageState
import io.github.lucateam_wt.tumbler.domain.repository.WriteAccess
import io.github.lucateam_wt.tumbler.ui.accounts.AccountItem
import io.github.lucateam_wt.tumbler.ui.accounts.AccountListUiState
import io.github.lucateam_wt.tumbler.ui.accounts.AccountListViewModel
import io.github.lucateam_wt.tumbler.ui.accounts.FakeAccountRepository
import io.github.lucateam_wt.tumbler.ui.accounts.MigrationProgress
import io.github.lucateam_wt.tumbler.ui.accounts.StorageNotice
import io.github.lucateam_wt.tumbler.ui.accounts.StorageNoticeKind
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
import org.junit.Before
import org.junit.Test

/**
 * [drawerAccess]: the drawer is hidden only while the migration progress is shown; Import is
 * refused only while a new storage key is required. Checked on hand-made screen states and on
 * the states the accounts view model makes from every storage gate state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DrawerAccessTest {

    // ---- the pure function on screen states ----

    private val totp = AccountItem.TotpItem("t1", "Example", "alice", code = "287082", secondsRemaining = 1, period = 30)

    private fun loaded(notice: StorageNotice? = null, items: List<AccountItem> = listOf(totp)) =
        AccountListUiState(isLoading = false, accounts = items, storageNotice = notice)

    @Test
    fun `the migration progress hides the drawer`() {
        assertEquals(DrawerAccess.HIDDEN, drawerAccess(AccountListUiState(isLoading = true, migration = MigrationProgress(0, 8))))
        assertEquals(DrawerAccess.HIDDEN, drawerAccess(AccountListUiState(isLoading = true, migration = MigrationProgress(7, 8))))
    }

    @Test
    fun `the migration hides the drawer even with a stale banner or accounts`() {
        val state = loaded(StorageNotice(StorageNoticeKind.NEW_KEY_REQUIRED, 1)).copy(migration = MigrationProgress(1, 2))

        assertEquals(DrawerAccess.HIDDEN, drawerAccess(state))
    }

    @Test
    fun `plain loading without a progress keeps the drawer, as it kept the menu`() {
        assertEquals(DrawerAccess.AVAILABLE, drawerAccess(AccountListUiState()))
    }

    @Test
    fun `a loaded list, an empty list and a failed load keep the drawer`() {
        assertEquals(DrawerAccess.AVAILABLE, drawerAccess(loaded()))
        assertEquals(DrawerAccess.AVAILABLE, drawerAccess(loaded(null, emptyList())))
        assertEquals(DrawerAccess.AVAILABLE, drawerAccess(AccountListUiState(isLoading = false, loadFailed = true)))
    }

    @Test
    fun `a required new storage key refuses only the import`() {
        assertEquals(DrawerAccess.IMPORT_REFUSED, drawerAccess(loaded(StorageNotice(StorageNoticeKind.NEW_KEY_REQUIRED, 0))))
        assertEquals(DrawerAccess.IMPORT_REFUSED, drawerAccess(loaded(StorageNotice(StorageNoticeKind.NEW_KEY_REQUIRED, 3))))
    }

    @Test
    fun `unreadable accounts and temporary failures keep the whole drawer`() {
        assertEquals(DrawerAccess.AVAILABLE, drawerAccess(loaded(StorageNotice(StorageNoticeKind.UNREADABLE, 2))))
        assertEquals(DrawerAccess.AVAILABLE, drawerAccess(loaded(StorageNotice(StorageNoticeKind.TEMPORARY, 0))))
    }

    @Test
    fun `every notice kind has a decision`() {
        for (kind in StorageNoticeKind.entries) {
            val expected = if (kind == StorageNoticeKind.NEW_KEY_REQUIRED) DrawerAccess.IMPORT_REFUSED else DrawerAccess.AVAILABLE
            assertEquals(kind.name, expected, drawerAccess(loaded(StorageNotice(kind, 0))))
        }
    }

    // ---- through the view model, for every storage gate state ----

    private class FakeStorageGate : StorageGate {
        override val state = MutableStateFlow<StorageState>(StorageState.Preparing())
        private var generation = 0

        override fun start() = Unit

        override fun retry() = Unit

        override suspend fun startNewKey(): Boolean = true

        override suspend fun awaitReady() {
            state.first { it is StorageState.Ready }
        }

        fun ready(access: WriteAccess, retryAdvised: Boolean = false) {
            state.value = StorageState.Ready(access, retryAdvised, ++generation)
        }
    }

    private val repository = FakeAccountRepository()
    private val gate = FakeStorageGate()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun addTotp(name: String) = repository.add(
        OtpAccount(
            issuer = "Example",
            accountName = name,
            secret = "12345678901234567890".toByteArray(Charsets.US_ASCII),
            type = OtpType.TOTP,
            algorithm = OtpAlgorithm.SHA1,
            digits = 6,
            period = 30,
            counter = 0,
        ),
    )

    private fun TestScope.access(): () -> DrawerAccess {
        val viewModel = AccountListViewModel(repository, clock = { 59L }, storage = gate)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect { } }
        return { drawerAccess(viewModel.uiState.value) }
    }

    @Test
    fun `preparing with nothing to convert keeps the drawer`() = runTest {
        val access = access()

        gate.state.value = StorageState.Preparing(done = 0, total = 0)

        assertEquals(DrawerAccess.AVAILABLE, access())
    }

    @Test
    fun `preparing with accounts to convert hides the drawer until the gate is ready`() = runTest {
        addTotp("alice")
        val access = access()

        gate.state.value = StorageState.Preparing(done = 0, total = 3)
        assertEquals(DrawerAccess.HIDDEN, access())
        gate.state.value = StorageState.Preparing(done = 2, total = 3)
        assertEquals(DrawerAccess.HIDDEN, access())

        gate.ready(WriteAccess.AVAILABLE)
        assertEquals(DrawerAccess.AVAILABLE, access())
    }

    @Test
    fun `ready with write access keeps the whole drawer`() = runTest {
        addTotp("alice")
        val access = access()

        gate.ready(WriteAccess.AVAILABLE)

        assertEquals(DrawerAccess.AVAILABLE, access())
    }

    @Test
    fun `ready with a required new key refuses the import`() = runTest {
        val lost = addTotp("lost")
        repository.getAccountFailures[lost] = KeyUnavailableException()
        val access = access()

        gate.ready(WriteAccess.NEW_KEY_REQUIRED)

        assertEquals(DrawerAccess.IMPORT_REFUSED, access())
    }

    @Test
    fun `ready with a temporarily unavailable key or a retry advised keeps the whole drawer`() = runTest {
        addTotp("alice")
        val access = access()

        gate.ready(WriteAccess.TEMPORARILY_UNAVAILABLE)
        assertEquals(DrawerAccess.AVAILABLE, access())

        gate.ready(WriteAccess.AVAILABLE, retryAdvised = true)
        assertEquals(DrawerAccess.AVAILABLE, access())
    }

    @Test
    fun `unreadable and temporarily unreadable accounts keep the whole drawer`() = runTest {
        val lost = addTotp("lost")
        val flaky = addTotp("flaky")
        repository.getAccountFailures[lost] = KeyUnavailableException()
        repository.getAccountFailures[flaky] = KeyTemporarilyUnavailableException()
        val access = access()

        gate.ready(WriteAccess.AVAILABLE)

        assertEquals(DrawerAccess.AVAILABLE, access())
    }

    @Test
    fun `a new run of the gate hides the drawer again only if it shows a progress`() = runTest {
        addTotp("alice")
        val access = access()
        gate.ready(WriteAccess.NEW_KEY_REQUIRED)

        // A retry or a confirmed new key runs the gate again.
        gate.state.value = StorageState.Preparing(done = 0, total = 0)
        assertEquals(DrawerAccess.AVAILABLE, access())
        gate.state.value = StorageState.Preparing(done = 0, total = 1)
        assertEquals(DrawerAccess.HIDDEN, access())

        gate.ready(WriteAccess.AVAILABLE)
        assertEquals(DrawerAccess.AVAILABLE, access())
    }
}
