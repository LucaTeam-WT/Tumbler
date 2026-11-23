package io.github.lucateam_wt.tumbler.backup

import android.database.SQLException
import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipherException
import io.github.lucateam_wt.tumbler.domain.model.StoredAccount
import io.github.lucateam_wt.tumbler.domain.repository.AccountBackupStore
import io.github.lucateam_wt.tumbler.domain.repository.UnreadableAccountException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Export and import of every account in the backup format (`docs/backup-format.md`).
 *
 * All work runs on [dispatcher] (by default [Dispatchers.Default]: scrypt is CPU-bound), so
 * every function may be called from the main thread. Every outcome the caller must handle
 * is a closed result, never an exception, including running out of memory anywhere in the
 * operation and an `IOException` from a stream. What is left to propagate is a programming
 * or platform fault (for example AES-GCM missing from the platform), an exception other than
 * `IOException` thrown by the caller's stream, and cancellation.
 *
 * **Passwords:** each function takes ownership of its password array and fills it with
 * zeros before it returns, whatever the outcome. **Secrets:** the decrypted secrets the
 * service reads (the accounts on the device, the accounts in a backup) are wiped once they
 * are no longer needed; see [PreparedImport] for the ones an import holds while the user
 * decides. A plan the service has made is closed and wiped if a cancellation stops it from
 * reaching the caller: a result that `withContext` drops because the caller was cancelled
 * after the service made it (an [ImportReadResult.Ready] or an [ImportResult.PlanChanged])
 * is closed before the cancellation propagates. The same holds when allocating that result
 * runs out of memory: the result is allocated before the plan is handed over, and a plan
 * that cannot be handed over is closed (or, in [applyImport], left open for a retry). The
 * store wipes a secret it is decrypting when the caller is cancelled, and every secret read
 * so far when converting them runs out of memory (best-effort, as everywhere: see
 * `docs/backup-format.md`). Nothing is logged, and no result's `toString` contains a
 * password, a secret, a label or an account id.
 *
 * The service never opens or closes a stream: the caller owns both.
 */
