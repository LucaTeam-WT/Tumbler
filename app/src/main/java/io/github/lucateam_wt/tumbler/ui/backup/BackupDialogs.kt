package io.github.lucateam_wt.tumbler.ui.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.lucateam_wt.tumbler.R
import io.github.lucateam_wt.tumbler.backup.BackupPayload
import io.github.lucateam_wt.tumbler.backup.ConflictDecision
import io.github.lucateam_wt.tumbler.backup.MAX_PASSWORD_INPUT_UTF16_UNITS
import io.github.lucateam_wt.tumbler.backup.MIN_PASSWORD_CHARACTERS
import io.github.lucateam_wt.tumbler.backup.PasswordCheck

/** Test tags for the backup dialogs and the review screen. */
internal object BackupTags {
    const val EXPORT_PASSWORD = "backup-export-password"
    const val EXPORT_CONFIRMATION = "backup-export-confirmation"
    const val EXPORT_SUBMIT = "backup-export-submit"
    const val IMPORT_PASSWORD = "backup-import-password"
    const val IMPORT_SUBMIT = "backup-import-submit"
    const val PROGRESS = "backup-progress"
    const val MESSAGE_OK = "backup-message-ok"
    const val REVIEW_CONTINUE = "backup-review-continue"
    const val REVIEW_CANCEL = "backup-review-cancel"

    /** The review's bottom bar (Cancel, Continue), which covers the end of the list. */
    const val REVIEW_BAR = "backup-review-bar"
    const val CONFIRM_IMPORT = "backup-confirm-import"
    const val CONFIRM_BACK = "backup-confirm-back"

    /** The card of conflict [conflictIndex] (a [ConflictRow.index]). */
    fun conflict(conflictIndex: Int) = "backup-conflict-$conflictIndex"

    /** One choice of conflict [conflictIndex] (a [ConflictRow.index]). */
    fun choice(conflictIndex: Int, decision: ConflictDecision) =
        "backup-choice-$conflictIndex-${decision.name}"
}

/** Longest label shown in a message; longer ones are cut, so a 1 KiB name cannot fill the dialog. */
private const val MAX_LABEL_CHARS_IN_MESSAGE = 80

/** "Issuer (name)", or just the name when there is no issuer, as in the accounts list. */
internal fun AccountLabel.displayName(): String = if (issuer.isEmpty()) accountName else "$issuer ($accountName)"

/** [text] cut to at most [max] characters with an ellipsis, never splitting a surrogate pair. */
internal fun ellipsize(text: String, max: Int): String {
    if (text.length <= max) return text
    var end = max - 1
    if (Character.isLowSurrogate(text[end]) && Character.isHighSurrogate(text[end - 1])) end--
    return text.substring(0, end) + "…"
}

/**
 * A password field: secure (no copy, cut or drag), password keyboard (no suggestions or
 * learning), no autocorrect, a visibility toggle, and the backup format's input cap. Its
 * text is [input], which the view model owns: nothing here is saved.
 */
