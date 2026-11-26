package io.github.lucateam_wt.tumbler.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.lucateam_wt.tumbler.R

/** Test tags of the storage views. */
internal object StorageTags {
    const val MIGRATION = "storage-migration"
    const val MIGRATION_PROGRESS = "storage-migration-progress"
    const val BANNER = "storage-banner"
    const val NEW_KEY_BUTTON = "storage-new-key"
    const val RETRY_BUTTON = "storage-retry"
    const val NEW_KEY_CONFIRM = "storage-new-key-confirm"
}

/**
 * Shown instead of the list while accounts are moved to the storage key: a title, a short
 * explanation and "N of M" with a progress bar. TalkBack reads the progress as one polite live
 * region ("N of M accounts updated").
 */
@Composable
internal fun MigrationState(progress: MigrationProgress, modifier: Modifier = Modifier) {
    val description = pluralStringResource(R.plurals.storage_migrating_description, progress.total, progress.done, progress.total)
    Column(
        modifier = modifier.padding(32.dp).testTag(StorageTags.MIGRATION),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.storage_migrating_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.storage_migrating_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        val fraction = progress.done.toFloat() / progress.total
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .widthIn(max = 320.dp)
                .fillMaxWidth()
                .testTag(StorageTags.MIGRATION_PROGRESS)
                .clearAndSetSemantics {
                    contentDescription = description
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    liveRegion = LiveRegionMode.Polite
                },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.storage_migrating_progress, progress.done, progress.total),
            style = MaterialTheme.typography.labelLarge,
            // The bar above already says it to TalkBack.
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

/**
 * The storage key message above the list: what happened, and the one thing the user can do about
 * it ("Start a new storage key", which asks for confirmation first, or "Try again").
 */
@Composable
internal fun StorageBanner(
    notice: StorageNotice,
    onStartNewKey: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = when {
        notice.kind == StorageNoticeKind.TEMPORARY -> stringResource(R.string.storage_temporary_title)
        notice.unreadableCount > 0 ->
            pluralStringResource(R.plurals.storage_unreadable_title, notice.unreadableCount, notice.unreadableCount)
        else -> stringResource(R.string.storage_new_key_title)
    }
    val body = when (notice.kind) {
        StorageNoticeKind.NEW_KEY_REQUIRED -> stringResource(R.string.storage_new_key_body)
        StorageNoticeKind.UNREADABLE -> stringResource(R.string.storage_unreadable_body)
        StorageNoticeKind.TEMPORARY -> stringResource(R.string.storage_temporary_body)
    }
    Card(
        modifier = modifier.fillMaxWidth().testTag(StorageTags.BANNER),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                when (notice.kind) {
                    StorageNoticeKind.NEW_KEY_REQUIRED ->
                        TextButton(onClick = onStartNewKey, modifier = Modifier.testTag(StorageTags.NEW_KEY_BUTTON)) {
                            Text(stringResource(R.string.storage_new_key_action))
                        }
                    StorageNoticeKind.TEMPORARY ->
                        TextButton(onClick = onRetry, modifier = Modifier.testTag(StorageTags.RETRY_BUTTON)) {
                            Text(stringResource(R.string.storage_retry_action))
                        }
                    StorageNoticeKind.UNREADABLE -> Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

/** Asks before a new storage key is started; says plainly that unreadable accounts stay unreadable. */
@Composable
internal fun NewStorageKeyDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.storage_new_key_dialog_title)) },
        text = { Text(stringResource(R.string.storage_new_key_dialog_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(StorageTags.NEW_KEY_CONFIRM)) {
                Text(stringResource(R.string.storage_new_key_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.add_cancel)) }
        },
    )
}