internal class BackupService(
    private val store: AccountBackupStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val codec: BackupCodec = DefaultBackupCodec,
    private val results: PlanResults = DefaultPlanResults,
) {

    /**
     * Writes a backup of every account on the device, protected by [password], to [output]
     * and flushes it. The password is checked first ([validatePassword]; the UI should have
     * checked it and its confirmation with [validatePasswordConfirmation]), then every
     * account is read and decrypted, checked against the backup's limits, and encrypted.
     * One account the backup cannot hold blocks the whole export, by design.
     *
     * Writes to [output] only on [ExportResult.Success] and [ExportResult.WriteFailed]. The
     * decrypted secrets are wiped as soon as the backup is encrypted, before anything is
     * written to [output]; on every other exit they are wiped before this returns.
     */
    suspend fun exportAccounts(password: CharArray, output: OutputStream): ExportResult =
        withContext(dispatcher) {
            try {
                export(password, output)
            } finally {
                password.fill(WIPED_CHAR)
            }
        }

    /**
     * Reads a backup from [input] with the bounded stream reader (at most 4 MiB + 1 bytes are
     * requested), decrypts it with [password] and plans it against the accounts on the
     * device as they are now ([planImport]). Writes nothing.
     *
     * On [ImportReadResult.Ready] the caller must eventually pass the [PreparedImport] to
     * [applyImport] or close it. On every other result the imported secrets are already wiped,
     * and so they are if the caller is cancelled before it receives a [ImportReadResult.Ready].
     */
    suspend fun readBackup(input: InputStream, password: CharArray): ImportReadResult {
        var made: ImportReadResult? = null
        try {
            return withContext(dispatcher) {
                try {
                    read(input, password).also { made = it }
                } finally {
                    password.fill(WIPED_CHAR)
                }
            }
        } catch (t: Throwable) {
            // The caller never gets the plan (cancelled after it was made): wipe it here.
            (made as? ImportReadResult.Ready)?.prepared?.close()
            throw t
        }
    }

    /**
     * Writes [prepared] with one [decisions] entry per conflict (in [ImportPlan.conflicts]
     * order), all in one transaction of the account store:
     *
     * 1. The decisions are checked against the confirmed plan first: exactly one per
     *    conflict, each one of [allowedDecisions], and at most one [ConflictDecision.REPLACE]
     *    per existing account. Otherwise [ImportResult.DecisionsRejected], and no transaction
     *    is started.
     * 2. Inside the transaction the accounts are read again and the import is planned again
     *    with them. If that plan does not [ImportPlan.matches] the confirmed one (an account
     *    was added, deleted or changed in a way that changes what the user confirmed), nothing
     *    is written and the result is [ImportResult.PlanChanged] with a new plan.
     * 3. Otherwise the writes come from the plan made inside the transaction: each
     *    [Replacement] writes [Replacement.accountToWrite] for the row read there (so a HOTP
     *    counter moved since the plan is never moved backwards), keeping the row's id and
     *    creation time, and each insert keeps the imported creation time.
     *
     * A failure while writing rolls the whole transaction back. [prepared] stays open after a
     * result that wrote nothing (except [ImportResult.PlanChanged], which replaces it, and
     * [ImportResult.PlanClosed]), so the caller can retry or close it. After
     * [ImportResult.Success] its secrets are wiped and it is closed.
     *
     * **Cancellation:** once the apply has begun on the service's dispatcher, a cancellation
     * does not stop the transaction. The service waits for it under `NonCancellable`, and
     * Room's wait for its transaction thread and the write lock cannot be cancelled, so the
     * import is written and [prepared] closed (or the plan is found changed and nothing is
     * written). A cancellation that lands before that point, while entering or dispatching
     * `withContext`, throws `CancellationException` with nothing written and [prepared] still
     * open. Either way the cancellation propagates as `CancellationException`, and
     * [PreparedImport.isOpen] says whether a retry is possible. A new plan made for a caller
     * cancelled meanwhile is closed, never dropped with its secrets.
     *
     * **Maybe applied:** once the transaction block has returned its writes, the store may
     * have committed them, so every other exit from that point on (cancellation, running out
     * of memory, a database error while committing) closes [prepared] and wipes its secrets
     * instead of reopening it: a retry returns [ImportResult.PlanClosed] and can never write
     * the import twice.
     *
     * **For the UI:** do not `join()` the import's job on the main thread (it waits for the
     * transaction), and `withTimeout` cannot stop an import that has started. A
     * [ImportResult.StorageFailed] or [ImportResult.NotEnoughMemory] with
     * [PreparedImport.isOpen] false means the import may have been written: reload the
     * accounts instead of offering a retry.
     */
    suspend fun applyImport(prepared: PreparedImport, decisions: List<ConflictDecision>): ImportResult {
        var made: ImportResult? = null
        try {
            return withContext(dispatcher) { apply(prepared, decisions).also { made = it } }
        } catch (t: Throwable) {
            // As in readBackup: the old plan is already closed, so nothing else would wipe the new one.
            (made as? ImportResult.PlanChanged)?.newPlan?.close()
            throw t
        }
    }

    private suspend fun export(password: CharArray, output: OutputStream): ExportResult {
        val check = validatePassword(password)
        if (check != PasswordCheck.Ok) return ExportResult.PasswordRejected(check)
        var stored: List<StoredAccount> = emptyList()
        try {
            stored = store.readAll()
            val file = try {
                codec.export(stored.map { BackupEntry(it.account, it.createdAtMillis) }, password)
            } catch (e: AccountRejectedException) {
                return ExportResult.AccountRejected(stored[e.accountIndex].id, e.reason)
            } catch (e: BackupNotEnoughMemoryException) {
                return ExportResult.NotEnoughMemory
            }
            // The encrypted backup no longer needs them: wipe before the stream (and so the
            // document provider) is touched, which may block or be slow.
            wipeSecrets(stored)
            try {
                output.write(file)
                output.flush()
            } catch (e: IOException) {
                return ExportResult.WriteFailed
            }
            return ExportResult.Success(stored.size)
        } catch (e: UnreadableAccountException) {
            return ExportResult.AccountUnreadable(e.accountId)
        } catch (e: SQLException) {
            return ExportResult.StorageFailed
        } catch (e: OutOfMemoryError) {
            // Outside scrypt (which exportBackup maps itself); docs/backup-format.md item 2.
            return ExportResult.NotEnoughMemory
        } finally {
            // Every other exit; wiping twice is harmless.
            wipeSecrets(stored)
        }
    }

    /** Wipes the decrypted secrets read for an export; the backup entries share these arrays. */
    private fun wipeSecrets(stored: List<StoredAccount>) {
        stored.forEach { it.account.secret.fill(0) }
    }

    private suspend fun read(input: InputStream, password: CharArray): ImportReadResult {
        var incoming: List<BackupEntry>? = null
        var handedOver = false
        try {
            when (val result = codec.read(input, password)) {
                is BackupReadResult.Success -> incoming = result.accounts
                BackupReadResult.NotEnoughMemory -> return ImportReadResult.NotEnoughMemory
                is BackupReadResult.Failure -> return ImportReadResult.FileRejected(result)
            }
            val existing = store.readAll().toExistingAccounts()
            val prepared = try {
                PreparedImport(incoming, existing)
            } catch (e: Throwable) {
                wipe(existing)
                throw e
            }
            // Allocate the result before handing the plan over: if that runs out of memory, the
            // plan is closed here instead of being dropped with its secrets.
            val ready = try {
                results.ready(prepared)
            } catch (e: Throwable) {
                prepared.close()
                throw e
            }
            handedOver = true
            return ready
        } catch (e: UnreadableAccountException) {
            return ImportReadResult.ExistingAccountUnreadable(e.accountId)
        } catch (e: SQLException) {
            return ImportReadResult.StorageFailed
        } catch (e: OutOfMemoryError) {
            return ImportReadResult.NotEnoughMemory
        } finally {
            if (!handedOver) incoming?.forEach { it.account.secret.fill(0) }
        }
    }

    private suspend fun apply(prepared: PreparedImport, decisions: List<ConflictDecision>): ImportResult {
        if (!prepared.begin()) return ImportResult.PlanClosed
        var fresh: List<ExistingAccount>? = null
        var freshHandedOver = false
        var ended = false
        // Set by the block as it returns its writes: from then on the store may commit them.
        var mayBeCommitted = false
        try {
            try {
                applyDecisions(prepared.plan, decisions)
            } catch (e: IllegalArgumentException) {
                return ImportResult.DecisionsRejected
            }
            // Room resumes a cancelled caller at once while its transaction thread may still
            // commit, so wait for the transaction to end whatever happens to the caller.
            val written = withContext(NonCancellable) {
                store.writeTransaction {
                    val rows = readAll().toExistingAccounts()
                    fresh = rows
                    val current = planImport(rows, prepared.incoming)
                    if (!current.matches(prepared.plan)) return@writeTransaction null
                    // Same pairings as the confirmed plan, so the decisions fit this plan too.
                    val writes = applyDecisions(current, decisions)
                    val rowsById = rows.associateBy { it.id }
                    for (replacement in writes.replacements) {
                        val storedNow = rowsById.getValue(replacement.existingId)
                        replace(replacement.existingId, replacement.accountToWrite(storedNow))
                    }
                    for (entry in writes.inserts) insert(entry.account, entry.createdAtMillis)
                    mayBeCommitted = true
                    writes
                }
            }
            if (written == null) {
                // Nothing was written. A cancelled caller would drop a new plan with its
                // secrets, so make none: this one is released below.
                currentCoroutineContext().ensureActive()
                // Make the new plan and its result before this plan changes state. If either
                // allocation runs out of memory, nothing has been handed over: this plan is
                // released below (still open, so isOpen does not report a write that never
                // happened) and the rows just read are wiped. The new plan shares this plan's
                // imported accounts, so it is dropped without closing it.
                val next = prepared.planAgain(checkNotNull(fresh))
                val changed = results.planChanged(next)
                val replaced = prepared.replaceWith(next)
                ended = true
                if (!replaced) return ImportResult.PlanClosed
                freshHandedOver = true
                return changed
            }
            prepared.finish()
            ended = true
            return ImportResult.Success(
                inserted = written.inserts.size,
                replaced = written.replacements.size,
                skipped = decisions.count { it == ConflictDecision.SKIP },
            )
        } catch (e: UnreadableAccountException) {
            return ImportResult.ExistingAccountUnreadable(e.accountId)
        } catch (e: SQLException) {
            return ImportResult.StorageFailed
        } catch (e: SecretCipherException) {
            return ImportResult.StorageFailed
        } catch (e: OutOfMemoryError) {
            return ImportResult.NotEnoughMemory
        } finally {
            if (!freshHandedOver) fresh?.let(::wipe)
            when {
                ended -> Unit
                // Maybe applied: never open again, so a retry cannot write the import twice.
                mayBeCommitted -> prepared.finish()
                else -> prepared.release()
            }
        }
    }
}

