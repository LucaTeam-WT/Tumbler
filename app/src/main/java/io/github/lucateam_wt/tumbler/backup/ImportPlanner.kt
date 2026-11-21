package io.github.lucateam_wt.tumbler.backup

import io.github.lucateam_wt.tumbler.domain.model.OtpAccount
import io.github.lucateam_wt.tumbler.domain.model.OtpType
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

/**
 * An imported account that duplicates [existing]; the user must decide what to do.
 *
 * @property sharesCounterpart true if at least one other conflict in the same plan has
 *   [existing] as its counterpart too. At most one of them can replace it ([applyDecisions]
 *   rejects a second [ConflictDecision.REPLACE]), so the UI must say so.
 */
internal class ImportConflict(
    val incoming: BackupEntry,
    val existing: ExistingAccount,
    val reason: ConflictReason,
    val sharesCounterpart: Boolean,
) {
    override fun toString(): String =
        "ImportConflict(existingId=${existing.id}, reason=$reason, sharesCounterpart=$sharesCounterpart)"
}

/** The user's answer for one [ImportConflict]. There is no default. */
internal enum class ConflictDecision {
    /** Do not import this account. */
    SKIP,

    /**
     * Import it as a new account and leave the existing one unchanged. Not allowed when both
     * accounts are HOTP with identical secret bytes; see [allowedDecisions].
     */
    KEEP_BOTH,

    /**
     * Overwrite the existing account with the imported one. If the row, as read when the
     * write happens, and the imported account are both HOTP with identical secret bytes, the
     * larger of the two counters is written, so a replace never moves a counter backwards for
     * the same secret. Otherwise the imported counter is used. See [Replacement.accountToWrite].
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

    /**
     * Whether [other] pairs every imported account as this plan does, so decisions confirmed
     * for this plan mean the same for [other]. Used to plan again inside the write
     * transaction and abort if anything the user confirmed has changed.
     *
     * True if both plans hold equal imported accounts in the same order, and each one is
     * clean in both, or conflicting in both with the same existing id and an existing
     * account equal in every field except the counter. The [ConflictReason],
     * [ImportConflict.sharesCounterpart] and [allowedDecisions] of every conflict follow from
     * that, so they are equal too.
     *
     * The counter is left out on purpose: generating a HOTP code moves it, and
     * [Replacement.accountToWrite] already resolves it against the row as it is at write
     * time. An existing account that is the counterpart of no imported account does not take
     * part, so adding or deleting an unrelated account does not count as a change.
     */
    fun matches(other: ImportPlan): Boolean {
        if (items.size != other.items.size) return false
        return items.indices.all { index -> sameItem(items[index], other.items[index]) }
    }

    override fun toString(): String = "ImportPlan(nonConflicting=${nonConflicting.size}, conflicts=${conflicts.size})"
}

private fun sameItem(a: ImportPlan.Item, b: ImportPlan.Item): Boolean = when (a) {
    is ImportPlan.Item.Clean -> b is ImportPlan.Item.Clean && a.entry == b.entry
    is ImportPlan.Item.Conflicting -> b is ImportPlan.Item.Conflicting && sameConflict(a.conflict, b.conflict)
}

private fun sameConflict(a: ImportConflict, b: ImportConflict): Boolean =
    a.incoming == b.incoming &&
        a.existing.id == b.existing.id &&
        equalExceptCounter(a.existing.account, b.existing.account)

private fun equalExceptCounter(a: OtpAccount, b: OtpAccount): Boolean =
    a.issuer == b.issuer &&
        a.accountName == b.accountName &&
        a.type == b.type &&
        MessageDigest.isEqual(a.secret, b.secret) &&
        a.algorithm == b.algorithm &&
        a.digits == b.digits &&
        a.period == b.period

/**
 * How the counter written by a [Replacement] is chosen. It is a write-time rule: [counterPolicy]
 * decides it from the row as read when the write happens and the imported account, never
 * from what the plan saw (codes may have been generated, or the row changed, in between).
 */
internal enum class CounterPolicy {
    /**
     * Write the imported counter: the secrets differ (only the labels matched, so the account
     * was re-enrolled), or the accounts are not both HOTP.
     */
    USE_IMPORTED,

    /**
     * Both HOTP with identical secret bytes: write the larger of the stored counter and the
     * imported one, so a replace never moves the counter backwards for that secret.
     */
    KEEP_LARGER_OF_STORED_AND_IMPORTED,
}

/**
 * The counter policy for overwriting [storedNow] with [imported]:
 * [CounterPolicy.KEEP_LARGER_OF_STORED_AND_IMPORTED] if both are HOTP with identical secret
 * bytes, otherwise [CounterPolicy.USE_IMPORTED]. Pure.
 */
internal fun counterPolicy(storedNow: OtpAccount, imported: OtpAccount): CounterPolicy =
    if (isSameHotpSecret(storedNow, imported)) {
        CounterPolicy.KEEP_LARGER_OF_STORED_AND_IMPORTED
    } else {
        CounterPolicy.USE_IMPORTED
    }

