package com.phucho0237.lucaauth.ui.accounts

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.Density
import com.phucho0237.lucaauth.R
import com.phucho0237.lucaauth.ui.theme.LucaAuthTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AccountListScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)

    private val totp = AccountItem.TotpItem(
        id = "t1",
        issuer = "Example",
        accountName = "alice",
        code = "123456",
        secondsRemaining = 12,
        period = 30,
    )
    private val hotp = AccountItem.HotpItem(id = "h1", issuer = "Example", accountName = "bob", code = "755224")
    private val unavailable = AccountItem.UnavailableItem(id = "u1", issuer = "Example", accountName = "carol")

    private fun stateOf(vararg items: AccountItem) =
        AccountListUiState(isLoading = false, accounts = items.toList())

    private fun show(
        state: AccountListUiState,
        onCopyCode: (String) -> Unit = {},
        onNextHotpCode: (String) -> Unit = {},
        onDelete: (String) -> Unit = {},
        fontScale: Float? = null,
    ) = showState(onCopyCode, onNextHotpCode, onDelete, fontScale) { state }

    /** Shows a single TOTP row that the test can change afterwards through the returned state. */
    private fun showChanging(first: AccountItem.TotpItem, fontScale: Float? = null): MutableState<AccountItem.TotpItem> {
        val item = mutableStateOf(first)
        showState(fontScale = fontScale) { stateOf(item.value) }
        return item
    }

    private fun showState(
        onCopyCode: (String) -> Unit = {},
        onNextHotpCode: (String) -> Unit = {},
        onDelete: (String) -> Unit = {},
        fontScale: Float? = null,
        state: () -> AccountListUiState,
    ) {
        composeRule.setContent {
            LucaAuthTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides if (fontScale == null) density else Density(density.density, fontScale),
                ) {
                    AccountListScreen(
                        state = state(),
                        onAddClick = {},
                        onCopyCode = onCopyCode,
                        onNextHotpCode = onNextHotpCode,
                        onDelete = onDelete,
                        onActionFailureShown = {},
                    )
                }
            }
        }
    }

    private fun row(item: AccountItem) = composeRule.onNodeWithTag(AccountListTags.row(item.id))

    private fun longPress(item: AccountItem) = row(item).performTouchInput { longClick() }

    private fun menuItem(tag: String) = composeRule.onNodeWithTag(tag)

    private fun dialogButton(textRes: Int) = composeRule.onNode(
        hasText(string(textRes)) and hasClickAction() and hasAnyAncestor(isDialog()),
    )

    /** The icon-only add button, found by its test tag. */
    private fun addButton() = composeRule.onNodeWithTag(AccountListTags.ADD_BUTTON)

    private fun SemanticsNodeInteraction.customActionLabels(): List<String> =
        fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label }

    @Test
    fun emptyStateIsShownWhenThereAreNoAccounts() {
        show(AccountListUiState(isLoading = false))

        composeRule.onNodeWithText(string(R.string.accounts_empty_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.accounts_empty_body)).assertIsDisplayed()
        addButton().assertIsDisplayed()
    }

    @Test
    fun addButtonHasAnAccessibleNameAndRequestsTheAddScreen() {
        var addClicks = 0
        composeRule.setContent {
            LucaAuthTheme {
                AccountListScreen(
                    state = AccountListUiState(isLoading = false),
                    onAddClick = { addClicks++ },
                    onCopyCode = {},
                    onNextHotpCode = {},
                    onDelete = {},
                    onActionFailureShown = {},
                )
            }
        }

        addButton().performClick()

        assertEquals(1, addClicks)
    }

    @Test
    fun addButtonIsIconOnlyAndNamedByItsContentDescription() {
        show(AccountListUiState(isLoading = false))

        addButton().assertHasClickAction()
        addButton().assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf(string(R.string.accounts_add)),
            ),
        )
        composeRule.onNode(hasContentDescription(string(R.string.accounts_add)) and hasClickAction())
            .assertIsDisplayed()
        // No visible text label: the "Add account" string is only the content description.
        composeRule.onNodeWithText(string(R.string.accounts_add)).assertDoesNotExist()
    }

    @Test
    fun loadingStateShowsAProgressIndicator() {
        show(AccountListUiState(isLoading = true))

        composeRule.onNodeWithContentDescription(string(R.string.accounts_loading)).assertIsDisplayed()
    }

    @Test
    fun loadFailureIsExplained() {
        show(AccountListUiState(isLoading = false, loadFailed = true))

        composeRule.onNodeWithText(string(R.string.accounts_load_failed_title)).assertIsDisplayed()
    }

    @Test
    fun totpRowShowsGroupedCodeAccountDetailsAndCountdown() {
        show(stateOf(totp))

        composeRule.onNodeWithText("123 456").assertIsDisplayed()
        composeRule.onNodeWithText("Example").assertIsDisplayed()
        composeRule.onNodeWithText("alice").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.account_seconds_remaining, 12)).assertIsDisplayed()
    }

    @Test
    fun tappingARowCopiesTheRawCodeAndShowsTheSnackbar() {
        var copied: String? = null
        show(stateOf(totp), onCopyCode = { copied = it })

        row(totp).performClick()

        assertEquals("123456", copied)
        composeRule.onNodeWithText(string(R.string.account_code_copied)).assertIsDisplayed()
    }

    @Test
    fun hotpRowShowsLastCodeAndAnIconButtonForTheNextCode() {
        show(stateOf(hotp))

        composeRule.onNodeWithText("755 224").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.action_next_code_for, "Example (bob)"))
            .assertIsDisplayed()
    }

    @Test
    fun hotpIconButtonRequestsTheNextCodeForThatAccount() {
        var requested: String? = null
        show(stateOf(hotp), onNextHotpCode = { requested = it })

        composeRule.onNodeWithContentDescription(string(R.string.action_next_code_for, "Example (bob)"))
            .performClick()

        assertEquals("h1", requested)
    }

    @Test
    fun hotpRowWithoutACodeShowsAPlaceholder() {
        show(stateOf(hotp.copy(code = null)))

        composeRule.onNodeWithContentDescription(string(R.string.account_hotp_no_code_description))
            .assertIsDisplayed()
    }

    @Test
    fun unavailableRowExplainsWhyThereIsNoCode() {
        show(stateOf(unavailable))

        composeRule.onNodeWithText(string(R.string.account_unavailable_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.account_unavailable_body)).assertIsDisplayed()
    }

    @Test
    fun longPressOnATotpRowOffersCopyAndDelete() {
        show(stateOf(totp))

        longPress(totp)

        menuItem(AccountListTags.MENU_COPY).assertIsDisplayed()
        menuItem(AccountListTags.MENU_DELETE).assertIsDisplayed()
        menuItem(AccountListTags.MENU_NEXT_CODE).assertDoesNotExist()
    }

    @Test
    fun longPressOnAHotpRowOffersCopyNextCodeAndDelete() {
        show(stateOf(hotp))

        longPress(hotp)

        menuItem(AccountListTags.MENU_COPY).assertIsDisplayed()
        menuItem(AccountListTags.MENU_NEXT_CODE).assertIsDisplayed()
        menuItem(AccountListTags.MENU_DELETE).assertIsDisplayed()
    }

    @Test
    fun longPressOnAnUnavailableRowOffersOnlyDelete() {
        show(stateOf(unavailable))

        longPress(unavailable)

        menuItem(AccountListTags.MENU_DELETE).assertIsDisplayed()
        menuItem(AccountListTags.MENU_COPY).assertDoesNotExist()
        menuItem(AccountListTags.MENU_NEXT_CODE).assertDoesNotExist()
    }

    @Test
    fun menuCopyCopiesTheCode() {
        var copied: String? = null
        show(stateOf(totp), onCopyCode = { copied = it })

        longPress(totp)
        menuItem(AccountListTags.MENU_COPY).performClick()

        assertEquals("123456", copied)
    }

    @Test
    fun menuNextCodeRequestsTheNextCode() {
        var requested: String? = null
        show(stateOf(hotp), onNextHotpCode = { requested = it })

        longPress(hotp)
        menuItem(AccountListTags.MENU_NEXT_CODE).performClick()

        assertEquals("h1", requested)
    }

    @Test
    fun deleteAsksForConfirmationBeforeDeleting() {
        var deleted: String? = null
        show(stateOf(unavailable), onDelete = { deleted = it })

        longPress(unavailable)
        menuItem(AccountListTags.MENU_DELETE).performClick()
        assertEquals(null, deleted)
        composeRule.onNodeWithText(string(R.string.delete_dialog_title)).assertIsDisplayed()
        dialogButton(R.string.delete_dialog_confirm).performClick()

        assertEquals("u1", deleted)
        composeRule.onNodeWithText(string(R.string.delete_dialog_title)).assertDoesNotExist()
    }

    @Test
    fun cancellingTheDeleteConfirmationKeepsTheAccount() {
        var deleted: String? = null
        show(stateOf(unavailable), onDelete = { deleted = it })

        longPress(unavailable)
        menuItem(AccountListTags.MENU_DELETE).performClick()
        composeRule.onNodeWithText(string(R.string.delete_dialog_title)).assertIsDisplayed()
        dialogButton(R.string.add_cancel).performClick()

        assertEquals(null, deleted)
        composeRule.onNodeWithText(string(R.string.delete_dialog_title)).assertDoesNotExist()
        composeRule.onNodeWithText("carol").assertIsDisplayed()
    }

    @Test
    fun deleteConfirmationNamesTheAccount() {
        show(stateOf(totp))

        longPress(totp)
        menuItem(AccountListTags.MENU_DELETE).performClick()

        composeRule.onNode(hasText("Example (alice)", substring = true) and hasAnyAncestor(isDialog()))
            .assertIsDisplayed()
    }

    @Test
    fun rowsExposeCustomAccessibilityActionsNamingTheAccount() {
        show(stateOf(totp, hotp, unavailable))

        assertEquals(
            listOf(
                string(R.string.action_copy_code_for, "Example (alice)"),
                string(R.string.action_delete_for, "Example (alice)"),
            ),
            row(totp).customActionLabels(),
        )
        assertEquals(
            listOf(
                string(R.string.action_copy_code_for, "Example (bob)"),
                string(R.string.action_next_code_for, "Example (bob)"),
                string(R.string.action_delete_for, "Example (bob)"),
            ),
            row(hotp).customActionLabels(),
        )
        assertEquals(
            listOf(string(R.string.action_delete_for, "Example (carol)")),
            row(unavailable).customActionLabels(),
        )
    }

    @Test
    fun deleteCustomActionOpensTheConfirmationDialog() {
        var deleted: String? = null
        show(stateOf(totp), onDelete = { deleted = it })

        val action = row(totp).fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .first { it.label == string(R.string.action_delete_for, "Example (alice)") }
        composeRule.runOnUiThread { action.action() }

        composeRule.onNodeWithText(string(R.string.delete_dialog_title)).assertIsDisplayed()
        assertEquals(null, deleted)
    }

    @Test
    fun allRowTypesStayReadableAtTwoHundredPercentFontScale() {
        show(stateOf(totp, hotp, unavailable), fontScale = 2f)

        composeRule.onNodeWithText("123 456").assertIsDisplayed()
        composeRule.onNodeWithText("alice").assertIsDisplayed()
        composeRule.onNodeWithText("755 224").assertIsDisplayed()
        composeRule.onNodeWithText("bob").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.account_unavailable_body)).assertIsDisplayed()
    }

    private val totpWithNext = totp.copy(secondsRemaining = 4, nextCode = "654321")

    private fun nextCodeNode() = composeRule.onNodeWithTag(AccountListTags.NEXT_CODE, useUnmergedTree = true)

    @Test
    fun nextCodeIsShownWithTextAndItsOwnDescription() {
        show(stateOf(totpWithNext))

        nextCodeNode().assertIsDisplayed()
        nextCodeNode().assertTextEquals(string(R.string.account_next_totp_code, "654 321"))
        nextCodeNode().assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf(string(R.string.account_next_totp_code_description, "654 321")),
            ),
        )
        composeRule.onNodeWithText("123 456").assertIsDisplayed()
    }

    @Test
    fun nextCodeIsAbsentWhenNull() {
        show(stateOf(totp))

        nextCodeNode().assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.account_next_totp_code, "123 456"), substring = true)
            .assertDoesNotExist()
    }

    @Test
    fun tappingARowWithANextCodeCopiesOnlyTheCurrentCode() {
        val copied = mutableListOf<String>()
        show(stateOf(totpWithNext), onCopyCode = { copied += it })

        row(totpWithNext).performClick()

        assertEquals(listOf("123456"), copied)
    }

    @Test
    fun nextCodeAddsNoMenuItemOrCustomAction() {
        show(stateOf(totpWithNext))

        assertEquals(
            row(totp).customActionLabels().size,
            row(totpWithNext).customActionLabels().size,
        )
        longPress(totpWithNext)
        menuItem(AccountListTags.MENU_COPY).assertIsDisplayed()
        menuItem(AccountListTags.MENU_DELETE).assertIsDisplayed()
        menuItem(AccountListTags.MENU_NEXT_CODE).assertDoesNotExist()
    }

    private fun rowHeight(id: String) = composeRule.onNodeWithTag(AccountListTags.row(id)).fetchSemanticsNode().size.height

    @Test
    fun currentCodeIsVerticallyCenteredInTheRowWithoutANextCode() {
        show(stateOf(totp))

        val rowBounds = row(totp).fetchSemanticsNode().boundsInRoot
        val code = composeRule.onNodeWithText("123 456", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val tolerancePx = 2 * composeRule.density.density
        assertEquals(rowBounds.center.y, code.center.y, tolerancePx)
    }

    @Test
    fun rowHeightIsTheSameWithAndWithoutTheNextCodeAtNormalFontScale() {
        val item = showChanging(totp)
        val without = rowHeight("t1")

        item.value = totpWithNext
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()

        nextCodeNode().assertIsDisplayed()
        assertEquals(without, rowHeight("t1"))
    }

    private fun assertNextCodeFitsAndRowReturnsAt(fontScale: Float) {
        val item = showChanging(totp, fontScale = fontScale)
        val original = rowHeight("t1")

        item.value = totpWithNext
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("123 456", useUnmergedTree = true).assertIsDisplayed()
        nextCodeNode().assertIsDisplayed()
        // Both codes stay inside the row, whether they share a line or the next code wraps below.
        val rowBounds = row(totp).fetchSemanticsNode().boundsInRoot
        val code = composeRule.onNodeWithText("123 456", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val next = nextCodeNode().fetchSemanticsNode().boundsInRoot
        for (bounds in listOf(code, next)) {
            assertEquals("fontScale=$fontScale", true, bounds.left >= rowBounds.left)
            assertEquals("fontScale=$fontScale", true, bounds.right <= rowBounds.right)
            assertEquals("fontScale=$fontScale", true, bounds.top >= rowBounds.top)
            assertEquals("fontScale=$fontScale", true, bounds.bottom <= rowBounds.bottom)
        }

        item.value = totp
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()

        nextCodeNode().assertDoesNotExist()
        assertEquals("fontScale=$fontScale", original, rowHeight("t1"))
    }

    @Test
    fun nextCodeFitsAndRowReturnsAtFontScale13() = assertNextCodeFitsAndRowReturnsAt(1.3f)

    @Test
    fun nextCodeFitsAndRowReturnsAtFontScale15() = assertNextCodeFitsAndRowReturnsAt(1.5f)

    @Test
    fun nextCodeFitsAndRowReturnsAtFontScale20() = assertNextCodeFitsAndRowReturnsAt(2f)

    @Test
    fun nextCodeSemanticsExistFromTheFirstFrameOfTheEntrance() {
        composeRule.mainClock.autoAdvance = false
        val item = showChanging(totp)
        nextCodeNode().assertDoesNotExist()

        item.value = totpWithNext
        // One frame only: recomposed, but the 400 ms entrance has barely started.
        composeRule.mainClock.advanceTimeByFrame()

        nextCodeNode().assertExists()
        nextCodeNode().assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf(string(R.string.account_next_totp_code_description, "654 321")),
            ),
        )
    }

    @Test
    fun nextCodeLosesItsSemanticsAsSoonAsItStartsToExit() {
        val item = showChanging(totpWithNext)
        nextCodeNode().assertExists()

        composeRule.mainClock.autoAdvance = false
        item.value = totp
        // One frame: recomposed with no next code, the 300 ms exit has barely started.
        composeRule.mainClock.advanceTimeByFrame()

        nextCodeNode().assertDoesNotExist()
        composeRule.onNodeWithContentDescription(
            string(R.string.account_next_totp_code_description, "654 321"),
            useUnmergedTree = true,
        ).assertDoesNotExist()

        composeRule.mainClock.advanceTimeBy(NEXT_CODE_EXIT_MILLIS + 100L)
        nextCodeNode().assertDoesNotExist()
    }

    @Test
    fun currentCodeIsCenteredAgainAndRowHeightUnchangedAfterTheExit() {
        val item = showChanging(totp)
        val original = rowHeight("t1")
        item.value = totpWithNext
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()

        item.value = totp
        composeRule.mainClock.advanceTimeBy(NEXT_CODE_EXIT_MILLIS + 100L)
        composeRule.waitForIdle()

        val rowBounds = row(totp).fetchSemanticsNode().boundsInRoot
        val code = composeRule.onNodeWithText("123 456", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(rowBounds.center.y, code.center.y, 2 * composeRule.density.density)
        assertEquals(original, rowHeight("t1"))
    }

    @Test
    fun nextCodeIsEndAlignedUnderTheCurrentCodeAtNormalFontScale() {
        show(stateOf(totpWithNext))

        val code = composeRule.onNodeWithText("123 456", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val next = nextCodeNode().fetchSemanticsNode().boundsInRoot
        assertEquals(code.right, next.right, 1f)
        assertEquals(true, next.top >= code.bottom - 1f)
    }

    /** Shows a list whose accounts the test can change afterwards; time only moves when the test says so. */
    private fun showChangingList(vararg first: AccountItem): MutableState<List<AccountItem>> {
        val accounts = mutableStateOf(first.toList())
        showState { AccountListUiState(isLoading = false, accounts = accounts.value) }
        return accounts
    }

    @Test
    fun deletedRowIsRemovedFromTheTreeAfterTheAnimation() {
        composeRule.mainClock.autoAdvance = false
        val accounts = showChangingList(totp, hotp)
        row(totp).assertExists()
        row(hotp).assertExists()

        accounts.value = listOf(hotp)
        composeRule.mainClock.advanceTimeBy(1_000)

        row(totp).assertDoesNotExist()
        row(hotp).assertExists()
        composeRule.onNodeWithText("alice").assertDoesNotExist()
    }

    @Test
    fun rowsStayFindableWithOneNodeEachAfterAnAddAndADelete() {
        composeRule.mainClock.autoAdvance = false
        val accounts = showChangingList(totp)

        accounts.value = listOf(totp, hotp)
        composeRule.mainClock.advanceTimeBy(1_000)
        row(totp).assertExists()
        row(hotp).assertExists()

        accounts.value = listOf(hotp)
        composeRule.mainClock.advanceTimeBy(1_000)
        row(totp).assertDoesNotExist()
        row(hotp).assertExists()
    }

    @Test
    fun switchingFromEmptyToTheListShowsTheListAndDropsTheEmptyState() {
        composeRule.mainClock.autoAdvance = false
        val accounts = showChangingList()
        composeRule.onNodeWithText(string(R.string.accounts_empty_title)).assertExists()

        accounts.value = listOf(totp)
        composeRule.mainClock.advanceTimeByFrame()
        // The empty state is fading out: drawn, but already gone from the semantics tree.
        composeRule.onNodeWithText(string(R.string.accounts_empty_title)).assertDoesNotExist()
        composeRule.mainClock.advanceTimeBy(1_000)

        row(totp).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.accounts_empty_title)).assertDoesNotExist()
    }

    @Test
    fun deletingTheLastAccountShowsTheEmptyStateAndRemovesTheRow() {
        composeRule.mainClock.autoAdvance = false
        val accounts = showChangingList(totp)
        row(totp).assertExists()

        accounts.value = emptyList()
        composeRule.mainClock.advanceTimeByFrame()
        row(totp).assertDoesNotExist()
        composeRule.mainClock.advanceTimeBy(1_000)

        composeRule.onNodeWithText(string(R.string.accounts_empty_title)).assertIsDisplayed()
        row(totp).assertDoesNotExist()
    }

    @Test
    fun ringDescriptionIsOneNodeThatFollowsTheSecondsWhileTheRingSweeps() {
        composeRule.mainClock.autoAdvance = false
        val item = showChanging(totp)
        composeRule.onNodeWithContentDescription(string(R.string.account_seconds_remaining, 12)).assertExists()

        item.value = totp.copy(secondsRemaining = 11)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(500)

        composeRule.onNodeWithContentDescription(string(R.string.account_seconds_remaining, 11)).assertExists()
        composeRule.onNodeWithContentDescription(string(R.string.account_seconds_remaining, 12)).assertDoesNotExist()

        // The period restarts: the description follows, still a single node.
        item.value = totp.copy(secondsRemaining = 30)
        composeRule.mainClock.advanceTimeBy(1_500)
        composeRule.onNodeWithContentDescription(string(R.string.account_seconds_remaining, 30)).assertExists()
    }

    private val longIssuer ="An extremely long issuer name that keeps going ".repeat(6).trim()
    private val longAccountName = "someone.with.a.very.long.account.name.".repeat(6) + "@example.com"
    private val longTotp = AccountItem.TotpItem(
        id = "long1",
        issuer = longIssuer,
        accountName = longAccountName,
        code = "654321",
        secondsRemaining = 7,
        period = 30,
    )
    private val longDisplayName = "$longIssuer ($longAccountName)"

    private fun SemanticsNodeInteraction.mergedTexts(): List<String> =
        fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text }

    @Test
    fun veryLongNamesDoNotMakeARowTallerAndKeepTheirFullTextInSemantics() {
        show(stateOf(totp, longTotp))

        // The row is cut to one line per name, so it is as tall as a row with short names.
        assertEquals(
            row(totp).fetchSemanticsNode().size.height,
            row(longTotp).fetchSemanticsNode().size.height,
        )
        // The ellipsis only affects drawing: the full text is still in the row's semantics...
        val texts = row(longTotp).mergedTexts()
        assertEquals(true, longIssuer in texts)
        assertEquals(true, longAccountName in texts)
        // ... and in the accessibility actions.
        assertEquals(
            listOf(
                string(R.string.action_copy_code_for, longDisplayName),
                string(R.string.action_delete_for, longDisplayName),
            ),
            row(longTotp).customActionLabels(),
        )
        // The code and the countdown are still shown.
        composeRule.onNodeWithText("654 321").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.account_seconds_remaining, 7)).assertIsDisplayed()
    }

    @Test
    fun veryLongNamesStayBoundedAndCompleteAtLargeFontScale() {
        show(stateOf(longTotp), fontScale = 1.3f)

        assertEquals(true, longIssuer in row(longTotp).mergedTexts())
        assertEquals(true, longAccountName in row(longTotp).mergedTexts())
        composeRule.onNodeWithText("654 321").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.account_seconds_remaining, 7)).assertIsDisplayed()
    }

    @Test
    fun deleteConfirmationShowsTheFullLongName() {
        show(stateOf(longTotp))

        longPress(longTotp)
        menuItem(AccountListTags.MENU_DELETE).performClick()

        composeRule.onNode(hasText(longDisplayName, substring = true) and hasAnyAncestor(isDialog()))
            .assertIsDisplayed()
    }
}
