package io.github.lucateam_wt.tumbler.ui.backup

import android.database.SQLException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.lucateam_wt.tumbler.TumblerApplication
import io.github.lucateam_wt.tumbler.backup.AccountRejection
import io.github.lucateam_wt.tumbler.backup.BackupReadResult
import io.github.lucateam_wt.tumbler.backup.BackupService
import io.github.lucateam_wt.tumbler.backup.ConflictDecision
import io.github.lucateam_wt.tumbler.backup.ExportResult
import io.github.lucateam_wt.tumbler.backup.ImportReadResult
import io.github.lucateam_wt.tumbler.backup.ImportResult
import io.github.lucateam_wt.tumbler.backup.PasswordCheck
import io.github.lucateam_wt.tumbler.backup.PreparedImport
import io.github.lucateam_wt.tumbler.backup.allowedDecisions
import io.github.lucateam_wt.tumbler.backup.validatePasswordConfirmation
import io.github.lucateam_wt.tumbler.domain.model.AccountSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException

/**
 * Runs encrypted export and import with [BackupService], as the state machine in
 * [BackupUiState], and publishes each outcome once in [message].
 *
 * **Passwords.** The dialogs edit [exportPassword], [exportConfirmation] and
 * [importPassword], which live only in this view model (see [PasswordInput] for why they
 * are not in saved state). A password leaves its field only as a [CharArray] made by
 * [chars] at the moment it is used; the field is cleared at once, and the array is wiped as
 * soon as the service returns, on cancel and dismiss, if the app restarts meanwhile (the
 * array is simply gone with the process), and in [onCleared]. The export password waits in
 * [pendingExportPassword] only while the system picker chooses the file.
 *
 * **Secrets.** A [PreparedImport] holds decrypted secrets until it is applied or closed:
 * this view model closes it on cancel, on every result that ends the import, when it is
 * replaced by a new plan, and in [onCleared] (closing while an apply runs takes effect when
 * the apply ends). The UI state holds labels and counts only.
 *
 * **Import.** Work runs in [viewModelScope], so leaving the review screen does not abandon a
 * started import, and nothing joins it on the main thread. Every [ImportResult] is mapped by
 * [importStep] with an exhaustive `when`: a new plan is shown for a new confirmation, never
 * applied by itself, and a result that may have written the import ([PreparedImport.isOpen]
 * false) reloads the list and says so instead of offering a retry.
 *
 * **Export.** The file from the picker is opened (which truncates it) only when the service
 * writes the finished backup ([OpenOnFirstWriteOutput]), so a failure or a cancellation
 * before that leaves it as it was, even if the picker returned a file that already held
 * something. Once the file was opened, every outcome but success (including a failed close
 * and a cancellation) deletes it, or empties it if the provider refuses, so no broken file
 * is left looking like a backup. A file that could not be opened, and one that arrives after
 * the app was restarted, is deleted only if it is empty.
 *
 * @param accountSummaries the accounts on the device, to name an account the backup cannot hold.
 * @param chars copies a field's text into a new array; a seam so tests can check the wiping.
 */