/**
 * The counter to write when [imported] overwrites [storedNow]. Pure; it reads nothing else.
 *
 * The policy comes from [storedNow]'s type and secret ([counterPolicy]), so [storedNow] must
 * be the row as read inside the same transaction as the write, not the account seen when the
 * plan was made:
 * - [CounterPolicy.USE_IMPORTED]: the imported counter, whatever is stored;
 * - [CounterPolicy.KEEP_LARGER_OF_STORED_AND_IMPORTED]: the larger of the stored counter and
 *   the imported one.
 *
 * Called with the plan-time account it gives a display hint only (the row may change before
 * the write). The value to write comes from [Replacement.accountToWrite].
 */
internal fun resolveCounter(storedNow: OtpAccount, imported: OtpAccount): Long =
    when (counterPolicy(storedNow, imported)) {
        CounterPolicy.USE_IMPORTED -> imported.counter
        CounterPolicy.KEEP_LARGER_OF_STORED_AND_IMPORTED -> maxOf(storedNow.counter, imported.counter)
    }

/**
 * Overwrite the stored account [existingId] with an imported one, keeping the row's id and
 * creation time.
 *
 * The imported account is private, because its counter must not be written as it is. The
 * only way to get the account to write is [accountToWrite], which needs the row as read
 * inside the write transaction.
 */
