package io.github.lucateam_wt.tumbler.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.Density
import io.github.lucateam_wt.tumbler.R
import io.github.lucateam_wt.tumbler.ui.assertTextsFit
import io.github.lucateam_wt.tumbler.ui.accounts.AccountItem
import io.github.lucateam_wt.tumbler.ui.accounts.AccountListScreen
import io.github.lucateam_wt.tumbler.ui.accounts.AccountListUiState
import io.github.lucateam_wt.tumbler.ui.accounts.MigrationProgress
import io.github.lucateam_wt.tumbler.ui.accounts.StorageNotice
import io.github.lucateam_wt.tumbler.ui.accounts.StorageNoticeKind
import io.github.lucateam_wt.tumbler.ui.accounts.StorageTags
import io.github.lucateam_wt.tumbler.ui.theme.TumblerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The navigation drawer of the accounts screen: the menu button, opening and closing by tap,
 * swipe and Back, the entries' roles and selected state, the storage states, and font scale 1.3.
 */
class NavigationDrawerTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int) = composeRule.activity.getString(id)

    private val totp = AccountItem.TotpItem(
        id = "t1",
        issuer = "Example",
        accountName = "alice",
        code = "123456",
        secondsRemaining = 12,
        period = 30,
    )

    private val loaded = AccountListUiState(isLoading = false, accounts = listOf(totp))

    private var exports = 0
    private var imports = 0
    private var settings = 0
    private var abouts = 0

    /** The state the screen shows; tests may change it after [show]. */
    private var state by mutableStateOf(loaded)

    private fun show(initial: AccountListUiState = loaded, fontScale: Float = 1f) {
        state = initial
        composeRule.setContent {
            TumblerTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    AccountListScreen(
                        state = state,
                        onAddClick = {},
                        onCopyCode = {},
                        onNextHotpCode = {},
                        onDelete = {},
                        onActionFailureShown = {},
                        onExportBackup = { exports++ },
                        onImportBackup = { imports++ },
                        onOpenSettings = { settings++ },
                        onOpenAbout = { abouts++ },
                    )
                }
            }
        }
    }

    private fun tag(tag: String) = composeRule.onNodeWithTag(tag)

    private fun openButton() = tag(DrawerTags.OPEN_BUTTON)

    private fun assertDrawerOpen() {
        tag(DrawerTags.SHEET).assertIsDisplayed()
        tag(DrawerTags.ACCOUNTS).assertIsDisplayed()
    }

    /** The drawer is composed but off screen: the node exists, so "not displayed" means closed. */
    private fun assertDrawerClosed() {
        tag(DrawerTags.SHEET).assertExists().assertIsNotDisplayed()
        tag(DrawerTags.ACCOUNTS).assertExists().assertIsNotDisplayed()
    }

    private fun swipeFromStartEdge() = composeRule.onRoot().performTouchInput { swipeRight(startX = left + 1f, endX = right - 1f) }

    @Test
    fun menuButtonIsNamedAndOpensTheDrawer() {
        show()
        assertDrawerClosed()

        openButton()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(string(R.string.drawer_open))))
            .assertHasClickAction()
            .performClick()

        assertDrawerOpen()
        composeRule.onNodeWithText(string(R.string.app_name))
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        composeRule.onNodeWithText(string(R.string.drawer_backup))
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        for (entry in listOf(DrawerTags.EXPORT, DrawerTags.IMPORT, DrawerTags.SETTINGS, DrawerTags.ABOUT)) {
            tag(entry).assertIsDisplayed()
        }
    }

    @Test
    fun entriesExposeTheirRoleLabelAndSelectedState() {
        show()
        openButton().performClick()

        val entries = mapOf(
            DrawerTags.ACCOUNTS to R.string.accounts_title,
            DrawerTags.EXPORT to R.string.backup_export_menu,
            DrawerTags.IMPORT to R.string.backup_import_menu,
            DrawerTags.SETTINGS to R.string.settings_title,
            DrawerTags.ABOUT to R.string.about_title,
        )
        for ((entry, label) in entries) {
            tag(entry)
                .assertHasClickAction()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
                .assert(hasText(string(label)))
        }
        // Accounts is the screen behind the drawer.
        tag(DrawerTags.ACCOUNTS).assertIsSelected()
        for (entry in entries.keys - DrawerTags.ACCOUNTS) tag(entry).assertIsNotSelected()
    }

    @Test
    fun tappingAccountsClosesTheDrawer() {
        show()
        openButton().performClick()

        tag(DrawerTags.ACCOUNTS).performClick()

        assertDrawerClosed()
        openButton().assertIsDisplayed()
    }

    @Test
    fun backClosesTheDrawerFirst() {
        show()
        openButton().performClick()
        assertDrawerOpen()

        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }

        assertDrawerClosed()
        assertFalse("Back left the screen instead of closing the drawer", composeRule.activity.isFinishing)
        openButton().assertIsDisplayed()
    }

    @Test
    fun backDoesNothingToTheDrawerWhileItIsClosed() {
        show()

        // No enabled callback of the drawer: a closed drawer must not swallow Back.
        assertFalse(composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.hasEnabledCallbacks() })
    }

    @Test
    fun swipingFromTheStartEdgeOpensAndSwipingBackClosesIt() {
        show()

        swipeFromStartEdge()
        assertDrawerOpen()

        composeRule.onRoot().performTouchInput { swipeLeft(startX = right - 1f, endX = left + 1f) }
        assertDrawerClosed()
    }

    @Test
    fun settingsAndAboutCloseTheDrawerAndOpenTheirScreen() {
        show()

        openButton().performClick()
        tag(DrawerTags.SETTINGS).performClick()
        assertEquals(1, settings)
        assertDrawerClosed()

        openButton().performClick()
        tag(DrawerTags.ABOUT).performClick()
        assertEquals(1, abouts)
        assertDrawerClosed()
        assertEquals(0, exports + imports)
    }

    @Test
    fun exportAndImportCloseTheDrawerAndStartTheirFlow() {
        show()

        openButton().performClick()
        tag(DrawerTags.EXPORT).performClick()
        assertEquals(1, exports)
        assertDrawerClosed()

        openButton().performClick()
        tag(DrawerTags.IMPORT).performClick()
        assertEquals(1, imports)
        assertDrawerClosed()
    }

    @Test
    fun duringTheMigrationThereIsNoMenuButtonAndNoSwipe() {
        show(AccountListUiState(isLoading = true, accounts = listOf(totp), migration = MigrationProgress(3, 8)))

        tag(StorageTags.MIGRATION_PROGRESS).assertIsDisplayed()
        openButton().assertDoesNotExist()

        swipeFromStartEdge()

        assertDrawerClosed()
        assertEquals(0, exports + imports + settings + abouts)
    }

    @Test
    fun aMigrationThatStartsWhileTheDrawerIsOpenClosesIt() {
        show()
        openButton().performClick()
        assertDrawerOpen()

        state = AccountListUiState(isLoading = true, migration = MigrationProgress(0, 2))

        assertDrawerClosed()
        openButton().assertDoesNotExist()

        state = loaded

        openButton().assertIsDisplayed()
        assertDrawerClosed()
    }

    @Test
    fun withANewKeyRequiredTheDrawerWorksButImportOnlySaysWhy() {
        show(loaded.copy(storageNotice = StorageNotice(StorageNoticeKind.NEW_KEY_REQUIRED, unreadableCount = 0)))
        tag(StorageTags.BANNER).assertIsDisplayed()

        openButton().performClick()
        tag(DrawerTags.IMPORT).performClick()
        assertEquals(0, imports)
        composeRule.onNodeWithText(string(R.string.backup_import_needs_new_key)).assertIsDisplayed()

        openButton().performClick()
        tag(DrawerTags.EXPORT).performClick()
        assertEquals(1, exports)

        openButton().performClick()
        tag(DrawerTags.SETTINGS).performClick()
        assertEquals(1, settings)
    }

    @Test
    fun nothingIsClippedAtFontScale13() {
        show(fontScale = 1.3f)
        openButton().performClick()

        val sheet = tag(DrawerTags.SHEET).getUnclippedBoundsInRoot()
        val header = composeRule.onNodeWithText(string(R.string.app_name), useUnmergedTree = true)
        val section = composeRule.onNodeWithText(string(R.string.drawer_backup), useUnmergedTree = true)
        val entries = mapOf(
            DrawerTags.ACCOUNTS to R.string.accounts_title,
            DrawerTags.EXPORT to R.string.backup_export_menu,
            DrawerTags.IMPORT to R.string.backup_import_menu,
            DrawerTags.SETTINGS to R.string.settings_title,
            DrawerTags.ABOUT to R.string.about_title,
        )
        // "Accounts" is also the top bar title: take each label inside its entry.
        val labels = entries.mapValues { (entry, label) ->
            composeRule.onNode(hasText(string(label)) and hasAnyAncestor(hasTestTag(entry)), useUnmergedTree = true)
        }
        assertTextsFit(header, section, *labels.values.toTypedArray())

        for (text in listOf(header, section) + labels.values) {
            assertTrue("text runs past the drawer", text.getUnclippedBoundsInRoot().right <= sheet.right)
        }
        for ((entry, text) in labels) {
            val item = tag(entry).getUnclippedBoundsInRoot()
            val bounds = text.getUnclippedBoundsInRoot()
            assertTrue("$entry label sticks out of its entry", bounds.top >= item.top && bounds.bottom <= item.bottom)
        }
    }
}
