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

    /**
     * Import it as a new account and leave the existing one unchanged. Not allowed when both
     * accounts are HOTP with identical secret bytes; see [allowedDecisions].
     */
    KEEP_BOTH,

    /**
     * Overwrite the existing account with the imported one. If both are HOTP with identical
     * secret bytes, the larger of the stored counter (read when the write happens) and the
     * imported one is written, so a replace never moves a counter backwards for the same
     * secret. Otherwise the imported counter is used. See [CounterPolicy] and [resolveCounter].
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

/**
 * How the counter of a [Replacement] is chosen. It is a write-time rule: [resolveCounter]
 * applies it to the counter stored in the row when the write happens, never to the one seen
 * when the plan was made (codes may have been generated in between).
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
 * Overwrite the stored account [existingId] with [imported], keeping the row's id and
 * creation time.
 *
 * **The counter to write is not `imported.counter`.** It is
 * `resolveCounter(storedNow, this)`, where `storedNow` is the counter of row [existingId]
 * read inside the same transaction as the write. That transaction must also check that the
 * row still exists with the type and secret it had when the plan was made, and abort (and
 * plan again) otherwise, because [counterPolicy] was chosen from them.
 *
 * @property imported the imported account; every field except the counter is written as is.
 * @property counterPolicy how the written counter is chosen; see [resolveCounter].
 * @property counterAtPlanTime the row's counter when the plan was made. Only for
 *   [plannedCounter]; never write it.
 */
internal class Replacement(
    val existingId: String,
    val imported: OtpAccount,
    val counterPolicy: CounterPolicy,
    val counterAtPlanTime: Long,
) {
    /**
     * What [resolveCounter] gives against the counter seen when the plan was made. A display
     * hint only (for example on a confirmation screen): the stored counter may have moved
     * since, so this value must never be written.
     */
    val plannedCounter: Long get() = resolveCounter(counterAtPlanTime, this)

    override fun toString(): String = "Replacement(existingId=$existingId, counterPolicy=$counterPolicy, <redacted>)"
}

/**
 * The counter to write for [operation], given [storedNow]: the counter of row
 * [Replacement.existingId] read inside the same transaction as the write, not the one seen
 * when the plan was made. Pure; it reads nothing else.
 *
 * - [CounterPolicy.USE_IMPORTED]: the imported counter, whatever is stored.
 * - [CounterPolicy.KEEP_LARGER_OF_STORED_AND_IMPORTED]: the larger of [storedNow] and the
 *   imported counter.
 *
 * @throws IllegalArgumentException if [storedNow] is negative (no stored account can have
 *   such a counter).
 */
internal fun resolveCounter(storedNow: Long, operation: Replacement): Long {
    require(storedNow >= 0) { "Stored counter must not be negative" }
    val imported = operation.imported.counter
    return when (operation.counterPolicy) {
        CounterPolicy.USE_IMPORTED -> imported
        CounterPolicy.KEEP_LARGER_OF_STORED_AND_IMPORTED -> maxOf(storedNow, imported)
    }
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
 * order) of:
 * 1. if the imported account is HOTP, a HOTP account with the same secret;
 * 2. an account with the same secret;
 * 3. an account with the same label.
 *
 * Step 1 makes the same-secret HOTP rules hold against every existing HOTP account with
 * that secret, wherever it sits in [existing]: Keep both is then never offered
 * ([allowedDecisions]), so an import cannot add a second HOTP row for a secret the device
 * already counts with, and Replace overwrites one of those rows with
 * [CounterPolicy.KEEP_LARGER_OF_STORED_AND_IMPORTED]. Without it, an earlier TOTP account
 * with the same secret would be picked, and Keep both or Replace would leave two HOTP rows
 * sharing a secret, one of them behind.
 *
 * Duplicates inside [incoming] are not conflicts with each other, including two HOTP
 * accounts with the same secret: both are inserted, as they were on the device that wrote
 * the backup. Neither list is modified.
 *
 * @throws IllegalArgumentException if two existing accounts share an id.
 */
internal fun planImport(existing: List<ExistingAccount>, incoming: List<BackupEntry>): ImportPlan {
    require(existing.mapTo(HashSet()) { it.id }.size == existing.size) { "Existing account ids must be unique" }
    val items = incoming.map { entry ->
        val account = entry.account
        val match = existing.firstOrNull { isSameHotpSecret(it.account, account) }
            ?: existing.firstOrNull { sameSecret(it.account, account) }
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
 * The decisions the user may choose for [conflict], in display order. All three, except
 * when both accounts are HOTP with identical secret bytes: then only
 * [ConflictDecision.SKIP] and [ConflictDecision.REPLACE], because keeping both would leave
 * two rows with the same secret whose counters diverge (codes from the one that falls
 * behind are rejected by the server, and using both burns counter values twice).
 *
 * [planImport] picks a same-secret HOTP counterpart whenever one exists, so this holds
 * against every existing HOTP account with the imported HOTP account's secret.
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
 * For [ConflictDecision.REPLACE], the [Replacement] carries the imported account and a
 * [CounterPolicy]; the counter to write is decided at write time by [resolveCounter] (see
 * [replacementFor]).
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
                        replacements += replacementFor(conflict)
                    }
                }
            }
        }
    }
    return ImportWrites(inserts, replacements)
}

/**
 * The write that overwrites the existing account with the imported one. Every field comes
 * from the import; the counter follows a [CounterPolicy]:
 *
 * - Both HOTP with identical secret bytes: [CounterPolicy.KEEP_LARGER_OF_STORED_AND_IMPORTED],
 *   so a replace never moves a counter backwards for the same secret (which would regenerate
 *   codes the server has already accepted), even if codes were generated after the plan.
 * - Different secrets (only the labels matched, so the account was re-enrolled with a new
 *   secret): [CounterPolicy.USE_IMPORTED]. The old counter says nothing about the new
 *   secret, and a larger old counter could run past the server's look-ahead window.
 * - TOTP, or HOTP on one side only: [CounterPolicy.USE_IMPORTED] (the imported account as it is).
 */
private fun replacementFor(conflict: ImportConflict): Replacement {
    val incoming = conflict.incoming.account
    val existing = conflict.existing.account
    val policy = if (isSameHotpSecret(incoming, existing)) {
        CounterPolicy.KEEP_LARGER_OF_STORED_AND_IMPORTED
    } else {
        CounterPolicy.USE_IMPORTED
    }
    return Replacement(conflict.existing.id, incoming, policy, counterAtPlanTime = existing.counter)
}

/** Both HOTP with identical secret bytes: the case where the two counters are one sequence. */
private fun isSameHotpSecret(a: OtpAccount, b: OtpAccount): Boolean =
    a.type == OtpType.HOTP && b.type == OtpType.HOTP && sameSecret(a, b)

private fun sameLabel(a: OtpAccount, b: OtpAccount): Boolean =
    a.type == b.type && a.issuer == b.issuer && a.accountName == b.accountName

private fun sameSecret(a: OtpAccount, b: OtpAccount): Boolean = MessageDigest.isEqual(a.secret, b.secret)