/**
 * A backup that was read and planned against the accounts on the device, waiting for the
 * user's decisions: show [plan] ([ImportPlan.conflicts] with [allowedDecisions] and
 * [ImportConflict.sharesCounterpart]), then call [BackupService.applyImport].
 *
 * It holds decrypted secrets: the imported accounts, and the device's accounts as read for
 * the plan. [BackupService.applyImport] wipes them once an apply wrote the import, or may
 * have; otherwise [close] it (for example when the user cancels), which wipes them. Closing
 * while an apply runs takes effect when the apply ends, so it never wipes secrets that are
 * being written. [toString] prints only counts.
 */
internal class PreparedImport internal constructor(
    internal val incoming: List<BackupEntry>,
    private val existing: List<ExistingAccount>,
) : Closeable {

    val plan: ImportPlan = planImport(existing, incoming)

    private val lock = Any()
    private var state = State.OPEN
    private var closeRequested = false

    /** False once it was applied, replaced by a new plan, or closed. */
    val isOpen: Boolean get() = synchronized(lock) { state != State.CLOSED && !closeRequested }

    /** Starts an apply; false if one is running or this was applied, replaced or closed. */
    internal fun begin(): Boolean = synchronized(lock) {
        if (state != State.OPEN) return false
        state = State.APPLYING
        true
    }

    /** Ends an apply that wrote nothing: open again, or wiped if [close] was called meanwhile. */
    internal fun release(): Unit = synchronized(lock) {
        check(state == State.APPLYING)
        if (closeRequested) closeNow() else state = State.OPEN
    }

    /** Ends an apply that wrote the import, or may have: wipes every secret and closes this for good. */
    internal fun finish(): Unit = synchronized(lock) {
        check(state == State.APPLYING)
        closeNow()
    }

    /**
     * During an apply that found the accounts changed: a new plan of the same imported accounts
     * against [current] (the rows read in the transaction). Changes nothing here, so if this
     * throws (out of memory) or the new plan is never handed over, this plan can still be
     * released. The new plan shares this plan's imported accounts: only [replaceWith] hands
     * them over, so a new plan that was not handed over must be dropped without closing it
     * (closing it would wipe this plan's imported accounts).
     */
    internal fun planAgain(current: List<ExistingAccount>): PreparedImport = synchronized(lock) {
        check(state == State.APPLYING)
        PreparedImport(incoming, current)
    }

    /**
     * Ends an apply that found the accounts changed by handing over to [next] (from
     * [planAgain]): closes this plan, wiping only its own existing accounts, and returns
     * true; [next] then owns the imported accounts and the rows it was planned against.
     * Returns false, wiping everything this plan holds, if [close] was called meanwhile; the
     * caller then wipes the rows [next] was planned against. Allocates nothing.
     */
    internal fun replaceWith(next: PreparedImport): Boolean = synchronized(lock) {
        check(state == State.APPLYING)
        check(next.incoming === incoming)
        if (closeRequested) {
            closeNow()
            return false
        }
        state = State.CLOSED
        wipe(existing)
        true
    }

    override fun close(): Unit = synchronized(lock) {
        when (state) {
            State.OPEN -> closeNow()
            State.APPLYING -> closeRequested = true
            State.CLOSED -> Unit
        }
    }

    private fun closeNow() {
        state = State.CLOSED
        for (i in incoming.indices) incoming[i].account.secret.fill(0)
        wipe(existing)
    }

    override fun toString(): String =
        "PreparedImport(accounts=${incoming.size}, conflicts=${plan.conflicts.size}, open=$isOpen)"

    private enum class State { OPEN, APPLYING, CLOSED }
}