@Composable
private fun PasswordField(
    input: PasswordInput,
    label: String,
    showDescription: String,
    hideDescription: String,
    supportingText: String?,
    isError: Boolean,
    imeAction: ImeAction,
    tag: String,
    modifier: Modifier = Modifier,
    onDone: (() -> Unit)? = null,
) {
    // Not saved on purpose: after a configuration change the password is hidden again.
    var revealed by remember { mutableStateOf(false) }
    OutlinedSecureTextField(
        state = input.state,
        modifier = modifier
            .fillMaxWidth()
            .testTag(tag)
            .semantics { if (isError && supportingText != null) error(supportingText) },
        label = { Text(label) },
        trailingIcon = {
            IconButton(onClick = { revealed = !revealed }) {
                Icon(
                    painter = painterResource(if (revealed) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                    contentDescription = if (revealed) hideDescription else showDescription,
                )
            }
        },
        supportingText = supportingText?.let { text ->
            { Text(text, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        },
        isError = isError,
        inputTransformation = InputTransformation.maxLength(MAX_PASSWORD_INPUT_UTF16_UNITS),
        // System follows the "Show passwords" setting: hidden, or the last typed character briefly.
        textObfuscationMode = if (revealed) TextObfuscationMode.Visible else TextObfuscationMode.System,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
            imeAction = imeAction,
        ),
        onKeyboardAction = onDone?.let { done -> KeyboardActionHandler { done() } },
    )
}

/** Asks for the new backup's password twice, with live feedback; [onSubmit] is enabled once both pass. */
@Composable
internal fun ExportPasswordDialog(
    password: PasswordInput,
    confirmation: PasswordInput,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    val feedback by remember(password, confirmation) {
        derivedStateOf { newPasswordFeedback(password.state.text, confirmation.state.text) }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val passwordError = when (val check = feedback.password) {
        is PasswordCheck.TooLong ->
            pluralStringResource(R.plurals.backup_password_too_long, check.maximumCharacters, check.maximumCharacters)
        PasswordCheck.InvalidCharacters -> stringResource(R.string.backup_password_invalid)
        is PasswordCheck.TooShort, PasswordCheck.Ok, PasswordCheck.Mismatch, null -> null
    }
    AlertDialog(
        onDismissRequest = onCancel,
        // A tap outside must not throw away what was typed; Back and Cancel still close it.
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.backup_export_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.backup_export_body))
                PasswordField(
                    input = password,
                    label = stringResource(R.string.backup_password_label),
                    showDescription = stringResource(R.string.backup_password_show),
                    hideDescription = stringResource(R.string.backup_password_hide),
                    supportingText = passwordError
                        ?: pluralStringResource(R.plurals.backup_password_hint, MIN_PASSWORD_CHARACTERS, MIN_PASSWORD_CHARACTERS),
                    isError = passwordError != null,
                    imeAction = ImeAction.Next,
                    tag = BackupTags.EXPORT_PASSWORD,
                    modifier = Modifier.focusRequester(focus),
                )
                PasswordField(
                    input = confirmation,
                    label = stringResource(R.string.backup_password_confirm_label),
                    showDescription = stringResource(R.string.backup_password_confirm_show),
                    hideDescription = stringResource(R.string.backup_password_confirm_hide),
                    supportingText = if (feedback.mismatch) stringResource(R.string.backup_password_mismatch) else null,
                    isError = feedback.mismatch,
                    imeAction = ImeAction.Done,
                    tag = BackupTags.EXPORT_CONFIRMATION,
                    onDone = { if (feedback.canSubmit) onSubmit() },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSubmit,
                enabled = feedback.canSubmit,
                modifier = Modifier.testTag(BackupTags.EXPORT_SUBMIT),
            ) { Text(stringResource(R.string.backup_export_choose_file)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.add_cancel)) }
        },
    )
}

/** Asks for the password of the chosen backup; after a failed attempt it says "wrong password or damaged file". */
@Composable
internal fun ImportPasswordDialog(
    password: PasswordInput,
    wrongPasswordOrDamaged: Boolean,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    val empty by remember(password) { derivedStateOf { password.state.text.isEmpty() } }
    val focus = remember { FocusRequester() }
    // Focus moves to the field, so TalkBack reads it together with its error after a failed attempt.
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.backup_import_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.backup_import_password_body))
                PasswordField(
                    input = password,
                    label = stringResource(R.string.backup_password_label),
                    showDescription = stringResource(R.string.backup_password_show),
                    hideDescription = stringResource(R.string.backup_password_hide),
                    supportingText = if (wrongPasswordOrDamaged) stringResource(R.string.backup_import_wrong_password) else null,
                    isError = wrongPasswordOrDamaged,
                    imeAction = ImeAction.Done,
                    tag = BackupTags.IMPORT_PASSWORD,
                    modifier = Modifier.focusRequester(focus),
                    onDone = { if (!empty) onSubmit() },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSubmit,
                enabled = !empty,
                modifier = Modifier.testTag(BackupTags.IMPORT_SUBMIT),
            ) { Text(stringResource(R.string.backup_continue)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.add_cancel)) }
        },
    )
}

/**
 * An indeterminate progress dialog while scrypt or the import runs. Back and taps outside do
 * nothing, so they cannot interrupt the work half-way; [onCancel], if given, adds a Cancel
 * button (only where cancelling is safe).
 */
@Composable
internal fun BackupProgressDialog(text: String, onCancel: (() -> Unit)? = null) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .testTag(BackupTags.PROGRESS)
                .semantics { paneTitle = text },
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator()
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Text(
                    text = stringResource(R.string.backup_progress_wait),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onCancel != null) {
                    TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.add_cancel))
                    }
                }
            }
        }
    }
}

/** The last confirmation before an import writes anything: the totals, Import or Back. */
@Composable
internal fun ImportConfirmDialog(review: PlanReview, onConfirm: () -> Unit, onBack: () -> Unit) {
    AlertDialog(
        onDismissRequest = onBack,
        title = { Text(stringResource(R.string.backup_confirm_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.backup_total_add, review.toAdd))
                Text(stringResource(R.string.backup_total_replace, review.toReplace))
                Text(stringResource(R.string.backup_total_skip, review.toSkip))
                Text(stringResource(R.string.backup_confirm_body))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(BackupTags.CONFIRM_IMPORT)) {
                Text(stringResource(R.string.backup_import_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onBack, modifier = Modifier.testTag(BackupTags.CONFIRM_BACK)) {
                Text(stringResource(R.string.add_back))
            }
        },
    )
}

/** Shows one [BackupMessage] until the user dismisses it. */
@Composable
internal fun BackupMessageDialog(message: BackupMessage, onDismiss: () -> Unit) {
    val (title, body) = messageText(message)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(BackupTags.MESSAGE_OK)) {
                Text(stringResource(R.string.backup_ok))
            }
        },
    )
}

