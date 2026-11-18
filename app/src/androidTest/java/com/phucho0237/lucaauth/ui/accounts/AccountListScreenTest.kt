package com.phucho0237.lucaauth.ui.accounts

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.phucho0237.lucaauth.R
import com.phucho0237.lucaauth.ui.theme.LucaAuthTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AccountListScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int) = composeRule.activity.getString(id)

    private fun show(state: AccountListUiState, onCopyCode: (String) -> Unit = {}) {
        composeRule.setContent {
            LucaAuthTheme {
                AccountListScreen(
                    state = state,
                    onAddClick = {},
                    onCopyCode = onCopyCode,
                    onNextHotpCode = {},
                    onDelete = {},
                    onActionFailureShown = {},
                )
            }
        }
    }

    @Test
    fun emptyStateIsShownWhenThereAreNoAccounts() {
        show(AccountListUiState(isLoading = false))

        composeRule.onNodeWithText(string(R.string.accounts_empty_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.accounts_add)).assertIsDisplayed()
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
    fun hotpItemShowsNextCodeButtonAndLastCode() {
        show(
            AccountListUiState(
                isLoading = false,
                accounts = listOf(AccountItem.HotpItem("1", "Example", "bob", code = "755224")),
            ),
        )

        composeRule.onNodeWithText("755 224").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.account_next_code)).assertIsDisplayed()
    }

    /**
     * The card's delete button and the dialog's confirm button have the same text
     * ("Delete") and are both in the semantics tree while the dialog is open, so each is
     * selected by whether it lives inside the dialog window.
     */
    private fun cardDeleteButton() = composeRule.onNode(
        hasText(string(R.string.account_delete)) and hasClickAction() and hasAnyAncestor(isDialog()).not(),
    )

    private fun dialogButton(textRes: Int) = composeRule.onNode(
        hasText(string(textRes)) and hasClickAction() and hasAnyAncestor(isDialog()),
    )

    private fun showDeletableAccount(onDelete: (String) -> Unit) {
        composeRule.setContent {
            LucaAuthTheme {
                AccountListScreen(
                    state = AccountListUiState(
                        isLoading = false,
                        accounts = listOf(AccountItem.UnavailableItem("1", "Example", "alice")),
                    ),
                    onAddClick = {},
                    onCopyCode = {},
                    onNextHotpCode = {},
                    onDelete = onDelete,
                    onActionFailureShown = {},
                )
            }
        }
    }

    @Test
    fun deleteAsksForConfirmationBeforeDeleting() {
        var deleted: String? = null
        showDeletableAccount { deleted = it }

        cardDeleteButton().performClick()
        assertEquals(null, deleted)
        composeRule.onNodeWithText(string(R.string.delete_dialog_title)).assertIsDisplayed()
        dialogButton(R.string.delete_dialog_confirm).performClick()

        assertEquals("1", deleted)
        composeRule.onNodeWithText(string(R.string.delete_dialog_title)).assertDoesNotExist()
    }

    @Test
    fun cancellingTheDeleteConfirmationKeepsTheAccount() {
        var deleted: String? = null
        showDeletableAccount { deleted = it }

        cardDeleteButton().performClick()
        composeRule.onNodeWithText(string(R.string.delete_dialog_title)).assertIsDisplayed()
        dialogButton(R.string.add_cancel).performClick()

        assertEquals(null, deleted)
        composeRule.onNodeWithText(string(R.string.delete_dialog_title)).assertDoesNotExist()
        composeRule.onNodeWithText("alice").assertIsDisplayed()
    }

    @Test
    fun listShowsGroupedCodeAndAccountDetails() {
        show(
            AccountListUiState(
                isLoading = false,
                accounts = listOf(
                    AccountItem.TotpItem(
                        id = "1",
                        issuer = "Example",
                        accountName = "alice@example.com",
                        code = "123456",
                        secondsRemaining = 12,
                        period = 30,
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("123 456").assertIsDisplayed()
        composeRule.onNodeWithText("Example").assertIsDisplayed()
        composeRule.onNodeWithText("alice@example.com").assertIsDisplayed()
    }

    @Test
    fun tappingACardCopiesTheRawCode() {
        var copied: String? = null
        show(
            AccountListUiState(
                isLoading = false,
                accounts = listOf(
                    AccountItem.TotpItem("1", "Example", "alice", "123456", secondsRemaining = 20, period = 30),
                ),
            ),
            onCopyCode = { copied = it },
        )

        composeRule.onNodeWithText("123 456").performClick()

        assertEquals("123456", copied)
        composeRule.onNodeWithText(string(R.string.account_code_copied)).assertIsDisplayed()
    }

    @Test
    fun unavailableAccountExplainsWhyThereIsNoCode() {
        show(
            AccountListUiState(
                isLoading = false,
                accounts = listOf(AccountItem.UnavailableItem("1", "Example", "alice")),
            ),
        )

        composeRule.onNodeWithText(string(R.string.account_unavailable_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.account_unavailable_body)).assertIsDisplayed()
    }
}