/** Outcome of [BackupService.exportAccounts]. [toString] never names an account. */
internal sealed interface ExportResult {

    /** A backup of [accountCount] accounts was written to the stream and flushed. */
    class Success(val accountCount: Int) : ExportResult {
        override fun toString(): String = "Success(accountCount=$accountCount)"
    }

    /** The password does not meet the policy ([validatePassword]); nothing was read or written. */
    class PasswordRejected(val check: PasswordCheck) : ExportResult {
        override fun toString(): String = "PasswordRejected(check=$check)"
    }

    /**
     * Account [accountId] cannot be written to a backup, for [reason] (the first such
     * account; see [AccountRejectedException]). Nothing was written. Name the account from
     * the app's own list by its id.
     */
    class AccountRejected(val accountId: String, val reason: AccountRejection) : ExportResult {
        override fun toString(): String = "AccountRejected(reason=$reason)"
    }

    /**
     * Account [accountId] cannot be read: its secret does not decrypt (the key is gone, or
     * the data is damaged) or its row holds invalid values. Nothing was written.
     */
    class AccountUnreadable(val accountId: String) : ExportResult {
        override fun toString(): String = "AccountUnreadable"
    }

    /**
     * Not enough memory, in scrypt ([BackupNotEnoughMemoryException]) or anywhere else.
     * Nothing was written; it may work once more memory is free.
     */
    data object NotEnoughMemory : ExportResult

