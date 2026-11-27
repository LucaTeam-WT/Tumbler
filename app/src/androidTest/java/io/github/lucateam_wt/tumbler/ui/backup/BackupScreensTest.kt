package io.github.lucateam_wt.tumbler.ui.backup

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.lucateam_wt.tumbler.R
import io.github.lucateam_wt.tumbler.backup.ConflictDecision
import io.github.lucateam_wt.tumbler.backup.ConflictDecision.KEEP_BOTH
import io.github.lucateam_wt.tumbler.backup.ConflictDecision.REPLACE
import io.github.lucateam_wt.tumbler.backup.ConflictDecision.SKIP
import io.github.lucateam_wt.tumbler.backup.ConflictReason
import io.github.lucateam_wt.tumbler.ui.accounts.AccountItem
import io.github.lucateam_wt.tumbler.ui.accounts.AccountListScreen
import io.github.lucateam_wt.tumbler.ui.accounts.AccountListUiState
import io.github.lucateam_wt.tumbler.ui.navigation.DrawerTags
import io.github.lucateam_wt.tumbler.ui.theme.TumblerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** The backup menu, dialogs and review screen, at the default font scale and at 130 %. */
class BackupScreensTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)

    private fun plural(id: Int, count: Int) = composeRule.activity.resources.getQuantityString(id, count, count)

    private val password = "correct horse battery staple"

    private fun show(fontScale: Float = 1f, content: @Composable () -> Unit) {
        composeRule.setContent {
            TumblerTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { content() }
            }
        }
    }

    private fun inDialog(text: String) = composeRule.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    private fun tag(tag: String) = composeRule.onNodeWithTag(tag, useUnmergedTree = true)

    private fun hasDescription(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(text))

    // ---- the menu ----

    private fun showList(state: AccountListUiState, onExport: () -> Unit, onImport: () -> Unit, fontScale: Float = 1f) = show(fontScale) {
        AccountListScreen(
            state = state,
            onAddClick = {},
            onCopyCode = {},
            onNextHotpCode = {},
            onDelete = {},
            onActionFailureShown = {},
            onExportBackup = onExport,
            onImportBackup = onImport,
        )
    }

    private val oneAccount = AccountListUiState(
        isLoading = false,
        accounts = listOf(AccountItem.HotpItem(id = "h1", issuer = "Example", accountName = "bob", code = "755224")),
    )

    private fun assertMenuOffersExportAndImport(fontScale: Float) {
        var exports = 0
        var imports = 0
        showList(oneAccount, onExport = { exports++ }, onImport = { imports++ }, fontScale = fontScale)

        composeRule.onNodeWithTag(DrawerTags.OPEN_BUTTON)
            .assert(hasDescription(string(R.string.drawer_open)))
            .assertHasClickAction()
            .performClick()
        composeRule.onNodeWithTag(DrawerTags.EXPORT).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.backup_export_menu)).assertIsDisplayed().performClick()
        assertEquals(1, exports)

        composeRule.onNodeWithTag(DrawerTags.OPEN_BUTTON).performClick()
        composeRule.onNodeWithText(string(R.string.backup_import_menu)).assertIsDisplayed().performClick()
        assertEquals(1, imports)
    }

    @Test
    fun menuOffersExportAndImport() = assertMenuOffersExportAndImport(1f)

    @Test
    fun menuOffersExportAndImportAtFontScale13() = assertMenuOffersExportAndImport(1.3f)

    @Test
    fun exportWithNoAccountsShowsAMessageInsteadOfTheDialog() {
        var exports = 0
        var imports = 0
        showList(AccountListUiState(isLoading = false), onExport = { exports++ }, onImport = { imports++ })

        composeRule.onNodeWithTag(DrawerTags.OPEN_BUTTON).performClick()
        composeRule.onNodeWithTag(DrawerTags.EXPORT).performClick()

        composeRule.onNodeWithText(string(R.string.backup_export_no_accounts)).assertIsDisplayed()
        assertEquals(0, exports)
        // Importing into an empty list is the normal way to restore a backup.
        composeRule.onNodeWithTag(DrawerTags.OPEN_BUTTON).performClick()
        composeRule.onNodeWithTag(DrawerTags.IMPORT).performClick()
        assertEquals(1, imports)
    }

    // ---- the export password dialog ----

    private fun assertExportDialogValidatesLive(fontScale: Float) {
        val first = PasswordInput()
        val second = PasswordInput()
        var submits = 0
        show(fontScale) { ExportPasswordDialog(first, second, onSubmit = { submits++ }, onCancel = {}) }
        val submit = tag(BackupTags.EXPORT_SUBMIT)

        inDialog(plural(R.plurals.backup_password_hint, 12)).assertIsDisplayed()
        submit.assertIsNotEnabled()

        tag(BackupTags.EXPORT_PASSWORD).performTextInput("short")
        submit.assertIsNotEnabled()
        tag(BackupTags.EXPORT_PASSWORD).performTextInput(" but now long enough")
        tag(BackupTags.EXPORT_CONFIRMATION).performTextInput("short but now")
        // A prefix of the password: still typing, so no error yet.
        composeRule.onNodeWithText(string(R.string.backup_password_mismatch)).assertDoesNotExist()
        submit.assertIsNotEnabled()

        tag(BackupTags.EXPORT_CONFIRMATION).performTextInput(" wrong")
        tag(BackupTags.EXPORT_CONFIRMATION).performScrollTo()
        inDialog(string(R.string.backup_password_mismatch)).assertIsDisplayed()
        tag(BackupTags.EXPORT_CONFIRMATION).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Error, string(R.string.backup_password_mismatch)),
        )
        submit.assertIsNotEnabled()

        second.clear()
        tag(BackupTags.EXPORT_CONFIRMATION).performTextInput("short but now long enough")
        composeRule.onNodeWithText(string(R.string.backup_password_mismatch)).assertDoesNotExist()
        submit.assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(1, submits)
    }

    @Test
    fun exportDialogValidatesLive() = assertExportDialogValidatesLive(1f)

    @Test
    fun exportDialogValidatesLiveAtFontScale13() = assertExportDialogValidatesLive(1.3f)

    @Test
    fun exportDialogSaysWhenThePasswordIsTooLong() {
        val first = PasswordInput()
        show { ExportPasswordDialog(first, PasswordInput(), onSubmit = {}, onCancel = {}) }

        tag(BackupTags.EXPORT_PASSWORD).performTextInput("a".repeat(1025))

        inDialog(plural(R.plurals.backup_password_too_long, 1024)).assertIsDisplayed()
        tag(BackupTags.EXPORT_SUBMIT).assertIsNotEnabled()
    }

    // A text field keeps what was typed, unmasked, in its EditableText (and InputText)
    // semantics, whether it is drawn masked or not, and marks itself with Password.
    // `onNodeWithText` matches EditableText too, so it always finds a filled-in field. What
    // is checked instead: the Password property, what the field draws, the toggles' names, and
    // that no node carries the text as a label. Whether the platform hands EditableText to
    // accessibility services is not checked here (see SECURITY.md).

    /** A node whose Text (its visible label) or content description holds [secret]: something TalkBack reads out. */
    private fun labelHolds(secret: String) = SemanticsMatcher("Text or ContentDescription holds the password") { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { secret in it.text } ||
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { secret in it }
    }

    private fun editableTextHolds(secret: String) = SemanticsMatcher("EditableText holds the password") { node ->
        node.config.getOrNull(SemanticsProperties.EditableText)?.text?.contains(secret) == true
    }

    /** In both trees: no label holds [secret], and only password fields hold it as editable text. */
    private fun assertNoLabelHolds(secret: String) {
        for (unmerged in listOf(false, true)) {
            composeRule.onAllNodes(labelHolds(secret), useUnmergedTree = unmerged).assertCountEquals(0)
            composeRule.onAllNodes(
                editableTextHolds(secret) and !SemanticsMatcher.keyIsDefined(SemanticsProperties.Password),
                useUnmergedTree = unmerged,
            ).assertCountEquals(0)
        }
    }

    /** The text the field lays out and draws, after its mask; compared, never printed. */
    private fun SemanticsNodeInteraction.drawnText(): String {
        val layouts = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().layoutInput.text.text
    }

    /** The field draws one mask character per character typed, and none of the password's. */
    private fun SemanticsNodeInteraction.assertMasked(): SemanticsNodeInteraction {
        val drawn = drawnText()
        assertEquals("masked length", password.length, drawn.length)
        assertTrue("the field draws a character of the password", drawn.none { it in password })
        return this
    }

    private fun toggle(description: Int) = composeRule.onNode(hasContentDescription(string(description)) and hasClickAction())

    /**
     * [field] is hidden by default, behind a toggle named [showDescription], which shows it
     * and then offers [hideDescription]. While shown, the field stays a password field for
     * accessibility: `BasicSecureTextField` sets Password in every obfuscation mode.
     */
    private fun assertHiddenWithANamedToggle(field: SemanticsNodeInteraction, showDescription: Int, hideDescription: Int) {
        field.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).assertMasked()
        assertNoLabelHolds(password)

        toggle(showDescription).assertIsDisplayed().performClick()

        toggle(hideDescription).assertIsDisplayed()
        assertTrue("the shown field draws the password as typed", field.drawnText() == password)
        field.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        assertNoLabelHolds(password)

        toggle(hideDescription).performClick()

        toggle(showDescription).assertIsDisplayed()
        field.assertMasked()
    }

    @Test
    fun passwordFieldsAreHiddenAndHaveNamedVisibilityToggles() {
        show { ExportPasswordDialog(PasswordInput(), PasswordInput(), onSubmit = {}, onCancel = {}) }
        val first = tag(BackupTags.EXPORT_PASSWORD)
        val second = tag(BackupTags.EXPORT_CONFIRMATION)
        first.performTextInput(password)
        second.performTextInput(password)

        second.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).assertMasked()
        assertHiddenWithANamedToggle(first, R.string.backup_password_show, R.string.backup_password_hide)
        // Each field has its own toggle: showing the first left the second hidden.
        second.assertMasked()
        assertHiddenWithANamedToggle(second, R.string.backup_password_confirm_show, R.string.backup_password_confirm_hide)
        first.assertMasked()
    }

    @Test
    fun importPasswordFieldIsHiddenAndHasANamedVisibilityToggle() {
        show { ImportPasswordDialog(PasswordInput(), wrongPasswordOrDamaged = true, onSubmit = {}, onCancel = {}) }
        val field = tag(BackupTags.IMPORT_PASSWORD)
        field.performTextInput(password)

        assertHiddenWithANamedToggle(field, R.string.backup_password_show, R.string.backup_password_hide)
    }

    /**
     * Each password field opts out of Compose's autofill: its node carries ContentDataType
     * None (set outside the text field, so it must win over the field's own Text), and
     * Compose then reports neither focus nor value changes to the autofill framework.
     * Whether a password manager then stays quiet is not checked: no autofill service runs here.
     */
    @Test
    fun passwordFieldsAreNotAutofillable() {
        var importDialog by mutableStateOf(false)
        show {
            if (importDialog) {
                ImportPasswordDialog(PasswordInput(), wrongPasswordOrDamaged = true, onSubmit = {}, onCancel = {})
            } else {
                ExportPasswordDialog(PasswordInput(), PasswordInput(), onSubmit = {}, onCancel = {})
            }
        }
        val fieldsByDialog = listOf(
            false to listOf(BackupTags.EXPORT_PASSWORD, BackupTags.EXPORT_CONFIRMATION),
            true to listOf(BackupTags.IMPORT_PASSWORD),
        )
        for ((import, fields) in fieldsByDialog) {
            importDialog = import
            composeRule.waitForIdle()
            for (field in fields) {
                tag(field).performTextInput(password)
                tag(field)
                    .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDataType, ContentDataType.None))
            }
        }
    }

    @Test
    fun exportDialogCancelIsAvailable() {
        var cancels = 0
        show { ExportPasswordDialog(PasswordInput(), PasswordInput(), onSubmit = {}, onCancel = { cancels++ }) }

        inDialog(string(R.string.add_cancel)).performClick()

        assertEquals(1, cancels)
    }

    /**
     * Records what the dialog's composition saves for a configuration change or process death:
     * nothing that holds the password may be in there.
     */
    @Test
    fun passwordDialogsSaveNothingThatHoldsThePassword() {
        val registry = SaveableStateRegistry(restoredValues = null, canBeSaved = { true })
        val first = PasswordInput()
        val second = PasswordInput()
        val import = PasswordInput()
        show {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                ExportPasswordDialog(first, second, onSubmit = {}, onCancel = {})
                ImportPasswordDialog(import, wrongPasswordOrDamaged = false, onSubmit = {}, onCancel = {})
            }
        }
        composeRule.onNode(hasTestTag(BackupTags.EXPORT_PASSWORD)).performTextInput(password)
        composeRule.onNode(hasTestTag(BackupTags.EXPORT_CONFIRMATION)).performTextInput(password)
        composeRule.onNode(hasTestTag(BackupTags.IMPORT_PASSWORD)).performTextInput(password)
        composeRule.waitForIdle()

        val saved = composeRule.runOnIdle { registry.performSave() }

        val text = saved.values.flatten().joinToString { it.toString() }
        assertFalse(text, text.contains(password))
        assertFalse(text, text.contains("horse"))
        assertTrue(first.state.text.toString() == password) // the input really held it
    }

    // ---- the import password dialog ----

    private fun assertImportDialogShowsTheSharedError(fontScale: Float) {
        val input = PasswordInput()
        var submits = 0
        show(fontScale) { ImportPasswordDialog(input, wrongPasswordOrDamaged = true, onSubmit = { submits++ }, onCancel = {}) }

        inDialog(string(R.string.backup_import_wrong_password)).assertIsDisplayed()
        tag(BackupTags.IMPORT_PASSWORD).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Error, string(R.string.backup_import_wrong_password)),
        )
        tag(BackupTags.IMPORT_SUBMIT).assertIsNotEnabled()

        tag(BackupTags.IMPORT_PASSWORD).performTextInput(password)
        tag(BackupTags.IMPORT_SUBMIT).assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(1, submits)
    }

    @Test
    fun importDialogShowsTheSharedErrorForWrongPasswordOrDamagedFile() = assertImportDialogShowsTheSharedError(1f)

    @Test
    fun importDialogShowsTheSharedErrorAtFontScale13() = assertImportDialogShowsTheSharedError(1.3f)

    @Test
    fun importDialogHasNoErrorBeforeTheFirstAttempt() {
        show { ImportPasswordDialog(PasswordInput(), wrongPasswordOrDamaged = false, onSubmit = {}, onCancel = {}) }

        composeRule.onNodeWithText(string(R.string.backup_import_wrong_password)).assertDoesNotExist()
        inDialog(string(R.string.backup_import_password_body)).assertIsDisplayed()
    }

    // ---- progress ----

    @Test
    fun progressDialogOffersCancelOnlyWhereItIsSafe() {
        var cancels = 0
        var reading by mutableStateOf(true)
        show {
            if (reading) {
                BackupProgressDialog(string(R.string.backup_reading), onCancel = { cancels++ })
            } else {
                BackupProgressDialog(string(R.string.backup_importing))
            }
        }

        inDialog(string(R.string.backup_reading)).assertIsDisplayed()
        inDialog(string(R.string.add_cancel)).performClick()
        assertEquals(1, cancels)

        reading = false
        composeRule.waitForIdle()
        inDialog(string(R.string.backup_importing)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.add_cancel)).assertDoesNotExist()
    }

    // ---- the review ----

    private val labelConflict = ConflictRow(
        index = 0,
        imported = AccountLabel("GitHub", "alice"),
        existing = AccountLabel("GitHub", "alice"),
        reason = ConflictReason.SAME_LABEL,
        sharesCounterpart = false,
        choices = listOf(SKIP, KEEP_BOTH, REPLACE),
        decision = SKIP,
        replaceTaken = false,
        counters = null,
    )

    private val hotpConflict = ConflictRow(
        index = 1,
        imported = AccountLabel("Bank", "bob"),
        existing = AccountLabel("Bank", "bob"),
        reason = ConflictReason.SAME_LABEL_AND_SECRET,
        sharesCounterpart = false,
        choices = listOf(SKIP, REPLACE),
        decision = SKIP,
        replaceTaken = false,
        counters = HotpCounters(stored = 3, imported = 9, replaceKeepsLarger = true),
    )

    private val takenConflict = ConflictRow(
        index = 3,
        imported = AccountLabel("Other", "x"),
        existing = AccountLabel("GitHub", "alice"),
        reason = ConflictReason.SAME_SECRET,
        sharesCounterpart = true,
        choices = listOf(SKIP, KEEP_BOTH, REPLACE),
        decision = SKIP,
        replaceTaken = true,
        counters = null,
    )

    private val review = PlanReview(newAccounts = 2, identical = 1, conflicts = listOf(labelConflict, hotpConflict, takenConflict))

    /** The choices (radio rows) inside the card of conflict [index]. */
    private fun choicesOf(index: Int) = composeRule.onAllNodes(
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton) and
            hasAnyAncestor(hasTestTag(BackupTags.conflict(index))),
    )

    /** A node with [text] inside the card of conflict [index]. */
    private fun inConflict(index: Int, text: String) =
        composeRule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(BackupTags.conflict(index))))

    /**
     * Brings the [decision] row of conflict [index] into view and checks that it is not behind
     * the bottom bar. The list fills the screen and runs under the bar, and `performScrollTo`
     * or `performScrollToNode` only keep a node inside the list's bounds, so they can leave a
     * row behind the bar, where a tap lands on the bar instead. This scrolls the card to the
     * top of the list (as far as the list goes), then on by the rest if the row is still
     * covered, as a user would.
     */
    private fun choiceInView(index: Int, decision: ConflictDecision): SemanticsNodeInteraction {
        val list = composeRule.onNode(hasScrollToKeyAction())
        list.performScrollToKey(index)
        val row = tag(BackupTags.choice(index, decision))
        val barTop = tag(BackupTags.REVIEW_BAR).getUnclippedBoundsInRoot().top
        val covered = row.getUnclippedBoundsInRoot().bottom - barTop
        if (covered > 0.dp) {
            val pixels = with(composeRule.density) { covered.toPx() }
            list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, pixels) }
        }
        assertTrue("choice $index $decision is behind the bottom bar", row.getUnclippedBoundsInRoot().bottom <= barTop)
        return row
    }

    private fun assertReviewShowsCountsAndOnlyTheOfferedChoices(fontScale: Float) {
        val decisions = mutableListOf<Pair<Int, ConflictDecision>>()
        var shown by mutableStateOf(review)
        var continues = 0
        show(fontScale) {
            ImportReviewScreen(
                review = shown,
                notice = null,
                // Records every tap and shows it as the view model would, so each card's selection can be checked.
                onDecision = { index, decision ->
                    decisions += index to decision
                    shown = shown.copy(conflicts = shown.conflicts.map { if (it.index == index) it.copy(decision = decision) else it })
                },
                onContinue = { continues++ },
                onCancel = {},
            )
        }

        composeRule.onNodeWithText(string(R.string.backup_review_new)).assertIsDisplayed()
        composeRule.onNode(hasText(string(R.string.backup_review_conflicts)) and hasText("3")).assertIsDisplayed()
        composeRule.onNode(hasText(string(R.string.backup_review_identical)) and hasText("1")).assertIsDisplayed()

        // Conflict 0, same label: all three choices, Skip selected, Replace not.
        val keepBoth0 = choiceInView(0, KEEP_BOTH)
        choicesOf(0).assertCountEquals(3)
        tag(BackupTags.choice(0, SKIP)).assertIsSelected()
        tag(BackupTags.choice(0, REPLACE)).assertIsNotSelected()
        keepBoth0.performClick()
        assertEquals(listOf(0 to KEEP_BOTH), decisions)
        keepBoth0.assertIsSelected()
        tag(BackupTags.choice(0, SKIP)).assertIsNotSelected()

        // Conflict 1, same HOTP secret: Skip and Replace only, with both counters.
        val replace1 = choiceInView(1, REPLACE)
        choicesOf(1).assertCountEquals(2)
        tag(BackupTags.choice(1, KEEP_BOTH)).assertDoesNotExist()
        tag(BackupTags.choice(1, SKIP)).assertIsSelected()
        replace1.assertIsNotSelected()
        inConflict(1, string(R.string.backup_decision_replace_desc_larger, 3, 9)).assertIsDisplayed()
        replace1.performClick()
        assertEquals(listOf(0 to KEEP_BOTH, 1 to REPLACE), decisions)
        replace1.assertIsSelected()
        tag(BackupTags.choice(1, SKIP)).assertIsNotSelected()

        // Conflict 3 (index 2 is identical, so not shown): its device account is already being
        // replaced by another conflict, so Replace is disabled and says why.
        val replace3 = choiceInView(3, REPLACE)
        choicesOf(3).assertCountEquals(3)
        replace3.assertIsNotEnabled()
        tag(BackupTags.choice(3, SKIP)).assertIsSelected()
        inConflict(3, string(R.string.backup_decision_replace_taken)).assertIsDisplayed()
        inConflict(3, string(R.string.backup_review_shared)).assertIsDisplayed()
        replace3.performClick()
        assertEquals("a disabled choice records nothing", 2, decisions.size)

        // Back at the top, conflict 0 still shows its own choice.
        choiceInView(0, KEEP_BOTH).assertIsSelected()
        tag(BackupTags.choice(0, REPLACE)).assertIsNotSelected()

        tag(BackupTags.REVIEW_CANCEL).assertIsDisplayed()
        tag(BackupTags.REVIEW_CONTINUE).assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(1, continues)
    }

    @Test
    fun reviewShowsCountsAndOnlyTheOfferedChoices() = assertReviewShowsCountsAndOnlyTheOfferedChoices(1f)

    @Test
    fun reviewShowsCountsAndOnlyTheOfferedChoicesAtFontScale13() = assertReviewShowsCountsAndOnlyTheOfferedChoices(1.3f)

    @Test
    fun reviewCanBeCancelledFromTheBarAndTheCloseButton() {
        var cancels = 0
        show { ImportReviewScreen(review, notice = null, onDecision = { _, _ -> }, onContinue = {}, onCancel = { cancels++ }) }

        tag(BackupTags.REVIEW_CANCEL).performClick()
        composeRule.onNode(hasContentDescription(string(R.string.backup_review_close)) and hasClickAction()).performClick()

        assertEquals(2, cancels)
    }

    @Test
    fun reviewWithNothingToImportCannotContinue() {
        show {
            ImportReviewScreen(
                PlanReview(newAccounts = 0, identical = 2, conflicts = listOf(labelConflict)),
                notice = null,
                onDecision = { _, _ -> },
                onContinue = {},
                onCancel = {},
            )
        }

        composeRule.onNodeWithText(string(R.string.backup_review_nothing)).assertIsDisplayed()
        tag(BackupTags.REVIEW_CONTINUE).assertIsNotEnabled()
    }

    @Test
    fun reviewShowsWhyItCameBack() {
        show { ImportReviewScreen(review, ReviewNotice.PLAN_CHANGED, onDecision = { _, _ -> }, onContinue = {}, onCancel = {}) }

        composeRule.onNodeWithText(string(R.string.backup_notice_plan_changed)).assertIsDisplayed()
    }

    private fun noticeText(notice: ReviewNotice) = string(
        when (notice) {
            ReviewNotice.PLAN_CHANGED -> R.string.backup_notice_plan_changed
            ReviewNotice.DECISIONS_REJECTED -> R.string.backup_notice_rejected
            ReviewNotice.STORAGE_FAILED -> R.string.backup_notice_storage
            ReviewNotice.NOT_ENOUGH_MEMORY -> R.string.backup_notice_memory
        },
    )

    /**
     * The notice comes back with the review after PlanChanged, StorageFailed or
     * NotEnoughMemory, while the user may have scrolled far down: it must be on screen, between
     * the two bars, without scrolling back.
     */
    @Test
    fun reviewNoticeIsOnScreenAfterTheListWasScrolled() {
        val many = PlanReview(
            newAccounts = 1,
            identical = 0,
            conflicts = List(15) { i ->
                labelConflict.copy(index = i, imported = AccountLabel("Issuer $i", "name $i"), existing = AccountLabel("Issuer $i", "name $i"))
            },
        )
        var notice by mutableStateOf<ReviewNotice?>(null)
        show { ImportReviewScreen(many, notice, onDecision = { _, _ -> }, onContinue = {}, onCancel = {}) }

        composeRule.onNode(hasScrollToKeyAction()).performScrollToKey(14)
        // The top of the list (the summary) is out of view.
        composeRule.onNodeWithText(string(R.string.backup_review_new)).assertIsNotDisplayed()

        for (shown in ReviewNotice.entries) {
            notice = shown
            composeRule.waitForIdle()

            val card = tag(BackupTags.REVIEW_NOTICE).assertIsDisplayed()
            composeRule.onNodeWithText(noticeText(shown)).assertIsDisplayed()
            val bounds = card.getUnclippedBoundsInRoot()
            assertTrue("$shown is under the top bar", bounds.top >= tag(BackupTags.REVIEW_TOP_BAR).getUnclippedBoundsInRoot().bottom)
            assertTrue("$shown is behind the bottom bar", bounds.bottom <= tag(BackupTags.REVIEW_BAR).getUnclippedBoundsInRoot().top)

            notice = null
            composeRule.waitForIdle()
            tag(BackupTags.REVIEW_NOTICE).assertDoesNotExist()
        }
    }

    @Test
    fun reviewKeepsVeryLongLabelsBoundedAtFontScale13() {
        val long = AccountLabel("Issuer ".repeat(60).trim(), "name ".repeat(80).trim())
        show(1.3f) {
            ImportReviewScreen(
                PlanReview(1, 0, listOf(labelConflict.copy(imported = long, existing = long))),
                notice = null,
                onDecision = { _, _ -> },
                onContinue = {},
                onCancel = {},
            )
        }

        // The last choice can still be brought clear of the bottom bar.
        choiceInView(0, REPLACE).assertIsDisplayed()
        tag(BackupTags.REVIEW_CONTINUE).assertIsDisplayed()
    }

    // ---- the final confirmation and the messages ----

    @Test
    fun confirmDialogShowsTheTotalsBeforeWriting() {
        var confirms = 0
        var backs = 0
        val chosen = review.copy(conflicts = listOf(labelConflict.copy(decision = KEEP_BOTH), hotpConflict.copy(decision = REPLACE), takenConflict))
        show { ImportConfirmDialog(chosen, onConfirm = { confirms++ }, onBack = { backs++ }) }

        inDialog(string(R.string.backup_total_add, 3)).assertIsDisplayed()
        inDialog(string(R.string.backup_total_replace, 1)).assertIsDisplayed()
        inDialog(string(R.string.backup_total_skip, 2)).assertIsDisplayed()
        // Replace overwrites what the account holds now, and the dialog says so.
        inDialog(plural(R.plurals.backup_confirm_replace_warning, 1)).performScrollTo().assertIsDisplayed()
        tag(BackupTags.CONFIRM_BACK).performClick()
        tag(BackupTags.CONFIRM_IMPORT).performClick()

        assertEquals(1, backs)
        assertEquals(1, confirms)
    }

    @Test
    fun confirmDialogWarnsAboutReplaceOnlyWhenSomethingIsReplaced() {
        var chosen by mutableStateOf(review)
        show { ImportConfirmDialog(chosen, onConfirm = {}, onBack = {}) }

        inDialog(string(R.string.backup_total_replace, 0)).assertIsDisplayed()
        composeRule.onNodeWithText(plural(R.plurals.backup_confirm_replace_warning, 0)).assertDoesNotExist()
        composeRule.onNodeWithText(plural(R.plurals.backup_confirm_replace_warning, 1)).assertDoesNotExist()

        chosen = review.copy(conflicts = listOf(labelConflict.copy(decision = REPLACE), hotpConflict.copy(decision = REPLACE), takenConflict))
        composeRule.waitForIdle()

        inDialog(plural(R.plurals.backup_confirm_replace_warning, 2)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun exportedMessageWarnsThatThePasswordCannotBeRecovered() {
        show(1.3f) { BackupMessageDialog(BackupMessage.Exported(3), onDismiss = {}) }

        inDialog(string(R.string.backup_export_done_title)).assertIsDisplayed()
        // The dialog shows the warning in the device's language...
        composeRule.onNode(hasText(string(R.string.backup_export_password_warning), substring = true) and hasAnyAncestor(isDialog()))
            .assertExists()
        tag(BackupTags.MESSAGE_OK).assertIsDisplayed()
        // ...and the warning still says that the password cannot be recovered, in both languages.
        val required = mapOf(
            "en" to "The password is not stored anywhere and cannot be recovered.",
            "vi" to "Mật khẩu không được lưu ở bất kỳ đâu và không thể khôi phục.",
        )
        for ((language, sentence) in required) {
            val config = Configuration(composeRule.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
            val warning = composeRule.activity.createConfigurationContext(config).getString(R.string.backup_export_password_warning)
            assertTrue("$language warning", warning.contains(sentence))
        }
    }

    @Test
    fun failedExportSaysWhatHappenedToTheFile() {
        show {
            BackupMessageDialog(BackupMessage.ExportFailed(ExportFailure.WRITE_FAILED, PartialFile.DELETED), onDismiss = {})
        }

        composeRule.onNode(hasText(string(R.string.backup_export_error_write), substring = true)).assertIsDisplayed()
        composeRule.onNode(hasText(string(R.string.backup_export_partial_deleted), substring = true)).assertIsDisplayed()
    }

    @Test
    fun importThatMayHaveBeenAppliedSaysToCheckTheList() {
        var dismissed = 0
        show { BackupMessageDialog(BackupMessage.ImportMayHaveBeenApplied, onDismiss = { dismissed++ }) }

        inDialog(string(R.string.backup_import_maybe_body)).assertIsDisplayed()
        tag(BackupTags.MESSAGE_OK).performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun importedMessageShowsTheCounts() {
        show { BackupMessageDialog(BackupMessage.Imported(added = 2, replaced = 1, skipped = 4), onDismiss = {}) }

        inDialog(string(R.string.backup_import_done_body, 2, 1, 4)).assertIsDisplayed()
    }
}
