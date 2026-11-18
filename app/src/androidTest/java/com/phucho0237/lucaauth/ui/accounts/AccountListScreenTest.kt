package com.phucho0237.lucaauth.ui.accounts

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
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
    ) {
        composeRule.setContent {
            LucaAuthTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides if (fontScale == null) density else Density(density.density, fontScale),
                ) {
                    AccountListScreen(
                        state = state,
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

    /**
     * The extended FAB removes its label from the semantics tree and takes its accessible name
     * from the icon's content description, so it is found by that description and click action.
     */
    private fun addButton() = composeRule.onNode(
        hasContentDescription(string(R.string.accounts_add)) and hasClickAction(),
    )

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

    private val longIssuer = "An extremely long issuer name that keeps going ".repeat(6).trim()
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