internal class BackupViewModel(
    private val service: BackupService,
    private val accountSummaries: suspend () -> List<AccountSummary>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val chars: (CharSequence) -> CharArray = CharSequence::toCharArrayCopy,
) : ViewModel() {

    private val _state = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    private val _message = MutableStateFlow<BackupMessage?>(null)

    /** The outcome to show; call [messageShown] once it was dismissed. */
    val message: StateFlow<BackupMessage?> = _message.asStateFlow()

    private val reloads = Channel<Unit>(Channel.CONFLATED)

    /** Emits when the accounts list should be read again: an import was, or may have been, written. */
    val reloadRequests: Flow<Unit> = reloads.receiveAsFlow()

    val exportPassword = PasswordInput()
    val exportConfirmation = PasswordInput()
    val importPassword = PasswordInput()

    /** The accepted export password while the picker chooses the file; wiped on every exit. */
    private var pendingExportPassword: CharArray? = null

    private var importFile: BackupFile? = null
    private var prepared: PreparedImport? = null

    /** One per conflict of [prepared]'s plan, in plan order. */
    private var decisions: List<ConflictDecision> = emptyList()
    private var readJob: Job? = null

    fun messageShown() {
        _message.value = null
    }

    // ---- export ----

    fun startExport() {
        if (_state.value != BackupUiState.Idle) return
        clearExportFields()
        _state.value = BackupUiState.ExportPassword
    }

    /** Closes the password dialog or ends an export whose file was not chosen yet. */
    fun cancelExport() {
        when (_state.value) {
            BackupUiState.ExportPassword, BackupUiState.ExportChoosingFile -> Unit
            else -> return
        }
        wipePendingExportPassword()
        clearExportFields()
        _state.value = BackupUiState.Idle
    }

    /**
     * Takes the password from the dialog if it and its confirmation pass the export rules;
     * returns true if it did, and the UI then opens the picker. The fields are cleared and
     * the password waits, as an array, for [exportFileChosen].
     */
    fun submitExportPassword(): Boolean {
        if (_state.value != BackupUiState.ExportPassword) return false
        val password = chars(exportPassword.state.text)
        val check = try {
            val confirmation = chars(exportConfirmation.state.text)
            try {
                validatePasswordConfirmation(password, confirmation)
            } finally {
                confirmation.fill(WIPED_CHAR)
            }
        } catch (t: Throwable) {
            password.fill(WIPED_CHAR)
            throw t
        }
        if (check != PasswordCheck.Ok) {
            password.fill(WIPED_CHAR)
            return false
        }
        wipePendingExportPassword()
        pendingExportPassword = password
        clearExportFields()
        _state.value = BackupUiState.ExportChoosingFile
        return true
    }

    /** The system picker could not be opened. */
    fun exportPickerUnavailable() {
        if (_state.value != BackupUiState.ExportChoosingFile) return
        wipePendingExportPassword()
        _state.value = BackupUiState.Idle
        _message.value = BackupMessage.ExportFailed(ExportFailure.NO_FILE_PICKER, PartialFile.NONE)
    }

    /** The picker's result: the file to write, or null if the user cancelled. */
    fun exportFileChosen(file: BackupFile?) {
        val password = pendingExportPassword
        pendingExportPassword = null
        val choosing = _state.value == BackupUiState.ExportChoosingFile
        if (file == null) {
            password?.fill(WIPED_CHAR)
            if (choosing) _state.value = BackupUiState.Idle
            return
        }
        if (password == null || !choosing) {
            // The app was restarted while the picker was open, so the password is gone. Remove
            // the empty file the picker created, but never a file that holds something: the
            // picker may have returned an existing file.
            password?.fill(WIPED_CHAR)
            viewModelScope.launch {
                val partial = deleteIfEmpty(file)
                _message.value = BackupMessage.ExportFailed(ExportFailure.INTERRUPTED, partial)
            }
            return
        }
        _state.value = BackupUiState.Exporting
        viewModelScope.launch {
            val message = try {
                export(file, password)
            } finally {
                password.fill(WIPED_CHAR)
            }
            _state.value = BackupUiState.Idle
            _message.value = message
        }
    }

    /**
     * Exports into [file], which is opened only when the finished backup is written. A failed
     * close is a failed write. What happens to the file on a failure:
     * - not opened (the failure came first): left as it was, [PartialFile.UNTOUCHED];
     * - the open failed: deleted only if it is empty ([deleteIfEmpty]);
     * - opened (so truncated, maybe partly written): deleted, whatever it held before.
     */
    private suspend fun export(file: BackupFile, password: CharArray): BackupMessage {
        val output = OpenOnFirstWriteOutput(file)
        var result: ExportResult? = null
        var unexpected = false
        var partial = PartialFile.UNTOUCHED
        try {
            result = service.exportAccounts(password, output)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            unexpected = true
        } finally {
            val (finished, fileOutcome) = finishFile(output, file, result)
            result = finished
            partial = fileOutcome
        }
        return when {
            unexpected -> BackupMessage.ExportFailed(ExportFailure.UNEXPECTED, partial)
            output.openFailed -> BackupMessage.ExportFailed(ExportFailure.FILE_NOT_OPENED, partial)
            else -> exportMessage(checkNotNull(result), partial)
        }
    }

    /**
     * Closes [output] if the file was opened, then deletes the file or leaves it, and returns
     * [result] (a [ExportResult.WriteFailed] if a successful export's close failed) and what
     * happened to the file. All in one `NonCancellable` block: a cancelled caller that returns
     * from such a block gets a `CancellationException` (the block itself has run), so a second
     * step after it would be skipped.
     */
    private suspend fun finishFile(
        output: OpenOnFirstWriteOutput,
        file: BackupFile,
        result: ExportResult?,
    ): Pair<ExportResult?, PartialFile> = withContext(NonCancellable + ioDispatcher) {
        var finished = result
        // A provider may only commit the file on close, so a close that fails loses the backup.
        if (output.isOpen && !closeQuietly(output) && finished is ExportResult.Success) finished = ExportResult.WriteFailed
        val partial = when {
            output.openFailed -> deleteNowIfEmpty(file)
            !output.isOpen -> PartialFile.UNTOUCHED
            // Every outcome but success (a cancellation too) leaves a file that is not a backup.
            finished !is ExportResult.Success -> deleteNow(file)
            else -> PartialFile.NONE
        }
        finished to partial
    }

    private suspend fun exportMessage(result: ExportResult, partial: PartialFile): BackupMessage = when (result) {
        is ExportResult.Success -> BackupMessage.Exported(result.accountCount)
        is ExportResult.PasswordRejected -> BackupMessage.ExportFailed(ExportFailure.PASSWORD_REJECTED, partial)
        is ExportResult.AccountRejected -> when (result.reason) {
            AccountRejection.TOO_MANY_ACCOUNTS -> BackupMessage.ExportFailed(ExportFailure.TOO_MANY_ACCOUNTS, partial)
            AccountRejection.LABEL_NOT_TRIMMED,
            AccountRejection.LABEL_UNPAIRED_SURROGATE,
            -> BackupMessage.ExportFailed(ExportFailure.ACCOUNT_LABEL_NOT_VALID, partial, labelOf(result.accountId))
            AccountRejection.LABEL_TOO_LONG ->
                BackupMessage.ExportFailed(ExportFailure.ACCOUNT_LABEL_TOO_LONG, partial, labelOf(result.accountId))
            AccountRejection.SECRET_TOO_LONG ->
                BackupMessage.ExportFailed(ExportFailure.ACCOUNT_SECRET_TOO_LONG, partial, labelOf(result.accountId))
        }
        // Never named: its row shows as unavailable in the list.
        is ExportResult.AccountUnreadable -> BackupMessage.ExportFailed(ExportFailure.ACCOUNT_UNREADABLE, partial)
        ExportResult.NotEnoughMemory -> BackupMessage.ExportFailed(ExportFailure.NOT_ENOUGH_MEMORY, partial)
        ExportResult.WriteFailed -> BackupMessage.ExportFailed(ExportFailure.WRITE_FAILED, partial)
        ExportResult.StorageFailed -> BackupMessage.ExportFailed(ExportFailure.STORAGE_FAILED, partial)
    }

    /** The label of a readable account the backup cannot hold, from the app's own list. */
    private suspend fun labelOf(accountId: String): AccountLabel? =
        try {
            accountSummaries().firstOrNull { it.id == accountId }?.let { AccountLabel(it.issuer, it.accountName) }
        } catch (e: SQLException) {
            null
        }

    /** [deleteNowIfEmpty] off the main thread, finished even if the caller is cancelled. */
    private suspend fun deleteIfEmpty(file: BackupFile): PartialFile =
        withContext(NonCancellable + ioDispatcher) { deleteNowIfEmpty(file) }

    // Blocking: call these on ioDispatcher.

    private fun deleteNow(file: BackupFile): PartialFile =
        if (file.delete()) PartialFile.DELETED else PartialFile.NOT_DELETED

    /** Deletes [file] only if it is known to be empty, as one the picker just created; never one that holds bytes. */
    private fun deleteNowIfEmpty(file: BackupFile): PartialFile =
        if (file.isKnownEmpty()) deleteNow(file) else PartialFile.UNTOUCHED

    private fun closeQuietly(closeable: Closeable): Boolean =
        try {
            closeable.close()
            true
        } catch (e: Exception) {
            false
        }

    private fun wipePendingExportPassword() {
        pendingExportPassword?.fill(WIPED_CHAR)
        pendingExportPassword = null
    }

    private fun clearExportFields() {
        exportPassword.clear()
        exportConfirmation.clear()
    }

    // ---- import ----

    /** The picker's result: the backup to read, or null if the user cancelled. */
    fun importFileChosen(file: BackupFile?) {
        if (file == null || _state.value != BackupUiState.Idle) return
        importFile = file
        importPassword.clear()
        _state.value = BackupUiState.ImportPassword(wrongPasswordOrDamaged = false)
    }

    /** The system picker could not be opened. */
    fun importPickerUnavailable() {
        if (_state.value != BackupUiState.Idle) return
        _message.value = BackupMessage.ImportFailed(ImportFailure.NO_FILE_PICKER)
    }

    /** Reads the chosen file with the password in the dialog; the field is cleared at once. */
    fun submitImportPassword() {
        if (_state.value !is BackupUiState.ImportPassword || importPassword.isEmpty) return
        val file = importFile ?: return
        val password = chars(importPassword.state.text)
        importPassword.clear()
        _state.value = BackupUiState.ImportReading
        readJob = viewModelScope.launch {
            try {
                read(file, password)
            } finally {
                password.fill(WIPED_CHAR)
            }
        }
    }

    private suspend fun read(file: BackupFile, password: CharArray) {
        var made: ImportReadResult? = null
        val result = try {
            withContext(ioDispatcher) {
                val input = file.openInput()
                try {
                    service.readBackup(input, password).also { made = it }
                } finally {
                    try {
                        input.close()
                    } catch (e: Exception) {
                        // Reading is over; a failed close changes nothing.
                    }
                }
            }
        } catch (t: Throwable) {
            // A plan made but dropped here (by a cancellation after the service returned) is wiped.
            (made as? ImportReadResult.Ready)?.prepared?.close()
            when (t) {
                is CancellationException -> throw t
                is IOException, is SecurityException -> {
                    endImport(BackupMessage.ImportFailed(ImportFailure.FILE_NOT_OPENED))
                    return
                }
                is Exception -> {
                    endImport(BackupMessage.ImportFailed(ImportFailure.UNEXPECTED))
                    return
                }
                else -> throw t
            }
        }
        readJob = null
        if (_state.value != BackupUiState.ImportReading) {
            // Cancelled while the result was on its way back: drop it, wiping a plan it holds.
            (result as? ImportReadResult.Ready)?.prepared?.close()
            return
        }
        when (result) {
            is ImportReadResult.Ready -> {
                importFile = null
                showReview(result.prepared, notice = null)
            }
            is ImportReadResult.FileRejected -> when (result.reason) {
                // One message for both, on purpose: the format cannot tell them apart. Same file, try again.
                BackupReadResult.WrongPasswordOrDamaged ->
                    _state.value = BackupUiState.ImportPassword(wrongPasswordOrDamaged = true)
                BackupReadResult.NotABackup -> endImport(BackupMessage.ImportFailed(ImportFailure.NOT_A_BACKUP))
                BackupReadResult.UnsupportedVersion,
                BackupReadResult.ParametersOutOfBounds,
                -> endImport(BackupMessage.ImportFailed(ImportFailure.NOT_SUPPORTED))
                BackupReadResult.InvalidPayload -> endImport(BackupMessage.ImportFailed(ImportFailure.INVALID_CONTENT))
                BackupReadResult.TooLarge -> endImport(BackupMessage.ImportFailed(ImportFailure.TOO_LARGE))
                BackupReadResult.NotEnoughMemory -> endImport(BackupMessage.ImportFailed(ImportFailure.NOT_ENOUGH_MEMORY))
                BackupReadResult.ReadFailed -> endImport(BackupMessage.ImportFailed(ImportFailure.READ_FAILED))
            }
            ImportReadResult.NotEnoughMemory -> endImport(BackupMessage.ImportFailed(ImportFailure.NOT_ENOUGH_MEMORY))
            // Never named by label.
            is ImportReadResult.ExistingAccountUnreadable ->
                endImport(BackupMessage.ImportFailed(ImportFailure.EXISTING_ACCOUNT_UNREADABLE))
            ImportReadResult.StorageFailed -> endImport(BackupMessage.ImportFailed(ImportFailure.STORAGE_FAILED))
        }
    }

    private fun showReview(plan: PreparedImport, notice: ReviewNotice?) {
        prepared = plan
        decisions = defaultDecisions(plan.plan)
        _state.value = BackupUiState.Reviewing(planReview(plan.plan, decisions), notice)
    }

    /**
     * Sets the decision of conflict [index] (a [ConflictRow.index]). Ignored unless the plan
     * offers it ([allowedDecisions]), and Replace is ignored if another conflict already
     * replaces the same account on the device.
     */
    fun setDecision(index: Int, decision: ConflictDecision) {
        val current = _state.value as? BackupUiState.Reviewing ?: return
        val plan = prepared?.plan ?: return
        val conflict = plan.conflicts.getOrNull(index) ?: return
        if (conflict.isIdentical() || decision !in allowedDecisions(conflict)) return
        if (decision == ConflictDecision.REPLACE) {
            val taken = plan.conflicts.indices.any { other ->
                other != index && decisions[other] == ConflictDecision.REPLACE &&
                    plan.conflicts[other].existing.id == conflict.existing.id
            }
            if (taken) return
        }
        decisions = decisions.toMutableList().also { it[index] = decision }
        _state.value = current.copy(review = planReview(plan, decisions))
    }

    /** From the review to the final confirmation, which shows the totals. */
    fun continueImport() {
        val current = _state.value as? BackupUiState.Reviewing ?: return
        _state.value = BackupUiState.Confirming(current.review)
    }

    /** From the final confirmation back to the review, decisions kept. */
    fun backToReview() {
        val current = _state.value as? BackupUiState.Confirming ?: return
        _state.value = BackupUiState.Reviewing(current.review, notice = null)
    }

    /** Ends the import before anything is written: allowed until the user confirms. */
    fun cancelImport() {
        when (_state.value) {
            is BackupUiState.ImportPassword,
            BackupUiState.ImportReading,
            is BackupUiState.Reviewing,
            is BackupUiState.Confirming,
            -> Unit
            else -> return
        }
        // A read still running is cancelled; the service closes the plan it may still make.
        readJob?.cancel()
        readJob = null
        endImport(message = null)
    }

    /** Writes the import with the confirmed decisions. It cannot be cancelled once started. */
    fun confirmImport() {
        val current = _state.value as? BackupUiState.Confirming ?: return
        val plan = prepared ?: return
        val chosen = decisions
        _state.value = BackupUiState.Importing(current.review)
        viewModelScope.launch {
            val result = try {
                service.applyImport(plan, chosen)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (result == null) {
                // Unknown outcome: never offer a retry that could import twice.
                finishImport(plan, ImportStep.Finish(BackupMessage.ImportMayHaveBeenApplied, reload = true))
            } else {
                finishImport(plan, importStep(result, plan))
            }
        }
    }

    private fun finishImport(plan: PreparedImport, step: ImportStep) {
        when (step) {
            is ImportStep.Finish -> {
                endImport(step.message)
                plan.close()
                if (step.reload) reloads.trySend(Unit)
            }
            is ImportStep.Review -> {
                // A new plan replaces this one, which the service already closed.
                if (step.plan !== plan) plan.close()
                prepared = step.plan
                if (step.resetDecisions) decisions = defaultDecisions(step.plan.plan)
                _state.value = BackupUiState.Reviewing(planReview(step.plan.plan, decisions), step.notice)
            }
        }
    }

    /** Ends the import flow: closes the plan (wiping its secrets), clears the password field, shows [message]. */
    private fun endImport(message: BackupMessage?) {
        prepared?.close()
        prepared = null
        decisions = emptyList()
        importFile = null
        importPassword.clear()
        _state.value = BackupUiState.Idle
        if (message != null) _message.value = message
    }

    override fun onCleared() {
        // viewModelScope is already cancelled: a running export or read wipes its own password
        // array; a running apply finishes, and closing its plan takes effect when it ends.
        wipePendingExportPassword()
        clearExportFields()
        importPassword.clear()
        prepared?.close()
        prepared = null
        importFile = null
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                val container = (app as TumblerApplication).container
                BackupViewModel(
                    service = container.backupService,
                    accountSummaries = { container.accountRepository.observeAccounts().first() },
                )
            }
        }
    }
}

