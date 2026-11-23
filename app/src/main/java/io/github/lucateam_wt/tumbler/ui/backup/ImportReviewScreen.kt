package io.github.lucateam_wt.tumbler.ui.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lucateam_wt.tumbler.R
import io.github.lucateam_wt.tumbler.backup.ConflictDecision
import io.github.lucateam_wt.tumbler.backup.ConflictReason

/** A label can be 1 KiB of text; in a conflict card it gets at most this many lines. */
private const val LABEL_MAX_LINES = 3

/**
 * The plan of an import: how many accounts are new, identical (skipped) or in conflict,
 * and one card per conflict with exactly the decisions the plan allows. Skip is selected
 * until the user picks something else; Replace is never preselected. Accounts appear by
 * label only. [onCancel] (the close icon and the Cancel button) ends the import without
 * writing anything; [onContinue] goes to the final confirmation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImportReviewScreen(
    review: PlanReview,
    notice: ReviewNotice?,
    onDecision: (Int, ConflictDecision) -> Unit,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nothingToImport = review.toAdd == 0 && review.toReplace == 0
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_import_title)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.backup_review_close),
                        )
                    }
                },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.testTag(BackupTags.REVIEW_BAR)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onCancel, modifier = Modifier.testTag(BackupTags.REVIEW_CANCEL)) {
                        Text(stringResource(R.string.add_cancel))
                    }
                    Button(
                        onClick = onContinue,
                        enabled = !nothingToImport,
                        modifier = Modifier.testTag(BackupTags.REVIEW_CONTINUE),
                    ) { Text(stringResource(R.string.backup_continue)) }
                }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (notice != null) item(key = "notice") { NoticeCard(notice) }
            item(key = "summary") { SummaryCard(review, nothingToImport) }
            if (review.conflicts.isNotEmpty()) {
                item(key = "conflicts-header") {
                    Text(
                        text = stringResource(R.string.backup_review_conflicts_header),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .semantics { heading() },
                    )
                }
            }
            items(review.conflicts, key = { it.index }) { row -> ConflictCard(row, onDecision) }
        }
    }
}

@Composable
private fun NoticeCard(notice: ReviewNotice) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(
                when (notice) {
                    ReviewNotice.PLAN_CHANGED -> R.string.backup_notice_plan_changed
                    ReviewNotice.DECISIONS_REJECTED -> R.string.backup_notice_rejected
                    ReviewNotice.STORAGE_FAILED -> R.string.backup_notice_storage
                    ReviewNotice.NOT_ENOUGH_MEMORY -> R.string.backup_notice_memory
                },
            ),
            modifier = Modifier
                .padding(16.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun SummaryCard(review: PlanReview, nothingToImport: Boolean) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryLine(stringResource(R.string.backup_review_new), review.newAccounts)
            SummaryLine(stringResource(R.string.backup_review_identical), review.identical)
            SummaryLine(stringResource(R.string.backup_review_conflicts), review.conflicts.size)
            if (nothingToImport) {
                Text(
                    text = stringResource(R.string.backup_review_nothing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SummaryLine(label: String, count: Int) {
    // One node, so TalkBack reads "New accounts, 3".
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(count.toString(), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ConflictCard(row: ConflictRow, onDecision: (Int, ConflictDecision) -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().testTag(BackupTags.conflict(row.index))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.backup_review_imported, row.imported.displayName()),
                style = MaterialTheme.typography.titleSmall,
                maxLines = LABEL_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.backup_review_existing, row.existing.displayName()),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = LABEL_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    when (row.reason) {
                        ConflictReason.SAME_LABEL -> R.string.backup_review_reason_label
                        ConflictReason.SAME_SECRET -> R.string.backup_review_reason_secret
                        ConflictReason.SAME_LABEL_AND_SECRET -> R.string.backup_review_reason_both
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (row.sharesCounterpart) {
                Text(
                    text = stringResource(R.string.backup_review_shared),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.padding(top = 4.dp).selectableGroup()) {
                for (choice in row.choices) ChoiceRow(row, choice, onDecision)
            }
        }
    }
}

@Composable
private fun ChoiceRow(row: ConflictRow, choice: ConflictDecision, onDecision: (Int, ConflictDecision) -> Unit) {
    val selected = row.decision == choice
    val blocked = choice == ConflictDecision.REPLACE && row.replaceTaken && !selected
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = !blocked,
                role = Role.RadioButton,
                onClick = { onDecision(row.index, choice) },
            )
            .testTag(BackupTags.choice(row.index, choice))
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // The row is the control; the radio button only shows its state.
        RadioButton(selected = selected, onClick = null, enabled = !blocked)
        Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(
                    when (choice) {
                        ConflictDecision.SKIP -> R.string.backup_decision_skip
                        ConflictDecision.KEEP_BOTH -> R.string.backup_decision_keep_both
                        ConflictDecision.REPLACE -> R.string.backup_decision_replace
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = choiceDescription(choice, row.counters),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (blocked) {
                Text(
                    text = stringResource(R.string.backup_decision_replace_taken),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** What a choice does, including what happens to the HOTP counter (the write-time counter rule). */
@Composable
private fun choiceDescription(choice: ConflictDecision, counters: HotpCounters?): String = when (choice) {
    ConflictDecision.SKIP -> stringResource(R.string.backup_decision_skip_desc)
    ConflictDecision.KEEP_BOTH -> counters?.imported?.let { stringResource(R.string.backup_decision_keep_both_desc_hotp, it) }
        ?: stringResource(R.string.backup_decision_keep_both_desc)
    ConflictDecision.REPLACE -> when {
        counters?.replaceKeepsLarger == true && counters.stored != null && counters.imported != null ->
            stringResource(R.string.backup_decision_replace_desc_larger, counters.stored, counters.imported)
        counters?.imported != null -> stringResource(R.string.backup_decision_replace_desc_imported, counters.imported)
        else -> stringResource(R.string.backup_decision_replace_desc)
    }
}
