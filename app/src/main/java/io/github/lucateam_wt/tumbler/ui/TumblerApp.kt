package io.github.lucateam_wt.tumbler.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lucateam_wt.tumbler.ui.accounts.AccountListScreen
import io.github.lucateam_wt.tumbler.ui.accounts.AccountListViewModel
import io.github.lucateam_wt.tumbler.ui.accounts.AddAccountScreen
import io.github.lucateam_wt.tumbler.ui.accounts.copyCodeToClipboard
import io.github.lucateam_wt.tumbler.ui.backup.BACKUP_MIME_TYPE
import io.github.lucateam_wt.tumbler.ui.backup.BackupDialogHost
import io.github.lucateam_wt.tumbler.ui.backup.BackupUiState
import io.github.lucateam_wt.tumbler.ui.backup.BackupViewModel
import io.github.lucateam_wt.tumbler.ui.backup.DocumentBackupFile
import io.github.lucateam_wt.tumbler.ui.backup.ImportReviewScreen
import io.github.lucateam_wt.tumbler.ui.backup.PlanReview
import io.github.lucateam_wt.tumbler.ui.backup.ReviewNotice
import io.github.lucateam_wt.tumbler.ui.backup.suggestedBackupFileName
import java.time.LocalDate

/** Duration of the slide and fade between the accounts list and the add screen. */
private const val SCREEN_TRANSITION_MILLIS = 300

/** The incoming and outgoing screens move by this fraction of the screen width. */
private const val SCREEN_SLIDE_FRACTION = 4

/**
 * Forward (to the add screen): the new screen enters from the end while the old one shifts a
 * little toward the start and fades. Back: the reverse. The direction follows the layout direction.
 */
internal fun screenTransition(forward: Boolean, layoutDirection: LayoutDirection): ContentTransform {
    val towardEnd = if (layoutDirection == LayoutDirection.Ltr) 1 else -1
    val enterFrom = if (forward) towardEnd else -towardEnd
    val spec = tween<Float>(SCREEN_TRANSITION_MILLIS, easing = FastOutSlowInEasing)
    val offsetSpec = tween<IntOffset>(SCREEN_TRANSITION_MILLIS, easing = FastOutSlowInEasing)
    val enter = slideInHorizontally(offsetSpec) { enterFrom * it / SCREEN_SLIDE_FRACTION } + fadeIn(spec)
    val exit = slideOutHorizontally(offsetSpec) { -enterFrom * it / SCREEN_SLIDE_FRACTION } + fadeOut(spec)
    return ContentTransform(enter, exit, sizeTransform = null)
}

/** The screens of the app, in the order the transitions go "forward". */
private enum class Screen { LIST, ADD, IMPORT_REVIEW }

/** What the root shows: the screen, and for the import review its content. */
private data class ShownScreen(val screen: Screen, val review: PlanReview? = null, val notice: ReviewNotice? = null)

/** The review stays on screen, under its dialogs, until the import ends. */
private fun BackupUiState.shownReview(): ShownScreen? = when (this) {
    is BackupUiState.Reviewing -> ShownScreen(Screen.IMPORT_REVIEW, review, notice)
    is BackupUiState.Confirming -> ShownScreen(Screen.IMPORT_REVIEW, review)
    is BackupUiState.Importing -> ShownScreen(Screen.IMPORT_REVIEW, review)
    BackupUiState.Idle,
    BackupUiState.ExportPassword,
    BackupUiState.ExportChoosingFile,
    BackupUiState.Exporting,
    BackupUiState.ImportChoosingFile,
    is BackupUiState.ImportPassword,
    BackupUiState.ImportReading,
    -> null
}

/**
 * Root of the UI: switches between the accounts list, the add screen and the import review,
 * and hosts the backup dialogs and the system file pickers. The backup flow lives in
 * [backupViewModel] and is never saved: after process death it starts again from the list.
 */