    /**
     * The output stream threw an `IOException` while writing or flushing. Part of the file
     * may have been written, so the caller should delete it.
     */
    data object WriteFailed : ExportResult

    /** The database could not be read. Nothing was written. */
    data object StorageFailed : ExportResult
}

/** Outcome of [BackupService.readBackup]. [toString] never names an account. */
internal sealed interface ImportReadResult {

    /** The backup was read and planned; see [PreparedImport]. */
    class Ready(val prepared: PreparedImport) : ImportReadResult {
        override fun toString(): String = "Ready($prepared)"
    }

    /**
     * The file was refused, for [reason]: any [BackupReadResult.Failure] except
     * [BackupReadResult.NotEnoughMemory], which is [NotEnoughMemory]. Show
     * [BackupReadResult.WrongPasswordOrDamaged] as "wrong password or damaged file" (the
     * format cannot tell them apart) and [BackupReadResult.ReadFailed] as a read error,
     * never as a wrong password.
     */
    class FileRejected(val reason: BackupReadResult.Failure) : ImportReadResult {
        override fun toString(): String = "FileRejected(reason=$reason)"
    }

    /** Not enough memory, in scrypt or anywhere else. Nothing was planned; the file may be fine. */
    data object NotEnoughMemory : ImportReadResult

    /**
     * Account [accountId] on the device cannot be read, so the import cannot be planned
     * safely (it could share a secret with an imported account).
     */
    class ExistingAccountUnreadable(val accountId: String) : ImportReadResult {
        override fun toString(): String = "ExistingAccountUnreadable"
    }

    /** The database could not be read. */
    data object StorageFailed : ImportReadResult
}

/** Outcome of [BackupService.applyImport]. [toString] never names an account. */
internal sealed interface ImportResult {

