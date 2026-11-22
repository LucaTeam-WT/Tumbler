package io.github.lucateam_wt.tumbler.ui.backup

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.lucateam_wt.tumbler.R

/**
 * The dialog of the current backup [state] (password, progress, final confirmation) and
 * the pending [message]. [onChooseExportFile] opens the system picker once the export
 * password was accepted. The review screen itself is shown by the app's root.
 */
@Composable
internal fun BackupDialogHost(
    state: BackupUiState,
    message: BackupMessage?,
    viewModel: BackupViewModel,
    onChooseExportFile: () -> Unit,
) {
    when (state) {
        BackupUiState.Idle,
        BackupUiState.ExportChoosingFile,
        is BackupUiState.Reviewing,
        -> Unit
        BackupUiState.ExportPassword -> ExportPasswordDialog(
            password = viewModel.exportPassword,
            confirmation = viewModel.exportConfirmation,
            onSubmit = { if (viewModel.submitExportPassword()) onChooseExportFile() },
            onCancel = viewModel::cancelExport,
        )
        BackupUiState.Exporting -> BackupProgressDialog(stringResource(R.string.backup_exporting))
        is BackupUiState.ImportPassword -> ImportPasswordDialog(
            password = viewModel.importPassword,
            wrongPasswordOrDamaged = state.wrongPasswordOrDamaged,
            onSubmit = viewModel::submitImportPassword,
            onCancel = viewModel::cancelImport,
        )
        // Reading writes nothing, so it can be cancelled; the service wipes a plan it still makes.
        BackupUiState.ImportReading -> BackupProgressDialog(stringResource(R.string.backup_reading), onCancel = viewModel::cancelImport)
        is BackupUiState.Confirming -> ImportConfirmDialog(
            review = state.review,
            onConfirm = viewModel::confirmImport,
            onBack = viewModel::backToReview,
        )
        is BackupUiState.Importing -> BackupProgressDialog(stringResource(R.string.backup_importing))
    }
    if (message != null) BackupMessageDialog(message, onDismiss = viewModel::messageShown)
}
