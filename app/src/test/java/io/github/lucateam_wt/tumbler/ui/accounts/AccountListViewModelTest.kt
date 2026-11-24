package io.github.lucateam_wt.tumbler.ui.accounts

import android.database.SQLException
import io.github.lucateam_wt.tumbler.domain.crypto.KeyUnavailableException
import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipherException
import io.github.lucateam_wt.tumbler.domain.model.OtpAccount
import io.github.lucateam_wt.tumbler.domain.model.OtpType
import io.github.lucateam_wt.tumbler.domain.otp.Base32
import io.github.lucateam_wt.tumbler.domain.otp.Hotp
import io.github.lucateam_wt.tumbler.domain.otp.OtpAlgorithm
import io.github.lucateam_wt.tumbler.domain.otp.Totp
import io.github.lucateam_wt.tumbler.ui.accounts.AccountItem.HotpItem
import io.github.lucateam_wt.tumbler.ui.accounts.AccountItem.TotpItem
import io.github.lucateam_wt.tumbler.ui.accounts.AccountItem.UnavailableItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
    fun `add reports a label over the backup limit with its own error`() = runTest {
        val viewModel = createViewModel()

        viewModel.add("otpauth://totp/Example:${"a".repeat(1025)}?secret=$rfcBase32Secret")
        assertEquals(AddState(error = AddError.LABEL_TOO_LONG), viewModel.addState.value)

        viewModel.add("otpauth://totp/alice?secret=$rfcBase32Secret&issuer=${"a".repeat(1025)}")
        assertEquals(AddState(error = AddError.LABEL_TOO_LONG), viewModel.addState.value)
        assertTrue(repository.storedAccounts.isEmpty())
    }

    @Test
    fun `add reports a secret over the backup limit with its own error`() = runTest {
        val viewModel = createViewModel()

        // 1025 bytes: repeats of the public RFC seed's Base32 ("GEZDGNBV" is "12345").
        viewModel.add("otpauth://totp/Example:alice?secret=" + "GEZDGNBV".repeat(205))

        assertEquals(AddState(error = AddError.SECRET_TOO_LONG), viewModel.addState.value)
        assertTrue(repository.storedAccounts.isEmpty())
    }

    @Test
    fun `add keeps the invalid link error for every other bad link`() = runTest {
        val viewModel = createViewModel()

        viewModel.add("otpauth://totp/al\ud800ice?secret=$rfcBase32Secret")
        assertEquals(AddState(error = AddError.INVALID_LINK), viewModel.addState.value)

        viewModel.add("otpauth://totp/alice?secret=$rfcBase32Secret&digits=9")
        assertEquals(AddState(error = AddError.INVALID_LINK), viewModel.addState.value)
    }

    @Test
    fun `add reports a storage failure`() = runTest {
        repository.addFailure = SecretCipherException("Encryption failed")
        val viewModel = createViewModel()

        viewModel.add("otpauth://totp/Example:alice?secret=$rfcBase32Secret")

        assertEquals(AddState(error = AddError.STORAGE_FAILED), viewModel.addState.value)
    }

    @Test
    fun `add reports a database failure as a storage failure`() = runTest {
        repository.addFailure = SQLException("disk full")
        val viewModel = createViewModel()

        viewModel.add("otpauth://totp/Example:alice?secret=$rfcBase32Secret")

        assertEquals(AddState(error = AddError.STORAGE_FAILED), viewModel.addState.value)
    }

    @Test
    fun `second add while saving is ignored and stores one account`() = runTest {
        val gate = CompletableDeferred<Unit>()
        repository.addGate = gate
        val viewModel = createViewModel()
        val link = "otpauth://totp/Example:alice?secret=$rfcBase32Secret"

        viewModel.add(link)
        assertTrue(viewModel.addState.value.saving)
        viewModel.add(link)
        gate.complete(Unit)
        runCurrent()

        assertEquals(1, repository.storedAccounts.size)
        assertEquals(AddState(saved = true), viewModel.addState.value)
    }

    @Test
    fun `resetAddState keeps saving while a save is running`() = runTest {
        val gate = CompletableDeferred<Unit>()
        repository.addGate = gate
        val viewModel = createViewModel()
        viewModel.add("otpauth://totp/Example:alice?secret=$rfcBase32Secret")

        viewModel.resetAddState()

        assertTrue(viewModel.addState.value.saving)
        gate.complete(Unit)
    }

    @Test
    fun `cancellation is not turned into a storage failure`() = runTest {
        repository.addFailure = CancellationException("cancelled")
        val viewModel = createViewModel()

        viewModel.add("otpauth://totp/Example:alice?secret=$rfcBase32Secret")

        assertNull(viewModel.addState.value.error)
        assertFalse(viewModel.addState.value.saved)
    }

    @Test
    fun `delete failure sets the action flag until it is shown`() = runTest {
        val id = repository.add(totpAccount())
        val viewModel = createViewModel()
        repository.deleteFailure = SQLException("locked")

        viewModel.delete(id)

        assertTrue(viewModel.uiState.value.actionFailed)
        assertEquals(1, viewModel.items().size)

        viewModel.actionFailureShown()
        assertFalse(viewModel.uiState.value.actionFailed)
    }

    @Test
    fun `next code failure in storage shows no code and sets the action flag`() = runTest {
        val id = repository.add(hotpAccount())
        val viewModel = createViewModel()
        repository.nextHotpCodeFailure = SQLException("locked")

        viewModel.nextHotpCode(id)

        assertTrue(viewModel.uiState.value.actionFailed)
        assertNull((viewModel.items().single() as HotpItem).code)
        assertEquals(0L, repository.counterOf(id))
    }

    @Test
    fun `failure while observing accounts sets loadFailed instead of crashing`() = runTest {
        repository.observeFailure = SQLException("corrupt")

        val state = createViewModel().uiState.value

        assertTrue(state.loadFailed)
        assertFalse(state.isLoading)
        assertTrue(state.accounts.isEmpty())
    }

    @Test
    fun `reload restarts a list whose loading had failed`() = runTest {
        repository.observeFailure = SQLException("corrupt")
        val viewModel = createViewModel()
        assertTrue(viewModel.uiState.value.loadFailed)

        repository.observeFailure = null
        repository.add(totpAccount())
        viewModel.reload()

        assertFalse(viewModel.uiState.value.loadFailed)
        assertEquals("94287082", (viewModel.items().single() as TotpItem).code)
    }

    @Test
    fun `reload decrypts the accounts again`() = runTest {
        val id = repository.add(totpAccount(digits = 8))
        val viewModel = createViewModel()
        assertEquals("94287082", (viewModel.items().single() as TotpItem).code)
        // Overwritten like a committed import whose change the list has not been told about.
        repository.replaceWithoutPublishing(id, totpAccount(digits = 6))

        viewModel.reload()

        assertEquals("287082", (viewModel.items().single() as TotpItem).code)
    }

    @Test
    fun `nothing to export only once the list has loaded empty without a failure`() {
        assertTrue(AccountListUiState(isLoading = false).nothingToExport)
        assertFalse(AccountListUiState(isLoading = true).nothingToExport)
        assertFalse(AccountListUiState(isLoading = false, loadFailed = true).nothingToExport)
        val item = AccountItem.UnavailableItem("id", "ACME", "alice")
        assertFalse(AccountListUiState(isLoading = false, accounts = listOf(item)).nothingToExport)
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

    /**
     * The list decrypts every secret to tell whether its row is usable, and keeps none of the
     * decrypted arrays: a HOTP row keeps no secret at all ("next code" decrypts the stored
     * row again), and a TOTP row's generator keeps its own copy. That holds again for every
     * reload after the table changed.
     */
    @Test
    fun `the list wipes every decrypted secret it read, also after the table changed`() = runTest {
        val hotpId = repository.add(hotpAccount())
        repository.add(totpAccount())
        val viewModel = createViewModel()
        assertEquals(2, repository.handedOutSecrets.size)

        viewModel.nextHotpCode(hotpId) // stores counter 1, so the list loads again
        repository.add(totpAccount(name = "carol"))

        assertTrue("the list loaded again", repository.handedOutSecrets.size > 2)
        repository.handedOutSecrets.forEach { (id, secret) -> assertTrue("secret read for $id not wiped", secret.all { it == 0.toByte() }) }
        // Still working: the TOTP codes come from the generators' own copies (RFC 6238
        // vector), the HOTP code from the stored row (RFC 4226 vector for counter 0).
        val items = viewModel.items()
        assertEquals(listOf("94287082", "94287082"), items.filterIsInstance<TotpItem>().map { it.code })
        assertEquals("755224", items.filterIsInstance<HotpItem>().single().code)
    }

    /** Public RFC 6238 example secret, distinct from [rfcSecret]. */
    private val otherSecret = "JBSWY3DPEHPK3PXP"

    @Test
    fun `hotp next code uses the stored secret and counter when an import replaced the row before the list reloaded`() = runTest {
        val id = repository.add(hotpAccount(counter = 3))
        val viewModel = createViewModel()
        // A committed import replaced the row (matched by label only): new secret, counter 50.
        // The list still holds the account it decrypted before.
        val newSecret = Base32.decode(otherSecret)
        repository.replaceWithoutPublishing(id, hotpAccount(counter = 50).copy(secret = newSecret))

        viewModel.nextHotpCode(id)

        val item = viewModel.items().single() as HotpItem
        assertEquals(Hotp(newSecret).generate(50), item.code)
        assertNotEquals(Hotp(rfcSecret).generate(3), item.code)
        assertEquals(51L, repository.counterOf(id))
    }

    @Test
    fun `hotp next code never moves a counter back that an import raised before the list reloaded`() = runTest {
        val id = repository.add(hotpAccount(counter = 3))
        val viewModel = createViewModel()
        // Same secret, larger counter kept by an import (KEEP_LARGER_OF_STORED_AND_IMPORTED).
        repository.replaceWithoutPublishing(id, hotpAccount(counter = 50))

        viewModel.nextHotpCode(id)

        assertEquals(Hotp(rfcSecret).generate(50), (viewModel.items().single() as HotpItem).code)
        assertEquals(51L, repository.counterOf(id))
    }

    @Test
    fun `overlapping hotp next code requests get consecutive counters and different codes`() = runTest {
        val id = repository.add(hotpAccount())
        val viewModel = createViewModel()
        val gate = CompletableDeferred<Unit>()
        repository.nextHotpCodeGate = gate

        viewModel.nextHotpCode(id)
        viewModel.nextHotpCode(id)
        assertNull((viewModel.items().single() as HotpItem).code)
        assertEquals(1, repository.nextHotpCodeCalls) // the second waits for the first
        gate.complete(Unit)

        assertEquals(2, repository.nextHotpCodeCalls)
        assertEquals(2, repository.counterWrites)
        assertEquals(2L, repository.counterOf(id))
        // RFC 4226 counter 1; counter 0 ("755224") was shown first.
        assertEquals("287082", (viewModel.items().single() as HotpItem).code)
    }

    @Test
    fun `hotp next code survives a secret that no longer decrypts`() = runTest {
        val id = repository.add(hotpAccount())
        val viewModel = createViewModel()
        repository.getAccountFailures[id] = KeyUnavailableException()

        viewModel.nextHotpCode(id)

        val item = viewModel.items().single() as HotpItem
        assertNull(item.code)
        assertEquals(HotpError.FAILED, item.error)
        assertEquals(0L, repository.counterOf(id))

        repository.getAccountFailures.clear()
        viewModel.nextHotpCode(id)
        assertEquals("755224", (viewModel.items().single() as HotpItem).code)
    }

    @Test
    fun `hotp at the counter limit shows an error, no code and writes nothing`() = runTest {
        val id = repository.add(hotpAccount(counter = Long.MAX_VALUE))
        val other = repository.add(totpAccount())
        val viewModel = createViewModel()

        viewModel.nextHotpCode(id)

        val item = viewModel.items().first { it.id == id } as HotpItem
        assertNull(item.code)
        assertEquals(HotpError.LIMIT_REACHED, item.error)
        assertEquals(0, repository.counterWrites)
        assertEquals(Long.MAX_VALUE, repository.counterOf(id))
        assertFalse(viewModel.uiState.value.actionFailed)
        // Other accounts keep working.
        assertTrue(viewModel.items().first { it.id == other } is TotpItem)
    }

    @Test
    fun `hotp one below the limit works once and then moves to the limit error`() = runTest {
        val id = repository.add(hotpAccount(counter = Long.MAX_VALUE - 1))
        val viewModel = createViewModel()

        viewModel.nextHotpCode(id)

        val expected = Hotp(rfcSecret, OtpAlgorithm.SHA1, 6).generate(Long.MAX_VALUE - 1)
        assertEquals(HotpItem(id, "Example", "bob", expected), viewModel.items().single())
        assertEquals(Long.MAX_VALUE, repository.counterOf(id))
        assertEquals(1, repository.counterWrites)

        viewModel.nextHotpCode(id)

        val item = viewModel.items().single() as HotpItem
        assertNull(item.code)
        assertEquals(HotpError.LIMIT_REACHED, item.error)
        assertEquals(1, repository.counterWrites)
        assertEquals(Long.MAX_VALUE, repository.counterOf(id))
    }

    @Test
    fun `hotp next code ends in an error state when the stored row is gone or no longer hotp`() = runTest {
        val changes = listOf<(String) -> Unit>(
            { id -> repository.replaceWithoutPublishing(id, null) },
            { id -> repository.replaceWithoutPublishing(id, totpAccount()) },
        )
        for (change in changes) {
            val id = repository.add(hotpAccount())
            val viewModel = createViewModel()
            change(id)

            viewModel.nextHotpCode(id)

            val item = viewModel.items().last() as HotpItem
            assertNull(item.code)
            assertEquals(HotpError.FAILED, item.error)
            assertFalse(viewModel.uiState.value.actionFailed)

            // The error is not sticky: the next request that succeeds replaces it with a code.
            repository.replaceWithoutPublishing(id, hotpAccount())
            viewModel.nextHotpCode(id)
            val recovered = viewModel.items().last() as HotpItem
            assertEquals("755224", recovered.code)
            assertNull(recovered.error)
        }
    }

    /** Start of the 30 s step that ends just before RFC 6238 vector time 1111111110. */
    private val stepStart = 1_111_111_080L

    @Test
    fun `next code is null outside the window and set inside it`() = runTest {
        repository.add(totpAccount())
        val viewModel = createViewModel()

        for (remaining in listOf(30, 15, 11)) {
            now = stepStart + 30 - remaining
            advanceTimeBy(1_000)
            runCurrent()
            assertNull("remaining=$remaining", (viewModel.items().single() as TotpItem).nextCode)
        }
        for (remaining in listOf(10, 5, 1)) {
            now = stepStart + 30 - remaining
            advanceTimeBy(1_000)
            runCurrent()
            val item = viewModel.items().single() as TotpItem
            assertEquals(remaining, item.secondsRemaining)
            assertNotNull("remaining=$remaining", item.nextCode)
        }
    }

    @Test
    fun `next code is the TOTP of the next step`() = runTest {
        repository.add(totpAccount())
        now = stepStart + 29 // = 1111111109, 1 second left
        val item = createViewModel().items().single() as TotpItem

        assertEquals("07081804", item.code)
        assertEquals("14050471", item.nextCode) // RFC 6238 vector for time 1111111110
        assertEquals(Totp(rfcSecret, OtpAlgorithm.SHA1, 8, 30).generate(now + 30), item.nextCode)
    }

    @Test
    fun `at the step boundary the old next code is the new current code`() = runTest {
        repository.add(totpAccount())
        now = stepStart + 29
        val viewModel = createViewModel()
        val before = viewModel.items().single() as TotpItem
        assertNotNull(before.nextCode)

        now += 1
        advanceTimeBy(1_000)
        runCurrent()

        val after = viewModel.items().single() as TotpItem
        assertEquals(before.nextCode, after.code)
        assertNull(after.nextCode)
    }

    @Test
    fun `short period uses half the period as the window`() = runTest {
        repository.add(totpAccount().copy(period = 10))
        val viewModel = createViewModel()

        // Period 10: window is 5 seconds, not 10.
        now = 1_000L + 4 // remaining = 10 - (1004 mod 10) = 6
        advanceTimeBy(1_000)
        runCurrent()
        val outside = viewModel.items().single() as TotpItem
        assertEquals(6, outside.secondsRemaining)
        assertNull(outside.nextCode)

        now = 1_000L + 5 // remaining 5
        advanceTimeBy(1_000)
        runCurrent()
        val inside = viewModel.items().single() as TotpItem
        assertEquals(5, inside.secondsRemaining)
        assertEquals(Totp(rfcSecret, OtpAlgorithm.SHA1, 8, 10).generate(now + 10), inside.nextCode)
    }

    @Test
    fun `window rule`() {
        assertEquals(10, nextCodeWindowSeconds(30))
        assertEquals(10, nextCodeWindowSeconds(21))
        assertEquals(10, nextCodeWindowSeconds(20))
        assertEquals(5, nextCodeWindowSeconds(10))
        assertEquals(NEXT_CODE_SECONDS, nextCodeWindowSeconds(60))
    }

    @Test
    fun `hotp and unavailable items are unaffected by the window`() = runTest {
        val hotpId = repository.add(hotpAccount())
        val broken = repository.add(totpAccount(name = "broken"))
        repository.getAccountFailures[broken] = KeyUnavailableException()
        now = stepStart + 29
        val viewModel = createViewModel()
        viewModel.nextHotpCode(hotpId)

        val items = viewModel.items()

        assertEquals(HotpItem(hotpId, "Example", "bob", "755224"), items[0])
        assertEquals(UnavailableItem(broken, "Example", "broken"), items[1])
    }

    @Test
    fun `next code is not stored`() = runTest {
        repository.add(totpAccount())
        val stored = repository.storedAccounts
        now = stepStart + 29
        val viewModel = createViewModel()
        assertNotNull((viewModel.items().single() as TotpItem).nextCode)

        assertEquals(stored, repository.storedAccounts)
        assertFalse(
            "AccountItem must not be saved in instance state",
            java.io.Serializable::class.java.isAssignableFrom(TotpItem::class.java) ||
                android.os.Parcelable::class.java.isAssignableFrom(TotpItem::class.java),
        )
    }

    @Test
    fun `next code on a totp account changes nothing`() = runTest {
        val id = repository.add(totpAccount())
        val viewModel = createViewModel()

        viewModel.nextHotpCode(id)

        assertEquals(0L, repository.counterOf(id))
        assertEquals(0, repository.nextHotpCodeCalls)
    }
}