@Composable
private fun messageText(message: BackupMessage): Pair<String, String> = when (message) {
    is BackupMessage.Exported -> stringResource(R.string.backup_export_done_title) to
        pluralStringResource(R.plurals.backup_export_done_body, message.accountCount, message.accountCount)
    is BackupMessage.ExportFailed -> {
        val reason = exportFailureText(message.reason, message.account)
        val file = when (message.partialFile) {
            PartialFile.NONE -> null
            PartialFile.UNTOUCHED -> stringResource(R.string.backup_export_file_untouched)
            PartialFile.DELETED -> stringResource(R.string.backup_export_partial_deleted)
            PartialFile.NOT_DELETED -> stringResource(R.string.backup_export_partial_not_deleted)
        }
        stringResource(R.string.backup_export_failed_title) to listOfNotNull(reason, file).joinToString("\n\n")
    }
    is BackupMessage.Imported -> stringResource(R.string.backup_import_done_title) to
        stringResource(R.string.backup_import_done_body, message.added, message.replaced, message.skipped)
    is BackupMessage.ImportFailed -> stringResource(R.string.backup_import_failed_title) to importFailureText(message.reason)
    BackupMessage.ImportMayHaveBeenApplied -> stringResource(R.string.backup_import_maybe_title) to
        stringResource(R.string.backup_import_maybe_body)
}

@Composable
private fun exportFailureText(reason: ExportFailure, account: AccountLabel?): String {
    val name = account?.let { ellipsize(it.displayName(), MAX_LABEL_CHARS_IN_MESSAGE) }
    return when (reason) {
        ExportFailure.PASSWORD_REJECTED -> stringResource(R.string.backup_export_error_password)
        ExportFailure.TOO_MANY_ACCOUNTS ->
            pluralStringResource(R.plurals.backup_export_error_too_many, BackupPayload.MAX_ACCOUNTS, BackupPayload.MAX_ACCOUNTS)
        ExportFailure.ACCOUNT_LABEL_TOO_LONG -> name?.let { stringResource(R.string.backup_export_error_label_too_long, it) }
            ?: stringResource(R.string.backup_export_error_account_unnamed)
        ExportFailure.ACCOUNT_LABEL_NOT_VALID -> name?.let { stringResource(R.string.backup_export_error_label_invalid, it) }
            ?: stringResource(R.string.backup_export_error_account_unnamed)
        ExportFailure.ACCOUNT_SECRET_TOO_LONG -> name?.let { stringResource(R.string.backup_export_error_secret_too_long, it) }
            ?: stringResource(R.string.backup_export_error_account_unnamed)
        ExportFailure.ACCOUNT_UNREADABLE -> stringResource(R.string.backup_export_error_unreadable)
        ExportFailure.NOT_ENOUGH_MEMORY -> stringResource(R.string.backup_export_error_memory)
        ExportFailure.WRITE_FAILED -> stringResource(R.string.backup_export_error_write)
        ExportFailure.STORAGE_FAILED -> stringResource(R.string.backup_error_storage)
        ExportFailure.FILE_NOT_OPENED -> stringResource(R.string.backup_export_error_open)
        ExportFailure.INTERRUPTED -> stringResource(R.string.backup_export_error_interrupted)
        ExportFailure.NO_FILE_PICKER -> stringResource(R.string.backup_error_no_picker)
        ExportFailure.UNEXPECTED -> stringResource(R.string.backup_export_error_unexpected)
    }
}

@Composable
private fun importFailureText(reason: ImportFailure): String = stringResource(
    when (reason) {
        ImportFailure.NOT_A_BACKUP -> R.string.backup_import_error_not_a_backup
        ImportFailure.NOT_SUPPORTED -> R.string.backup_import_error_not_supported
        ImportFailure.INVALID_CONTENT -> R.string.backup_import_error_invalid
        ImportFailure.TOO_LARGE -> R.string.backup_import_error_too_large
        ImportFailure.READ_FAILED -> R.string.backup_import_error_read
        ImportFailure.FILE_NOT_OPENED -> R.string.backup_import_error_open
        ImportFailure.NOT_ENOUGH_MEMORY -> R.string.backup_import_error_memory
        ImportFailure.EXISTING_ACCOUNT_UNREADABLE -> R.string.backup_import_error_unreadable
        ImportFailure.STORAGE_FAILED -> R.string.backup_error_storage
        ImportFailure.NO_FILE_PICKER -> R.string.backup_error_no_picker
        ImportFailure.UNEXPECTED -> R.string.backup_import_error_unexpected
    },
)
