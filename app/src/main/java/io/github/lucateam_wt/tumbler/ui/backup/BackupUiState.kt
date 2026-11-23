package io.github.lucateam_wt.tumbler.ui.backup

import io.github.lucateam_wt.tumbler.backup.ConflictDecision
import io.github.lucateam_wt.tumbler.backup.ConflictReason
import io.github.lucateam_wt.tumbler.backup.CounterPolicy
import io.github.lucateam_wt.tumbler.backup.ImportConflict
import io.github.lucateam_wt.tumbler.backup.ImportPlan
import io.github.lucateam_wt.tumbler.backup.allowedDecisions
import io.github.lucateam_wt.tumbler.backup.counterPolicy
import io.github.lucateam_wt.tumbler.domain.model.OtpAccount
import io.github.lucateam_wt.tumbler.domain.model.OtpType

/**
 * Where the backup flows are. Holds no password and no secret, only labels and counts, and
 * is never saved: after process death every flow starts again from [Idle].
 */
internal sealed interface BackupUiState {

    /** No flow is running. */
    data object Idle : BackupUiState

    /** The export password dialog ([BackupViewModel.exportPassword], [BackupViewModel.exportConfirmation]). */
    data object ExportPassword : BackupUiState

    /** The password was accepted; the system picker is choosing where to save the file. */
    data object ExportChoosingFile : BackupUiState

    /** Encrypting and writing the file (scrypt takes a few seconds). */
    data object Exporting : BackupUiState

    /** The system picker is choosing the backup to import; a second tap on Import opens no second picker. */
    data object ImportChoosingFile : BackupUiState

    /**
     * The import password dialog ([BackupViewModel.importPassword]) for the chosen file.
     * [wrongPasswordOrDamaged] after an attempt that failed that way: one message for both,
     * as the format cannot tell them apart.
     */
    data class ImportPassword(val wrongPasswordOrDamaged: Boolean) : BackupUiState

    /** Decrypting the file and comparing it with the accounts on the device. */
    data object ImportReading : BackupUiState

    /** The plan is shown and the user decides each conflict. Cancel is available. */
    data class Reviewing(val review: PlanReview, val notice: ReviewNotice?) : BackupUiState

    /** The totals of [review], for a last confirmation before anything is written. Cancel is available. */
    data class Confirming(val review: PlanReview) : BackupUiState

    /** Writing the import of [review]. Once begun it cannot be stopped, so there is no cancel. */
    data class Importing(val review: PlanReview) : BackupUiState
}

/** Why the review is shown again after the user confirmed. */
internal enum class ReviewNotice {
    /** The accounts on the device changed meanwhile: nothing was written, this is a new plan. */
    PLAN_CHANGED,

    /** The choices did not fit the plan: nothing was written, and they were reset. */
    DECISIONS_REJECTED,

    /** Storage failed and the import was rolled back: nothing was written, it can be retried. */
    STORAGE_FAILED,

    /** Not enough memory: nothing was written, it can be retried. */
    NOT_ENOUGH_MEMORY,
}

/** An issuer and account name, for display. [toString] hides both. */
internal data class AccountLabel(val issuer: String, val accountName: String) {
    override fun toString(): String = "AccountLabel(<redacted>)"
}

internal fun OtpAccount.label(): AccountLabel = AccountLabel(issuer, accountName)

/**
 * HOTP counters of a conflict, for the text of each choice. [stored] is null if the account on
 * the device is not HOTP, [imported] if the imported one is not. [replaceKeepsLarger]: both
 * are HOTP with the same secret, so Replace writes the larger counter (decided again from the
 * stored row when the import is written).
 */
internal data class HotpCounters(val stored: Long?, val imported: Long?, val replaceKeepsLarger: Boolean)

/** One conflict as the review shows it: labels only, never a secret. */
internal data class ConflictRow(
    /** Position in [ImportPlan.conflicts], so the decision list lines up with the plan. */
    val index: Int,
    val imported: AccountLabel,
    val existing: AccountLabel,
    val reason: ConflictReason,
    /** Another imported account matches the same account on the device. */
    val sharesCounterpart: Boolean,
    /** The decisions [allowedDecisions] offers, in display order. */
    val choices: List<ConflictDecision>,
    val decision: ConflictDecision,
    /** Another conflict already replaces the same account on the device, so this one cannot. */
    val replaceTaken: Boolean,
    val counters: HotpCounters?,
)

/**
 * What an import will do, for the review and the confirmation. Identical accounts (every
 * field and the secret equal to one on the device) are always skipped and only counted.
 */
internal data class PlanReview(
    val newAccounts: Int,
    val identical: Int,
    val conflicts: List<ConflictRow>,
) {
    /** Accounts the import adds: new ones and Keep both. */
    val toAdd: Int get() = newAccounts + conflicts.count { it.decision == ConflictDecision.KEEP_BOTH }
    val toReplace: Int get() = conflicts.count { it.decision == ConflictDecision.REPLACE }
    val toSkip: Int get() = identical + conflicts.count { it.decision == ConflictDecision.SKIP }

    override fun toString(): String =
        "PlanReview(newAccounts=$newAccounts, identical=$identical, conflicts=${conflicts.size})"
}