    /**
     * Everything was written in one transaction: [inserted] new accounts (clean ones and
     * Keep both), [replaced] existing accounts overwritten, [skipped] conflicts skipped.
     */
    class Success(val inserted: Int, val replaced: Int, val skipped: Int) : ImportResult {
        override fun toString(): String = "Success(inserted=$inserted, replaced=$replaced, skipped=$skipped)"
    }

    /**
     * The accounts changed since the plan in a way that changes what the user confirmed, so
     * nothing was written. The old plan is closed; [newPlan] holds the same imported accounts
     * planned against the accounts as they are now. Ask the user again.
     */
    class PlanChanged(val newPlan: PreparedImport) : ImportResult {
        override fun toString(): String = "PlanChanged($newPlan)"
    }

    /**
     * The decisions do not fit the plan: not exactly one per conflict, one that
     * [allowedDecisions] does not offer, or two [ConflictDecision.REPLACE] for the same
     * existing account. Nothing was written; the plan is still open.
     */
    data object DecisionsRejected : ImportResult

    /**
     * The plan was already applied (or may have been, see [BackupService.applyImport]),
     * replaced by a new plan, or closed, or an apply of it is running. Nothing was written.
     */
    data object PlanClosed : ImportResult

    /** Account [accountId] on the device cannot be read. Nothing was written; the plan is still open. */
    class ExistingAccountUnreadable(val accountId: String) : ImportResult {
        override fun toString(): String = "ExistingAccountUnreadable"
    }

    /**
     * The database or the Keystore failed. The transaction was rolled back, so nothing was
     * written; the plan is still open and the import can be retried. Exception: if the
     * database failed while committing, the import may have been written, and the plan is
     * closed (see [BackupService.applyImport]).
     */
    data object StorageFailed : ImportResult

    /**
     * Not enough memory. Nothing was written; the plan is still open. Exception: if it
     * happened after the transaction block had returned its writes, the import may have
     * been written, and the plan is closed (see [BackupService.applyImport]).
     */
    data object NotEnoughMemory : ImportResult
}

/**
 * Allocates the results that hand a plan to the caller. A seam so tests can make that
 * allocation run out of memory after the plan was made; [DefaultPlanResults] is the only
 * production implementation.
 */
internal interface PlanResults {
    fun ready(prepared: PreparedImport): ImportReadResult.Ready
    fun planChanged(newPlan: PreparedImport): ImportResult.PlanChanged
}

internal object DefaultPlanResults : PlanResults {
    override fun ready(prepared: PreparedImport): ImportReadResult.Ready = ImportReadResult.Ready(prepared)
    override fun planChanged(newPlan: PreparedImport): ImportResult.PlanChanged = ImportResult.PlanChanged(newPlan)
}

/**
 * The backup format as [BackupService] uses it: [exportBackup] and the `InputStream`
 * overload of [readBackup]. A seam so tests can use fast scrypt parameters.
 */
internal interface BackupCodec {
    fun export(accounts: List<BackupEntry>, password: CharArray): ByteArray
    fun read(input: InputStream, password: CharArray): BackupReadResult
}

/** The real format: default scrypt parameters and a fresh random salt and nonce per export. */
internal object DefaultBackupCodec : BackupCodec {
    override fun export(accounts: List<BackupEntry>, password: CharArray): ByteArray = exportBackup(accounts, password)
    override fun read(input: InputStream, password: CharArray): BackupReadResult = readBackup(input, password)
}

/** An index loop allocates nothing, so wiping still works after an OutOfMemoryError. */
private fun wipe(accounts: List<ExistingAccount>) {
    for (i in accounts.indices) accounts[i].account.secret.fill(0)
}

/**
 * The stored accounts as [ExistingAccount]s, sharing their secret arrays. If converting them
 * fails (out of memory), every secret read is wiped before the error propagates, so none is
 * dropped un-wiped.
 */
private fun List<StoredAccount>.toExistingAccounts(): List<ExistingAccount> =
    try {
        map { ExistingAccount(it.id, it.account) }
    } catch (t: Throwable) {
        for (i in indices) this[i].account.secret.fill(0)
        throw t
    }

private const val WIPED_CHAR = '\u0000'