internal class Replacement internal constructor(
    val existingId: String,
    private val imported: OtpAccount,
) {
    /**
     * The account to write over row [existingId]: every field of the imported account, and
     * the counter [resolveCounter] gives against [storedNow]. [storedNow] must be that row
     * as read inside the same transaction as the write. The counter policy is decided from
     * its type and secret, so it fits the row as it is when the write happens.
     *
     * The result shares the imported secret array; it is not copied.
     *
     * @throws IllegalArgumentException if [storedNow] is not row [existingId].
     */
    fun accountToWrite(storedNow: ExistingAccount): OtpAccount {
        require(storedNow.id == existingId) { "Stored row is not the row this replacement overwrites" }
        return imported.copy(counter = resolveCounter(storedNow.account, imported))
    }

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
 * When several existing accounts match, the counterpart is chosen by [MatchRank], best
 * first:
 * 1. same label and same secret (for an imported HOTP account, a HOTP row, since the type
 *    is part of the label);
 * 2. if the imported account is HOTP, another HOTP account with the same secret;
 * 3. an account with the same secret;
 * 4. an account with the same label.
 *
 * Ranks 1 and 2 make the same-secret HOTP rules hold against every existing HOTP account
 * with that secret, wherever it sits in [existing]: Keep both is then never offered
 * ([allowedDecisions]), so an import cannot add a second HOTP row for a secret the device
 * already counts with. Without them, an earlier TOTP account with the same secret would be
 * picked, and Keep both or Replace would leave two HOTP rows sharing a secret, one of them
 * behind. Rank 1 before rank 2 pairs an imported HOTP account with the same-label row when
 * the device holds several HOTP rows with its secret.
 *
 * Ranks are assigned in passes, best rank first, and within a pass in backup order. Within
 * a rank, an existing account that no other imported account has been paired with yet is
 * preferred, then [existing] order. A worse rank is never taken to avoid a shared
 * counterpart. When two imported accounts still end up with the same counterpart (the
 * device has fewer matching accounts than the backup), both conflicts say so in
 * [ImportConflict.sharesCounterpart], and at most one of them may replace it.
 *
 * Duplicates inside [incoming] are not conflicts with each other, including two HOTP
 * accounts with the same secret: both are inserted, as they were on the device that wrote
 * the backup. Neither list is modified.
 *
 * @throws IllegalArgumentException if two existing accounts share an id.
 */
internal fun planImport(existing: List<ExistingAccount>, incoming: List<BackupEntry>): ImportPlan {
    require(existing.mapTo(HashSet()) { it.id }.size == existing.size) { "Existing account ids must be unique" }
    // Every existing account that conflicts with each imported one, with its rank, in
    // existing order. Computed once: the passes below only filter these lists.
    val candidates = incoming.map { entry ->
        existing.mapNotNull { row -> matchRank(row.account, entry.account)?.let { rank -> rank to row } }
    }
    val counterparts = arrayOfNulls<ExistingAccount>(incoming.size)
    val pairings = HashMap<String, Int>()
    for (rank in MatchRank.entries) {
        for (index in incoming.indices) {
            if (counterparts[index] != null) continue
            val rows = candidates[index].filter { it.first == rank }.map { it.second }
            if (rows.isEmpty()) continue
            val chosen = rows.firstOrNull { it.id !in pairings } ?: rows.first()
            counterparts[index] = chosen
            pairings[chosen.id] = (pairings[chosen.id] ?: 0) + 1
        }
    }
    val items = incoming.mapIndexed { index, entry ->
        val match = counterparts[index]
        if (match == null) {
            ImportPlan.Item.Clean(entry)
        } else {
            val account = entry.account
            val reason = when {
                !sameSecret(match.account, account) -> ConflictReason.SAME_LABEL
                sameLabel(match.account, account) -> ConflictReason.SAME_LABEL_AND_SECRET
                else -> ConflictReason.SAME_SECRET
            }
            val shared = pairings.getValue(match.id) > 1
            ImportPlan.Item.Conflicting(ImportConflict(entry, match, reason, sharesCounterpart = shared))
        }
    }
    return ImportPlan(items)
}

/** How well an existing account matches an imported one, best first; see [planImport]. */
private enum class MatchRank {
    SAME_LABEL_AND_SECRET,
    SAME_HOTP_SECRET,
    SAME_SECRET,
    SAME_LABEL,
}

/** The [MatchRank] of [existing] for [imported], or null if they do not conflict. */
private fun matchRank(existing: OtpAccount, imported: OtpAccount): MatchRank? {
    val secret = sameSecret(existing, imported)
    val label = sameLabel(existing, imported)
    return when {
        secret && label -> MatchRank.SAME_LABEL_AND_SECRET
        secret && isSameHotpSecret(existing, imported) -> MatchRank.SAME_HOTP_SECRET
        secret -> MatchRank.SAME_SECRET
        label -> MatchRank.SAME_LABEL
        else -> null
    }
}

/**
 * The decisions the user may choose for [conflict], in display order. All three, except
 * when both accounts are HOTP with identical secret bytes: then only
 * [ConflictDecision.SKIP] and [ConflictDecision.REPLACE], because keeping both would leave
 * two rows with the same secret whose counters diverge (codes from the one that falls
 * behind are rejected by the server, and using both burns counter values twice).
 *
 * [planImport] picks a same-secret HOTP counterpart whenever one exists (ranks 1 and 2), so
 * this holds against every existing HOTP account with the imported HOTP account's secret.
 */
internal fun allowedDecisions(conflict: ImportConflict): Set<ConflictDecision> =
    if (isSameHotpSecret(conflict.incoming.account, conflict.existing.account)) {
        SAME_HOTP_SECRET_DECISIONS
    } else {
        ALL_DECISIONS
    }

// setOf keeps the order given, which is the display order.
private val ALL_DECISIONS: Set<ConflictDecision> =
    setOf(ConflictDecision.SKIP, ConflictDecision.KEEP_BOTH, ConflictDecision.REPLACE)
private val SAME_HOTP_SECRET_DECISIONS: Set<ConflictDecision> =
    setOf(ConflictDecision.SKIP, ConflictDecision.REPLACE)

/**
 * Turns [plan] and one decision per conflict ([decisions] aligned with
 * [ImportPlan.conflicts]) into the writes to perform. Inserts keep backup order.
 *
 * For [ConflictDecision.REPLACE], the [Replacement] holds the imported account; the counter
 * to write is decided at write time, from the row read in the write transaction, by
 * [Replacement.accountToWrite].
 *
 * @throws IllegalArgumentException if the number of decisions differs from the number of
 *   conflicts (nothing is ever overwritten by default), if a decision is not in
 *   [allowedDecisions] for its conflict, or if two decisions replace the same existing
 *   account. Nothing is returned in that case, so no partial set of writes exists.
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
            is ImportPlan.Item.Conflicting -> {
                val conflict = item.conflict
                val decision = decisions[next++]
                require(decision in allowedDecisions(conflict)) { "Decision not allowed for this conflict" }
                when (decision) {
                    ConflictDecision.SKIP -> Unit
                    ConflictDecision.KEEP_BOTH -> inserts += conflict.incoming
                    ConflictDecision.REPLACE -> {
                        require(replacedIds.add(conflict.existing.id)) {
                            "Two imported accounts cannot replace the same existing account"
                        }
                        // Every field comes from the import. The counter is decided when the
                        // write happens, from the row as it is then (Replacement.accountToWrite):
                        // - both HOTP with identical secret bytes: the larger counter, so a
                        //   replace never moves a counter backwards for the same secret (which
                        //   would regenerate codes the server has already accepted);
                        // - different secrets (the account was re-enrolled): the imported
                        //   counter, since a larger old one could run past the server's
                        //   look-ahead window;
                        // - TOTP, or HOTP on one side only: the imported account as it is.
                        replacements += Replacement(conflict.existing.id, conflict.incoming.account)
                    }
                }
            }
        }
    }
    return ImportWrites(inserts, replacements)
}

/** Both HOTP with identical secret bytes: the case where the two counters are one sequence. */
private fun isSameHotpSecret(a: OtpAccount, b: OtpAccount): Boolean =
    a.type == OtpType.HOTP && b.type == OtpType.HOTP && sameSecret(a, b)

private fun sameLabel(a: OtpAccount, b: OtpAccount): Boolean =
    a.type == b.type && a.issuer == b.issuer && a.accountName == b.accountName

private fun sameSecret(a: OtpAccount, b: OtpAccount): Boolean = MessageDigest.isEqual(a.secret, b.secret)