/** What the view model does with an [ImportResult]. */
internal sealed interface ImportStep {

    /** The import is over: close the plan, show [message], and reload the list if [reload]. */
    data class Finish(val message: BackupMessage, val reload: Boolean) : ImportStep

    /**
     * Show the review again for [plan] (the same one, or a new one) with [notice], for a new
     * confirmation; nothing was written. Decisions go back to the defaults if [resetDecisions].
     */
    class Review(val plan: PreparedImport, val notice: ReviewNotice, val resetDecisions: Boolean) : ImportStep {
        override fun toString(): String = "Review(notice=$notice, resetDecisions=$resetDecisions)"
    }
}

/**
 * Maps every [ImportResult] (exhaustively, so a new result breaks the build) to what the UI
 * does next, following [BackupService.applyImport]: a new plan is shown for a new
 * confirmation and never applied by itself, and a failure after which [plan] is no longer
 * open may have written the import, so it ends with a reload and
 * [BackupMessage.ImportMayHaveBeenApplied] instead of a retry.
 */
internal fun importStep(result: ImportResult, plan: PreparedImport): ImportStep = when (result) {
    is ImportResult.Success ->
        ImportStep.Finish(BackupMessage.Imported(result.inserted, result.replaced, result.skipped), reload = true)
    is ImportResult.PlanChanged -> ImportStep.Review(result.newPlan, ReviewNotice.PLAN_CHANGED, resetDecisions = true)
    ImportResult.DecisionsRejected -> ImportStep.Review(plan, ReviewNotice.DECISIONS_REJECTED, resetDecisions = true)
    // This call wrote nothing, but the view model only ever holds a closed plan after an apply
    // of it that may have been committed, so the user is told to check rather than to retry.
    ImportResult.PlanClosed -> ImportStep.Finish(BackupMessage.ImportMayHaveBeenApplied, reload = true)
    // Nothing was written, but a retry would fail the same way. Never named by label.
    is ImportResult.ExistingAccountUnreadable ->
        ImportStep.Finish(BackupMessage.ImportFailed(ImportFailure.EXISTING_ACCOUNT_UNREADABLE), reload = false)
    ImportResult.StorageFailed -> if (plan.isOpen) {
        ImportStep.Review(plan, ReviewNotice.STORAGE_FAILED, resetDecisions = false)
    } else {
        ImportStep.Finish(BackupMessage.ImportMayHaveBeenApplied, reload = true)
    }
    ImportResult.NotEnoughMemory -> if (plan.isOpen) {
        ImportStep.Review(plan, ReviewNotice.NOT_ENOUGH_MEMORY, resetDecisions = false)
    } else {
        ImportStep.Finish(BackupMessage.ImportMayHaveBeenApplied, reload = true)
    }
}