/** True if the imported account equals the one on the device in every field, secret included. */
internal fun ImportConflict.isIdentical(): Boolean = incoming.account == existing.account

/**
 * The decision each conflict starts with: always [ConflictDecision.SKIP], the one that changes
 * nothing. Replace, which overwrites an account, is never preselected.
 */
internal fun defaultDecisions(plan: ImportPlan): List<ConflictDecision> = plan.conflicts.map { ConflictDecision.SKIP }

/** The review of [plan] with [decisions] (one per conflict, in plan order). */
internal fun planReview(plan: ImportPlan, decisions: List<ConflictDecision>): PlanReview {
    require(decisions.size == plan.conflicts.size) { "One decision per conflict" }
    val replacing = HashMap<String, Int>()
    plan.conflicts.forEachIndexed { index, conflict ->
        if (decisions[index] == ConflictDecision.REPLACE) replacing[conflict.existing.id] = index
    }
    var identical = 0
    val rows = ArrayList<ConflictRow>()
    plan.conflicts.forEachIndexed { index, conflict ->
        if (conflict.isIdentical()) {
            identical++
            return@forEachIndexed
        }
        val incoming = conflict.incoming.account
        val existing = conflict.existing.account
        rows += ConflictRow(
            index = index,
            imported = incoming.label(),
            existing = existing.label(),
            reason = conflict.reason,
            sharesCounterpart = conflict.sharesCounterpart,
            choices = allowedDecisions(conflict).toList(),
            decision = decisions[index],
            replaceTaken = replacing[conflict.existing.id].let { it != null && it != index },
            counters = if (incoming.type == OtpType.HOTP || existing.type == OtpType.HOTP) {
                HotpCounters(
                    stored = existing.counter.takeIf { existing.type == OtpType.HOTP },
                    imported = incoming.counter.takeIf { incoming.type == OtpType.HOTP },
                    replaceKeepsLarger = counterPolicy(existing, incoming) == CounterPolicy.KEEP_LARGER_OF_STORED_AND_IMPORTED,
                )
            } else {
                null
            },
        )
    }
    return PlanReview(newAccounts = plan.nonConflicting.size, identical = identical, conflicts = rows)
}

/** A result to show once, as a dialog, after a flow ends. Never holds a password or a secret. */
internal sealed interface BackupMessage {

    /** The backup was written. The text warns that it is only as safe as its password. */
    data class Exported(val accountCount: Int) : BackupMessage

    /**
     * The export failed. [partialFile] says what happened to the file the picker created;
     * [account] names the account the backup cannot hold, for the reasons that have one.
     */
    data class ExportFailed(
        val reason: ExportFailure,
        val partialFile: PartialFile,
        val account: AccountLabel? = null,
    ) : BackupMessage

    data class Imported(val added: Int, val replaced: Int, val skipped: Int) : BackupMessage

    data class ImportFailed(val reason: ImportFailure) : BackupMessage

    /**
     * The import ended in a way that may have written it (the database failed while
     * committing, or the result was lost): the list is reloaded and the user asked to check
     * it. Never offered as a retry, which could import twice.
     */
    data object ImportMayHaveBeenApplied : BackupMessage
}

/** What happened to the file chosen for a failed export. */
internal enum class PartialFile {
    /** No file was chosen. */
    NONE,

    /**
     * The file was not opened for writing, so it is as it was: empty if the picker created
     * it for this export, or its earlier content if the picker returned an existing file.
     */
    UNTOUCHED,

    /** Deleted: it was opened for writing (so truncated) and the export failed, or it was empty. */
    DELETED,

    /** The provider refused to delete it (it was emptied if possible); the user must delete it. */
    NOT_DELETED,
}

internal enum class ExportFailure {
    /** The service refused the password (the dialog checks the same rules first). */
    PASSWORD_REJECTED,
    TOO_MANY_ACCOUNTS,
    ACCOUNT_LABEL_TOO_LONG,
    ACCOUNT_LABEL_NOT_VALID,
    ACCOUNT_SECRET_TOO_LONG,

    /** An account cannot be decrypted; it is never named, it shows as unavailable in the list. */
    ACCOUNT_UNREADABLE,
    NOT_ENOUGH_MEMORY,
    WRITE_FAILED,
    STORAGE_FAILED,
    FILE_NOT_OPENED,

    /** The app was restarted while the file was being chosen, so the password is gone. */
    INTERRUPTED,
    NO_FILE_PICKER,
    UNEXPECTED,
}

internal enum class ImportFailure {
    NOT_A_BACKUP,

    /** Made by a newer version, or its parameters are outside what this version accepts. */
    NOT_SUPPORTED,

    /** Decrypted, but the content is not valid. */
    INVALID_CONTENT,
    TOO_LARGE,
    READ_FAILED,
    FILE_NOT_OPENED,
    NOT_ENOUGH_MEMORY,

    /** An account on the device cannot be decrypted; never named. */
    EXISTING_ACCOUNT_UNREADABLE,
    STORAGE_FAILED,
    NO_FILE_PICKER,
    UNEXPECTED,
}
