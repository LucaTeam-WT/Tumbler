package io.github.lucateam_wt.tumbler.ui.accounts

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.lucateam_wt.tumbler.R
import io.github.lucateam_wt.tumbler.ui.theme.TumblerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The storage states on the accounts screen: the migration progress, the banners and the new-key dialog. */
class StorageScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)

    private fun plural(id: Int, count: Int, vararg args: Any) =
        composeRule.activity.resources.getQuantityString(id, count, *args)

    private val totp = AccountItem.TotpItem(
        id = "t1",
        issuer = "Example",
        accountName = "alice",
        code = "123456",
        secondsRemaining = 12,
        period = 30,
    )

    private var newKeyRequests = 0
    private var retries = 0
    private var imports = 0

    private fun show(state: AccountListUiState) {
        composeRule.setContent {
            TumblerTheme {
                AccountListScreen(
                    state = state,
                    onAddClick = {},
                    onCopyCode = {},
                    onNextHotpCode = {},
                    onDelete = {},
                    onActionFailureShown = {},
                    onImportBackup = { imports++ },
                    onStartNewStorageKey = { newKeyRequests++ },
                    onRetryStorage = { retries++ },
                )
            }
        }
    }

    private fun listWith(notice: StorageNotice?, vararg items: AccountItem) =
        AccountListUiState(isLoading = false, accounts = items.toList(), storageNotice = notice)

    @Test
    fun migrationShowsNOfMAndHidesTheListTheAddButtonAndTheBackupMenu() {
        show(AccountListUiState(isLoading = true, accounts = listOf(totp), migration = MigrationProgress(3, 8)))

        composeRule.onNodeWithText(string(R.string.storage_migrating_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.storage_migrating_progress, 3, 8)).assertIsDisplayed()
        composeRule.onNodeWithTag(StorageTags.MIGRATION_PROGRESS)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf(plural(R.plurals.storage_migrating_description, 8, 3, 8)),
                ),
            )
        composeRule.onNodeWithTag(AccountListTags.ADD_BUTTON).assertDoesNotExist()
        composeRule.onNodeWithTag(AccountListTags.MORE_OPTIONS).assertDoesNotExist()
        composeRule.onNodeWithTag(AccountListTags.row(totp.id)).assertDoesNotExist()
    }

    @Test
    fun newKeyBannerCountsUnreadableAccountsAndStartsAKeyOnlyAfterConfirmation() {
        show(listWith(StorageNotice(StorageNoticeKind.NEW_KEY_REQUIRED, unreadableCount = 2), totp))

        composeRule.onNodeWithText(plural(R.plurals.storage_unreadable_title, 2, 2)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.storage_new_key_body)).assertIsDisplayed()
        composeRule.onNodeWithTag(StorageTags.NEW_KEY_BUTTON).performClick()

        composeRule.onNodeWithText(string(R.string.storage_new_key_dialog_body)).assertIsDisplayed()
        assertEquals(0, newKeyRequests)
        composeRule.onNodeWithTag(StorageTags.NEW_KEY_CONFIRM).performClick()

        assertEquals(1, newKeyRequests)
        composeRule.onNodeWithText(string(R.string.storage_new_key_dialog_body)).assertDoesNotExist()
    }

    @Test
    fun cancellingTheNewKeyDialogStartsNothing() {
        show(listWith(StorageNotice(StorageNoticeKind.NEW_KEY_REQUIRED, unreadableCount = 0), totp))

        composeRule.onNodeWithText(string(R.string.storage_new_key_title)).assertIsDisplayed()
        composeRule.onNodeWithTag(StorageTags.NEW_KEY_BUTTON).performClick()
        composeRule.onNodeWithText(string(R.string.add_cancel)).performClick()

        assertEquals(0, newKeyRequests)
        composeRule.onNodeWithText(string(R.string.storage_new_key_dialog_body)).assertDoesNotExist()
    }

    @Test
    fun importIsRefusedWithAMessageWhileANewKeyIsRequired() {
        show(listWith(StorageNotice(StorageNoticeKind.NEW_KEY_REQUIRED, unreadableCount = 0), totp))

        composeRule.onNodeWithTag(AccountListTags.MORE_OPTIONS).performClick()
        composeRule.onNodeWithTag(AccountListTags.MENU_IMPORT).performClick()

        assertEquals(0, imports)
        composeRule.onNodeWithText(string(R.string.backup_import_needs_new_key)).assertIsDisplayed()
    }

    @Test
    fun temporaryBannerOffersTryAgain() {
        show(listWith(StorageNotice(StorageNoticeKind.TEMPORARY, unreadableCount = 0), totp))

        composeRule.onNodeWithText(string(R.string.storage_temporary_title)).assertIsDisplayed()
        composeRule.onNodeWithTag(StorageTags.NEW_KEY_BUTTON).assertDoesNotExist()
        composeRule.onNodeWithTag(StorageTags.RETRY_BUTTON).performClick()

        assertEquals(1, retries)
    }

    @Test
    fun unreadableBannerPointsToABackupAndOffersNoKeyAction() {
        val broken = AccountItem.UnavailableItem(id = "u1", issuer = "Example", accountName = "carol")
        show(listWith(StorageNotice(StorageNoticeKind.UNREADABLE, unreadableCount = 1), totp, broken))

        composeRule.onNodeWithText(plural(R.plurals.storage_unreadable_title, 1, 1)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.storage_unreadable_body)).assertIsDisplayed()
        composeRule.onNodeWithTag(StorageTags.NEW_KEY_BUTTON).assertDoesNotExist()
        composeRule.onNodeWithTag(StorageTags.RETRY_BUTTON).assertDoesNotExist()
        composeRule.onNodeWithTag(AccountListTags.row(broken.id)).assertIsDisplayed()
    }

    @Test
    fun aRowThatFailedOnlyThisTimeSaysSo() {
        val busy = AccountItem.UnavailableItem(id = "u2", issuer = "Example", accountName = "dave", temporary = true)
        show(listWith(StorageNotice(StorageNoticeKind.TEMPORARY, unreadableCount = 0), busy))

        composeRule.onNodeWithText(string(R.string.account_temporarily_unavailable_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.account_unavailable_title)).assertDoesNotExist()
    }
}
