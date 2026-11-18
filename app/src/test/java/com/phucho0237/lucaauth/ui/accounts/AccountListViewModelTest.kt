package com.phucho0237.lucaauth.ui.accounts

import com.phucho0237.lucaauth.domain.crypto.KeyUnavailableException
import com.phucho0237.lucaauth.domain.crypto.SecretCipherException
import com.phucho0237.lucaauth.domain.model.OtpAccount
import com.phucho0237.lucaauth.domain.model.OtpType
import com.phucho0237.lucaauth.ui.accounts.AccountItem.HotpItem
import com.phucho0237.lucaauth.ui.accounts.AccountItem.TotpItem
import com.phucho0237.lucaauth.ui.accounts.AccountItem.UnavailableItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountListViewModelTest {

    /** ASCII "12345678901234567890", the secret of the RFC 4226 and RFC 6238 SHA-1 vectors. */
    private val rfcSecret = "12345678901234567890".toByteArray(Charsets.US_ASCII)
    private val rfcBase32Secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    private val repository = FakeAccountRepository()
    private var now = 59L

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.createViewModel(): AccountListViewModel {
        val viewModel = AccountListViewModel(repository) { now }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect { } }
        return viewModel
    }

    private fun totpAccount(digits: Int = 8, name: String = "alice") = OtpAccount(
        issuer = "Example",
        accountName = name,
        type = OtpType.TOTP,
        secret = rfcSecret,
        digits = digits,
    )

    private fun hotpAccount(counter: Long = 0) = OtpAccount(
        issuer = "Example",
        accountName = "bob",
        type = OtpType.HOTP,
        secret = rfcSecret,
        counter = counter,
    )

    private fun AccountListViewModel.items() = uiState.value.accounts

    @Test
    fun `empty repository gives an empty loaded list`() = runTest {
        val viewModel = createViewModel()

        assertFalse(viewModel.uiState.value.isLoading)
        assertTrue(viewModel.items().isEmpty())
    }

    @Test
    fun `totp code matches the RFC 6238 vector`() = runTest {
        repository.add(totpAccount())
        now = 59

        val item = createViewModel().items().single() as TotpItem

        assertEquals("94287082", item.code)
        assertEquals("Example", item.issuer)
        assertEquals("alice", item.accountName)
        assertEquals(30, item.period)
    }

    @Test
    fun `countdown follows the clock`() = runTest {
        repository.add(totpAccount())
        now = 1_111_111_110L
        val viewModel = createViewModel()
        assertEquals(30, (viewModel.items().single() as TotpItem).secondsRemaining)

        now += 5
        advanceTimeBy(1_000)
        runCurrent()

        val item = viewModel.items().single() as TotpItem
        assertEquals(25, item.secondsRemaining)
        assertEquals("14050471", item.code)
    }

    @Test
    fun `code changes on the period boundary`() = runTest {
        repository.add(totpAccount())
        now = 1_111_111_109L
        val viewModel = createViewModel()
        val before = viewModel.items().single() as TotpItem
        assertEquals("07081804", before.code)
        assertEquals(1, before.secondsRemaining)

        now += 1
        advanceTimeBy(1_000)
        runCurrent()

        val after = viewModel.items().single() as TotpItem
        assertEquals("14050471", after.code)
        assertEquals(30, after.secondsRemaining)
    }

    @Test
    fun `account that cannot be decrypted becomes an unavailable row`() = runTest {
        val broken = repository.add(totpAccount(name = "broken"))
        val corrupt = repository.add(totpAccount(name = "corrupt"))
        repository.add(totpAccount(name = "fine"))
        repository.getAccountFailures[broken] = KeyUnavailableException()
        repository.getAccountFailures[corrupt] = SecretCipherException("Decryption failed")

        val items = createViewModel().items()

        assertEquals(3, items.size)
        assertTrue(items[0] is UnavailableItem)
        assertEquals("broken", items[0].accountName)
        assertTrue(items[1] is UnavailableItem)
        assertTrue(items[2] is TotpItem)
    }

    @Test
    fun `add stores a valid link`() = runTest {
        val viewModel = createViewModel()

        viewModel.add(
            "otpauth://totp/Example:alice?secret=$rfcBase32Secret&issuer=Example&digits=8",
        )

        assertTrue(viewModel.addState.value.saved)
        assertNull(viewModel.addState.value.error)
        assertEquals(1, repository.storedAccounts.size)
        assertEquals("94287082", (viewModel.items().single() as TotpItem).code)
    }

    @Test
    fun `add rejects an invalid link without storing anything`() = runTest {
        val viewModel = createViewModel()

        viewModel.add("https://example.com/not-a-link")

        assertEquals(AddState(error = AddError.INVALID_LINK), viewModel.addState.value)
        assertTrue(repository.storedAccounts.isEmpty())
    }

    @Test
    fun `add reports a storage failure`() = runTest {
        repository.addFailure = SecretCipherException("Encryption failed")
        val viewModel = createViewModel()

        viewModel.add("otpauth://totp/Example:alice?secret=$rfcBase32Secret")

        assertEquals(AddState(error = AddError.STORAGE_FAILED), viewModel.addState.value)
    }

    @Test
    fun `resetAddState clears the last result`() = runTest {
        val viewModel = createViewModel()
        viewModel.add("nonsense")

        viewModel.resetAddState()

        assertEquals(AddState(), viewModel.addState.value)
    }

    @Test
    fun `delete removes the account`() = runTest {
        val id = repository.add(totpAccount(name = "gone"))
        repository.add(totpAccount(name = "kept"))
        val viewModel = createViewModel()

        viewModel.delete(id)

        assertEquals(listOf("kept"), viewModel.items().map { it.accountName })
    }

    @Test
    fun `hotp shows no code until next code is requested`() = runTest {
        repository.add(hotpAccount())

        val item = createViewModel().items().single() as HotpItem

        assertNull(item.code)
    }

    @Test
    fun `hotp next code uses the RFC 4226 vectors and advances the counter`() = runTest {
        val id = repository.add(hotpAccount())
        val viewModel = createViewModel()

        viewModel.nextHotpCode(id)
        assertEquals("755224", (viewModel.items().single() as HotpItem).code)
        assertEquals(1L, repository.counterOf(id))

        viewModel.nextHotpCode(id)
        assertEquals("287082", (viewModel.items().single() as HotpItem).code)
        assertEquals(2L, repository.counterOf(id))
    }

    @Test
    fun `hotp next code survives a missing account`() = runTest {
        val id = repository.add(hotpAccount())
        val viewModel = createViewModel()
        repository.updateCounterFailure = NoSuchElementException("No HOTP account to update")

        viewModel.nextHotpCode(id)

        assertNull((viewModel.items().single() as HotpItem).code)
        assertEquals(0L, repository.counterOf(id))

        repository.updateCounterFailure = null
        viewModel.nextHotpCode(id)
        assertEquals("755224", (viewModel.items().single() as HotpItem).code)
    }

    @Test
    fun `next code on a totp account changes nothing`() = runTest {
        val id = repository.add(totpAccount())
        val viewModel = createViewModel()

        viewModel.nextHotpCode(id)

        assertEquals(0L, repository.counterOf(id))
    }
}