@Composable
internal fun TumblerApp(viewModel: AccountListViewModel, backupViewModel: BackupViewModel) {
    var showAdd by rememberSaveable { mutableStateOf(false) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val addState by viewModel.addState.collectAsStateWithLifecycle()
    val backupState by backupViewModel.state.collectAsStateWithLifecycle()
    val backupMessage by backupViewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resolver = context.applicationContext.contentResolver
    val layoutDirection = LocalLayoutDirection.current

    // Registered on every composition, so a result that arrives after the activity was
    // recreated still reaches the view model. The pickers need no storage permission.
    val createBackupFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE)) { uri ->
        backupViewModel.exportFileChosen(uri?.let { DocumentBackupFile(resolver, it) })
    }
    val openBackupFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        backupViewModel.importFileChosen(uri?.let { DocumentBackupFile(resolver, it) })
    }

    fun closeAdd() {
        showAdd = false
        viewModel.resetAddState()
    }

    LaunchedEffect(addState.saved) {
        if (addState.saved) closeAdd()
    }

    LaunchedEffect(backupViewModel, viewModel) {
        backupViewModel.reloadRequests.collect { viewModel.reload() }
    }

    val shown = backupState.shownReview() ?: ShownScreen(if (showAdd) Screen.ADD else Screen.LIST)

    BackHandler(enabled = shown.screen == Screen.ADD, onBack = ::closeAdd)
    // Back on the review cancels the import; while it is being written, Back does nothing.
    BackHandler(enabled = shown.screen == Screen.IMPORT_REVIEW, onBack = backupViewModel::cancelImport)

    // The screens are opaque, so the background shows through while they cross-fade.
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        AnimatedContent(
            targetState = shown,
            contentKey = { it.screen },
            transitionSpec = {
                screenTransition(forward = targetState.screen > initialState.screen, layoutDirection = layoutDirection)
            },
            label = "screen",
        ) { target ->
            // The screen that is leaving is only drawn: no semantics, so TalkBack and tests
            // never find two screens at once.
            val leaving = target.screen != shown.screen
            Box(
                Modifier
                    .fillMaxSize()
                    .then(if (leaving) Modifier.clearAndSetSemantics { } else Modifier),
            ) {
                when (target.screen) {
                    Screen.ADD -> AddAccountScreen(
                        error = addState.error,
                        saving = addState.saving,
                        onSave = viewModel::add,
                        onCancel = ::closeAdd,
                        onInputChange = viewModel::resetAddState,
                    )
                    Screen.LIST -> AccountListScreen(
                        state = state,
                        onAddClick = { showAdd = true },
                        onCopyCode = { copyCodeToClipboard(context, it) },
                        onNextHotpCode = viewModel::nextHotpCode,
                        onDelete = viewModel::delete,
                        onActionFailureShown = viewModel::actionFailureShown,
                        onExportBackup = backupViewModel::startExport,
                        onImportBackup = {
                            // A second tap while the picker opens gets false: one picker only.
                            if (backupViewModel.startImport()) {
                                try {
                                    openBackupFile.launch(arrayOf("*/*"))
                                } catch (e: ActivityNotFoundException) {
                                    backupViewModel.importPickerUnavailable()
                                }
                            }
                        },
                    )
                    Screen.IMPORT_REVIEW -> ImportReviewScreen(
                        review = checkNotNull(target.review),
                        notice = target.notice,
                        onDecision = backupViewModel::setDecision,
                        onContinue = backupViewModel::continueImport,
                        onCancel = backupViewModel::cancelImport,
                    )
                }
            }
        }
    }

    BackupDialogHost(
        state = backupState,
        message = backupMessage,
        viewModel = backupViewModel,
        onChooseExportFile = {
            try {
                createBackupFile.launch(suggestedBackupFileName(LocalDate.now()))
            } catch (e: ActivityNotFoundException) {
                backupViewModel.exportPickerUnavailable()
            }
        },
    )
}
