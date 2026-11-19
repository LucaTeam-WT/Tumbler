package com.phucho0237.lucaauth.backup

import com.phucho0237.lucaauth.domain.model.OtpAccount
import com.phucho0237.lucaauth.domain.model.OtpType
import java.security.MessageDigest

/** An account already stored on the device. [toString] prints only the id. */
internal data class ExistingAccount(val id: String, val account: OtpAccount) {
    override fun toString(): String = "ExistingAccount(id=$id, <redacted>)"
}

/** Why an imported account counts as a duplicate of an existing one. */
internal enum class ConflictReason {
    /** Same type, issuer and account name (exact, case-sensitive). */
    SAME_LABEL,

    /** Identical secret bytes. */
    SAME_SECRET,

    SAME_LABEL_AND_SECRET,
}

/** An imported account that duplicates [existing]; the user must decide what to do. */
internal class ImportConflict(
    val incoming: BackupEntry,
    val existing: ExistingAccount,
    val reason: ConflictReason,
) {
    override fun toString(): String = "ImportConflict(existingId=${existing.id}, reason=$reason)"
}

/** The user's answer for one [ImportConflict]. There is no default. */
internal enum class ConflictDecision {
    /** Do not import this account. */
    SKIP,

    /** Import it as a new account and leave the existing one unchanged. */
    KEEP_BOTH,

    /**
     * Overwrite the existing account with the imported one. If both are HOTP with identical
     * secret bytes, the larger counter is kept, so a replace never moves a counter backwards
     * for the same secret. If the secrets differ, the imported counter is used.
     */
    REPLACE,
}

/**
 * Result of [planImport]: accounts that can be added as they are, and conflicts that need
 * a [ConflictDecision] each. Building a plan writes nothing and decides nothing.
 */
internal class ImportPlan internal constructor(internal val items: List<Item>) {

    /** One imported account in backup order. */
    internal sealed interface Item {
        class Clean(val entry: BackupEntry) : Item
        class Conflicting(val conflict: ImportConflict) : Item
    }

    /** Imported accounts that duplicate nothing, in backup order. */
    val nonConflicting: List<BackupEntry> = items.filterIsInstance<Item.Clean>().map { it.entry }

    /** Conflicts in backup order; [applyDecisions] takes one decision per entry, same order. */
    val conflicts: List<ImportConflict> = items.filterIsInstance<Item.Conflicting>().map { it.conflict }

    override fun toString(): String = "ImportPlan(nonConflicting=${nonConflicting.size}, conflicts=${conflicts.size})"
}

/** Overwrite the stored account [existingId] with [account] (keeping its id and creation time). */
internal class Replacement(val existingId: String, val account: OtpAccount) {
    override fun toString(): String = "Replacement(existingId=$existingId, <redacted>)"
}

/** What to store after an import: new accounts to add and existing accounts to overwrite. */
internal class ImportWrites(val inserts: List<BackupEntry>, val replacements: List<Replacement>) {
    override fun toString(): String = "ImportWrites(inserts=${inserts.size}, replacements=${replacements.size})"
}

/**
 * Compares [incoming] (from a backup) with the [existing] accounts. An imported account
 * conflicts with an existing one if they have the same type, issuer and account name
 * (exact string match), or identical secret bytes.
 *
 * When several existing accounts match, the counterpart is the first (in [existing]
 * order) with the same secret, otherwise the first with the same label. Duplicates
 * inside [incoming] are not conflicts with each other. Neither list is modified.
 *
 * @throws IllegalArgumentException if two existing accounts share an id.
 */
internal fun planImport(existing: List<ExistingAccount>, incoming: List<BackupEntry>): ImportPlan {
    require(existing.mapTo(HashSet()) { it.id }.size == existing.size) { "Existing account ids must be unique" }
    val items = incoming.map { entry ->
        val account = entry.account
        val match = existing.firstOrNull { sameSecret(it.account, account) }
            ?: existing.firstOrNull { sameLabel(it.account, account) }
        if (match == null) {
            ImportPlan.Item.Clean(entry)
        } else {
            val reason = when {
                !sameSecret(match.account, account) -> ConflictReason.SAME_LABEL
                sameLabel(match.account, account) -> ConflictReason.SAME_LABEL_AND_SECRET
                else -> ConflictReason.SAME_SECRET
            }
            ImportPlan.Item.Conflicting(ImportConflict(entry, match, reason))
        }
    }
    return ImportPlan(items)
}

/**
 * Turns [plan] and one decision per conflict ([decisions] aligned with
 * [ImportPlan.conflicts]) into the writes to perform. Inserts keep backup order.
 *
 * For [ConflictDecision.REPLACE], the replacement carries every field of the imported
 * account, except that if both accounts are HOTP with identical secret bytes the counter
 * is the larger of the two (see [replacementFor]).
 *
 * @throws IllegalArgumentException if the number of decisions differs from the number of
 *   conflicts (nothing is ever overwritten by default), or if two decisions replace the
 *   same existing account.
 */
internal fun applyDecisions(plan: ImportPlan, decisions: List<ConflictDecision>): ImportWrites {
    require(decisions.size == plan.conflicts.size) { "Exactly one decision per conflict is required" }
    val inserts = ArrayList<BackupEntry>()
    val replacements = ArrayList<Replacement>()
    val replacedIds = HashSet<String>()
    var next = 0
    for (item in plan.items) {
        when (item) {
            is ImportPlan.Item.Clean -> inserts += item.entry
            is ImportPlan.Item.Conflicting -> when (decisions[next++]) {
                ConflictDecision.SKIP -> Unit
                ConflictDecision.KEEP_BOTH -> inserts += item.conflict.incoming
                ConflictDecision.REPLACE -> {
                    val conflict = item.conflict
                    require(replacedIds.add(conflict.existing.id)) {
                        "Two imported accounts cannot replace the same existing account"
                    }
                    replacements += Replacement(conflict.existing.id, replacementFor(conflict))
                }
            }
        }
    }
    return ImportWrites(inserts, replacements)
}

/**
 * The account that overwrites the existing one: the imported account, with one exception.
 *
 * - Both HOTP with identical secret bytes: the counter is the larger of the two, so a
 *   replace never moves a counter backwards for the same secret (which would regenerate
 *   codes the server has already accepted).
 * - Different secrets (only the labels matched, so the account was re-enrolled with a new
 *   secret): the imported counter. The old counter says nothing about the new secret, and
 *   a larger old counter could run past the server's look-ahead window.
 * - TOTP, or HOTP on one side only: the imported account as it is.
 */
private fun replacementFor(conflict: ImportConflict): OtpAccount {
    val incoming = conflict.incoming.account
    val existing = conflict.existing.account
    val sameHotpSecret = incoming.type == OtpType.HOTP &&
        existing.type == OtpType.HOTP &&
        sameSecret(incoming, existing)
    return if (sameHotpSecret && existing.counter > incoming.counter) {
        incoming.copy(counter = existing.counter)
    } else {
        incoming
    }
}

private fun sameLabel(a: OtpAccount, b: OtpAccount): Boolean =
    a.type == b.type && a.issuer == b.issuer && a.accountName == b.accountName

private fun sameSecret(a: OtpAccount, b: OtpAccount): Boolean = MessageDigest.isEqual(a.secret, b.secret)
