package io.github.lucateam_wt.tumbler.backup

import android.database.SQLException
import io.github.lucateam_wt.tumbler.backup.BackupFixtures.PASSWORD
import io.github.lucateam_wt.tumbler.backup.BackupFixtures.SECRET_A_BASE32
import io.github.lucateam_wt.tumbler.backup.BackupFixtures.SECRET_B_BASE32
import io.github.lucateam_wt.tumbler.backup.BackupFixtures.entry
import io.github.lucateam_wt.tumbler.backup.BackupFixtures.seal
import io.github.lucateam_wt.tumbler.backup.BackupFixtures.secretA
import io.github.lucateam_wt.tumbler.backup.BackupFixtures.secretB
import io.github.lucateam_wt.tumbler.backup.ConflictDecision.KEEP_BOTH
import io.github.lucateam_wt.tumbler.backup.ConflictDecision.REPLACE
import io.github.lucateam_wt.tumbler.backup.ConflictDecision.SKIP
import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipherException
import io.github.lucateam_wt.tumbler.domain.model.OtpAccount
import io.github.lucateam_wt.tumbler.domain.model.OtpType
import io.github.lucateam_wt.tumbler.domain.model.StoredAccount
import io.github.lucateam_wt.tumbler.domain.otp.OtpAlgorithm
import io.github.lucateam_wt.tumbler.domain.repository.AccountBackupStore
import io.github.lucateam_wt.tumbler.domain.repository.AccountBackupWriter
import io.github.lucateam_wt.tumbler.domain.repository.UnreadableAccountException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.concurrent.Executors
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

class BackupServiceTest {

    /** The real format with the fast test parameters and a random salt and nonce. */
    private class TestCodec : BackupCodec {
        val threads = mutableListOf<String>()
        val imported = mutableListOf<ByteArray>()
        var exportFailure: Throwable? = null
        var readResult: BackupReadResult? = null
        var passwordIntactAtCall: Boolean? = null

        override fun export(accounts: List<BackupEntry>, password: CharArray): ByteArray {
            threads += currentThreadName()
            passwordIntactAtCall = password.contentEquals(PASSWORD.toCharArray())
            exportFailure?.let { throw it }
            val random = SecureRandom()
            val salt = ByteArray(BackupFormat.SALT_LENGTH).also(random::nextBytes)
            val nonce = ByteArray(BackupFormat.NONCE_LENGTH).also(random::nextBytes)
            return sealBackup(accounts, password, salt, nonce, BackupFixtures.FAST)
        }

        override fun read(input: InputStream, password: CharArray): BackupReadResult {
            threads += currentThreadName()
            passwordIntactAtCall = password.contentEquals(PASSWORD.toCharArray())
            readResult?.let { return it }
            return readBackup(input, password).also { result ->
                if (result is BackupReadResult.Success) result.accounts.forEach { imported += it.account.secret }
            }
        }
    }

    /** Records what was written; can fail on write or flush. */
    private class TestOutput(private val failWrite: Boolean = false, private val failFlush: Boolean = false) : OutputStream() {
        val bytes = ByteArrayOutputStream()
        var flushed = false
        var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (failWrite) throw IOException("disk full")
            bytes.write(b, off, len)
        }

        override fun flush() {
            if (failFlush) throw IOException("disk full")
            flushed = true
        }

        override fun close() {
            closed = true
        }
    }

    private val workerName = "backup-worker"
    private val executor = Executors.newSingleThreadExecutor { Thread(it, workerName) }
    private val store = FakeAccountBackupStore()
    private val codec = TestCodec()
    private val service = BackupService(store, executor.asCoroutineDispatcher(), codec)

    private val githubAccount = OtpAccount("GitHub", "alice", OtpType.TOTP, secretA())
    private fun bankAccount(counter: Long = 3) =
        OtpAccount("Bank", "bob", OtpType.HOTP, secretB(), counter = counter)

    private val github = entry(issuer = "GitHub", accountName = "alice", createdAtMillis = 10)
    private fun bank(counter: Long = 5) = entry(
        issuer = "Bank", accountName = "bob", type = OtpType.HOTP, secret = secretB(), counter = counter, createdAtMillis = 20,
    )

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    private fun password() = PASSWORD.toCharArray()

    private inline fun <reified T> Any?.cast(): T {
        assertTrue("unexpected result $this", this is T)
        return this as T
    }

    private fun export(output: OutputStream = TestOutput(), password: CharArray = password()) =
        runBlocking { service.exportAccounts(password, output) }

    private fun readFile(file: ByteArray, password: CharArray = password()) =
        runBlocking { service.readBackup(ByteArrayInputStream(file), password) }

    private fun prepare(vararg entries: BackupEntry): PreparedImport =
        readFile(seal(entries.toList())).cast<ImportReadResult.Ready>().prepared

    private fun apply(prepared: PreparedImport, vararg decisions: ConflictDecision) =
        runBlocking { service.applyImport(prepared, decisions.toList()) }

    private fun assertHides(text: String, vararg hidden: String) {
        for (value in hidden.toList() + listOf(SECRET_A_BASE32, SECRET_B_BASE32, secretA().contentToString(), PASSWORD)) {
            assertFalse("'$text' contains '$value'", text.contains(value))
        }
    }

    // ---- export ----

    @Test
    fun export_writesEveryAccountWithItsCreationTimeAndFlushesWithoutClosing() {
        store.add(githubAccount, createdAtMillis = 100)
        store.add(bankAccount(counter = 7), createdAtMillis = 200)
        val output = TestOutput()

        val result = export(output).cast<ExportResult.Success>()

        assertEquals(2, result.accountCount)
        assertTrue(output.flushed)
        assertFalse(output.closed)
        val read = readBackup(output.bytes.toByteArray(), password()).cast<BackupReadResult.Success>()
        assertEquals(listOf(githubAccount, bankAccount(counter = 7)), read.accounts.map { it.account })
        assertEquals(listOf(100L, 200L), read.accounts.map { it.createdAtMillis })
    }

    @Test
    fun export_emptyStore_writesAnEmptyBackup() {
        val output = TestOutput()
        assertEquals(0, export(output).cast<ExportResult.Success>().accountCount)
        assertEquals(emptyList<BackupEntry>(), readBackup(output.bytes.toByteArray(), password()).cast<BackupReadResult.Success>().accounts)
    }

    @Test
    fun export_runsOnTheInjectedDispatcher() {
        store.add(githubAccount)
        export().cast<ExportResult.Success>()
        assertNotEquals(workerName, Thread.currentThread().name)
        assertEquals(listOf(workerName), codec.threads)
        assertEquals(listOf(workerName), store.threads)
    }

    @Test
    fun export_wipesThePasswordAndEveryDecryptedSecret() {
        store.add(githubAccount)
        store.add(bankAccount())
        val password = password()

        export(password = password).cast<ExportResult.Success>()

        assertEquals(true, codec.passwordIntactAtCall)
        assertTrue(password.all { it == '\u0000' })
        assertEquals(2, store.handedOut.size)
        assertTrue(store.handedOut.all { it.isWiped() })
        assertArrayEquals(secretA(), store.account(store.ids[0]).secret)
    }

    @Test
    fun export_passwordRejected_readsAndWritesNothingAndWipesThePassword() {
        store.add(githubAccount)
        val password = "short".toCharArray()
        val output = TestOutput()

        val result = export(output, password).cast<ExportResult.PasswordRejected>()

        assertTrue(result.check is PasswordCheck.TooShort)
        assertEquals(0, output.bytes.size())
        assertTrue(store.threads.isEmpty())
        assertTrue(codec.threads.isEmpty())
        assertTrue(password.all { it == '\u0000' })
    }

    @Test
    fun export_accountTheBackupCannotHold_namesItByIdAndWritesNothing() {
        store.add(githubAccount, createdAtMillis = 1)
        val badId = store.add(githubAccount.copy(issuer = "GitHub ", secret = secretA()), createdAtMillis = 2)
        val output = TestOutput()
        val password = password()

        val result = export(output, password).cast<ExportResult.AccountRejected>()

        assertEquals(badId, result.accountId)
        assertEquals(AccountRejection.LABEL_NOT_TRIMMED, result.reason)
        assertEquals(0, output.bytes.size())
        assertTrue(store.handedOut.all { it.isWiped() })
        assertTrue(password.all { it == '\u0000' })
        assertHides(result.toString(), badId, "GitHub", "alice")
    }

    @Test
    fun export_unreadableAccount_isReportedById() {
        store.readFailure = UnreadableAccountException("row-7", SecretCipherException("Decryption failed"))
        val output = TestOutput()

        val result = export(output).cast<ExportResult.AccountUnreadable>()

        assertEquals("row-7", result.accountId)
        assertEquals(0, output.bytes.size())
        assertHides(result.toString(), "row-7")
    }

    @Test
    fun export_databaseFailure_isStorageFailed() {
        store.readFailure = SQLException("disk I/O error")
        assertSame(ExportResult.StorageFailed, export())
    }

    @Test
    fun export_scryptOutOfMemory_isNotEnoughMemoryAndWipesTheSecrets() {
        store.add(githubAccount)
        codec.exportFailure = BackupNotEnoughMemoryException(OutOfMemoryError("test"))
        val output = TestOutput()

        assertSame(ExportResult.NotEnoughMemory, export(output))
        assertEquals(0, output.bytes.size())
        assertTrue(store.handedOut.single().isWiped())
    }

    @Test
    fun export_outOfMemoryOutsideScrypt_isNotEnoughMemory() {
        store.add(githubAccount)
        codec.exportFailure = OutOfMemoryError("test")
        assertSame(ExportResult.NotEnoughMemory, export())
        assertTrue(store.handedOut.single().isWiped())

        store.readFailure = OutOfMemoryError("test")
        assertSame(ExportResult.NotEnoughMemory, export())
    }

    @Test
    fun export_streamFailure_isWriteFailed() {
        store.add(githubAccount)
        assertSame(ExportResult.WriteFailed, export(TestOutput(failWrite = true)))
        assertSame(ExportResult.WriteFailed, export(TestOutput(failFlush = true)))
        assertTrue(store.handedOut.all { it.isWiped() })
    }

    @Test
    fun export_unexpectedFailure_propagatesButStillWipesPasswordAndSecrets() {
        store.add(githubAccount)
        codec.exportFailure = IllegalStateException("Backup encryption failed")
        val password = password()

        assertThrows(IllegalStateException::class.java) { export(password = password) }

        assertTrue(password.all { it == '\u0000' })
        assertTrue(store.handedOut.single().isWiped())
    }

    // ---- read ----

    @Test
    fun read_plansTheBackupAgainstTheAccountsOnTheDevice() {
        val bankId = store.add(bankAccount(counter = 3))
        val password = password()

        val prepared = readFile(seal(listOf(github, bank(counter = 5))), password).cast<ImportReadResult.Ready>().prepared

        assertTrue(password.all { it == '\u0000' })
        assertTrue(prepared.isOpen)
        assertEquals(listOf(workerName), codec.threads)
        assertEquals(listOf(workerName), store.threads)
        val conflict = prepared.plan.conflicts.single()
        assertEquals(bankId, conflict.existing.id)
        assertEquals(ConflictReason.SAME_LABEL_AND_SECRET, conflict.reason)
        assertEquals(setOf(SKIP, REPLACE), allowedDecisions(conflict))
        assertEquals(2, prepared.incoming.size)
        assertHides(ImportReadResult.Ready(prepared).toString(), bankId, "GitHub", "alice", "Bank", "bob")
    }

    @Test
    fun read_fileFailures_areFileRejectedAndWipeThePassword() {
        val file = seal(listOf(github))
        val wrong = "wrong horse battery staple".toCharArray()
        assertSame(BackupReadResult.WrongPasswordOrDamaged, readFile(file, wrong).cast<ImportReadResult.FileRejected>().reason)
        assertTrue(wrong.all { it == '\u0000' })

        assertSame(BackupReadResult.NotABackup, readFile(ByteArray(64)).cast<ImportReadResult.FileRejected>().reason)

        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("unreadable")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("unreadable")
        }
        val failed = runBlocking { service.readBackup(broken, password()) }
        assertSame(BackupReadResult.ReadFailed, failed.cast<ImportReadResult.FileRejected>().reason)
        assertTrue(store.threads.isEmpty())
    }

    @Test
    fun read_scryptOutOfMemory_isNotEnoughMemoryNotAFileFailure() {
        codec.readResult = BackupReadResult.NotEnoughMemory
        assertSame(ImportReadResult.NotEnoughMemory, readFile(seal(listOf(github))))
    }

    @Test
    fun read_failuresAfterDecryption_wipeTheImportedSecrets() {
        val file = seal(listOf(github, bank()))
        val failures = listOf(
            UnreadableAccountException("row-3", SecretCipherException("Decryption failed")) to ImportReadResult.ExistingAccountUnreadable::class.java,
            SQLException("locked") to ImportReadResult.StorageFailed::class.java,
            OutOfMemoryError("test") to ImportReadResult.NotEnoughMemory::class.java,
        )
        for ((failure, expected) in failures) {
            codec.imported.clear()
            store.readFailure = failure

            val result = readFile(file)

            assertTrue("$failure -> $result", expected.isInstance(result))
            assertEquals(2, codec.imported.size)
            assertTrue("$failure", codec.imported.all { it.isWiped() })
        }
        val unreadable = run {
            store.readFailure = UnreadableAccountException("row-3", SecretCipherException("Decryption failed"))
            readFile(file).cast<ImportReadResult.ExistingAccountUnreadable>()
        }
        assertEquals("row-3", unreadable.accountId)
        assertHides(unreadable.toString(), "row-3")
    }

    // ---- apply ----

    @Test
    fun apply_intoAnEmptyStore_insertsEveryAccountInOneTransactionAndWipesTheImport() {
        val prepared = prepare(github, bank())

        val result = apply(prepared).cast<ImportResult.Success>()

        assertEquals(listOf(2, 0, 0), listOf(result.inserted, result.replaced, result.skipped))
        assertEquals(listOf(github.account, bank().account), store.accounts())
        assertEquals(listOf(10L, 20L), store.ids.map(store::createdAt))
        assertEquals(1, store.transactions)
        assertEquals(1, store.commits)
        assertFalse(prepared.isOpen)
        assertTrue(codec.imported.all { it.isWiped() })
        assertTrue(store.handedOut.all { it.isWiped() })
        assertEquals(listOf(workerName), store.threads.distinct())
    }

    @Test
    fun apply_skip_keepsTheExistingAccountAndImportsTheRest() {
        val bankId = store.add(bankAccount(counter = 3), createdAtMillis = 1)
        val prepared = prepare(github, bank(counter = 5))

        val result = apply(prepared, SKIP).cast<ImportResult.Success>()

        assertEquals(listOf(1, 0, 1), listOf(result.inserted, result.replaced, result.skipped))
        assertEquals(bankAccount(counter = 3), store.account(bankId))
        assertEquals(listOf(bankAccount(counter = 3), github.account), store.accounts())
    }

    @Test
    fun apply_keepBoth_addsASecondAccount() {
        val githubId = store.add(githubAccount, createdAtMillis = 1)
        val prepared = prepare(github)

        val result = apply(prepared, KEEP_BOTH).cast<ImportResult.Success>()

        assertEquals(listOf(1, 0, 0), listOf(result.inserted, result.replaced, result.skipped))
        assertEquals(2, store.ids.size)
        assertEquals(githubAccount, store.account(githubId))
        assertEquals(listOf(githubAccount, github.account), store.accounts())
    }

    @Test
    fun apply_replace_overwritesEveryFieldAndKeepsTheIdAndCreationTime() {
        val githubId = store.add(githubAccount, createdAtMillis = 1)
        val imported = entry(issuer = "GitHub", accountName = "alice", algorithm = OtpAlgorithm.SHA512, digits = 8, period = 60)
        val prepared = prepare(imported)

        val result = apply(prepared, REPLACE).cast<ImportResult.Success>()

        assertEquals(listOf(0, 1, 0), listOf(result.inserted, result.replaced, result.skipped))
        assertEquals(listOf(githubId), store.ids)
        assertEquals(imported.account, store.account(githubId))
        assertEquals(1L, store.createdAt(githubId))
    }

    @Test
    fun apply_replaceSameHotpSecret_usesTheCounterStoredAtWriteTime() {
        // The counter moved after the plan: past the imported one, then less far.
        for ((movedTo, expected) in listOf(9L to 9L, 4L to 5L)) {
            val store = FakeAccountBackupStore()
            val service = BackupService(store, executor.asCoroutineDispatcher(), codec)
            val bankId = store.add(bankAccount(counter = 3))
            val prepared = runBlocking { service.readBackup(ByteArrayInputStream(seal(listOf(bank(counter = 5)))), password()) }
                .cast<ImportReadResult.Ready>().prepared
            store.setCounter(bankId, movedTo)

            runBlocking { service.applyImport(prepared, listOf(REPLACE)) }.cast<ImportResult.Success>()

            assertEquals("moved to $movedTo", expected, store.account(bankId).counter)
        }
    }

    @Test
    fun apply_replaceSameHotpSecret_counterIncrementedJustBeforeTheTransaction_isNotMovedBack() {
        val bankId = store.add(bankAccount(counter = 3))
        val prepared = prepare(bank(counter = 5))
        store.beforeTransaction = { store.setCounter(bankId, 7) }

        apply(prepared, REPLACE).cast<ImportResult.Success>()

        assertEquals(7L, store.account(bankId).counter)
    }

    @Test
    fun apply_replaceHotpWithANewSecret_writesTheImportedCounter() {
        val bankId = store.add(bankAccount(counter = 50))
        val reEnrolled = entry(issuer = "Bank", accountName = "bob", type = OtpType.HOTP, secret = secretA(), counter = 5)
        val prepared = prepare(reEnrolled)
        assertEquals(ConflictReason.SAME_LABEL, prepared.plan.conflicts.single().reason)

        apply(prepared, REPLACE).cast<ImportResult.Success>()

        assertEquals(reEnrolled.account, store.account(bankId))
    }

    @Test
    fun apply_rowAddedAfterThePlan_abortsWithANewPlanAndWritesNothing() {
        val prepared = prepare(github, bank())
        assertTrue(prepared.plan.conflicts.isEmpty())
        val githubId = store.add(githubAccount, createdAtMillis = 1)

        val changed = apply(prepared).cast<ImportResult.PlanChanged>()

        assertEquals(listOf(githubAccount), store.accounts())
        assertEquals(0, store.writes)
        assertFalse(prepared.isOpen)
        assertSame(ImportResult.PlanClosed, apply(prepared))
        val next = changed.newPlan
        assertEquals(githubId, next.plan.conflicts.single().existing.id)

        val result = apply(next, SKIP).cast<ImportResult.Success>()
        assertEquals(listOf(1, 0, 1), listOf(result.inserted, result.replaced, result.skipped))
        assertEquals(listOf(githubAccount, bank().account), store.accounts())
    }

    @Test
    fun apply_counterpartDeletedAfterThePlan_abortsAndWritesNothing() {
        val githubId = store.add(githubAccount)
        val prepared = prepare(github)
        store.delete(githubId)

        val changed = apply(prepared, REPLACE).cast<ImportResult.PlanChanged>()

        assertTrue(store.ids.isEmpty())
        assertTrue(changed.newPlan.plan.conflicts.isEmpty())
    }

    @Test
    fun apply_counterpartChangedAfterThePlan_abortsAndWritesNothing() {
        val githubId = store.add(githubAccount)
        val prepared = prepare(github)
        store.update(githubId) { it.copy(secret = secretB()) }

        val changed = apply(prepared, REPLACE).cast<ImportResult.PlanChanged>()

        assertArrayEquals(secretB(), store.account(githubId).secret)
        assertEquals(ConflictReason.SAME_LABEL, changed.newPlan.plan.conflicts.single().reason)
    }

    @Test
    fun apply_rowAddedJustBeforeTheTransaction_abortsAndWritesNothing() {
        val prepared = prepare(github)
        store.beforeTransaction = { store.add(githubAccount, createdAtMillis = 1) }

        val changed = apply(prepared).cast<ImportResult.PlanChanged>()

        assertEquals(listOf(githubAccount), store.accounts())
        assertEquals(1, changed.newPlan.plan.conflicts.size)
    }

    @Test
    fun apply_planChanged_handsTheImportAndTheNewRowsToTheNewPlanAndWipesTheOldRows() {
        val bankId = store.add(bankAccount())
        val prepared = prepare(bank())
        val planTimeRows = store.handedOut.toList()
        store.add(githubAccount, createdAtMillis = 1)
        store.update(bankId) { it.copy(issuer = "Bank (new)") }

        val next = apply(prepared, REPLACE).cast<ImportResult.PlanChanged>().newPlan

        assertTrue(planTimeRows.all { it.isWiped() })
        assertTrue(next.isOpen)
        assertArrayEquals(secretB(), next.incoming.single().account.secret)
        assertTrue(next.plan.conflicts.all { !it.existing.account.secret.isWiped() })
        next.close()
        assertTrue(codec.imported.all { it.isWiped() })
        assertTrue(store.handedOut.all { it.isWiped() })
    }

    @Test
    fun apply_unrelatedChangesAfterThePlan_doNotAbort() {
        val bankId = store.add(bankAccount(counter = 3))
        val prepared = prepare(bank(counter = 5), github)
        store.setCounter(bankId, 4)
        store.add(OtpAccount("Other", "carol", OtpType.TOTP, secretB().copyOf(10)), createdAtMillis = 1)

        val result = apply(prepared, REPLACE).cast<ImportResult.Success>()

        assertEquals(listOf(1, 1, 0), listOf(result.inserted, result.replaced, result.skipped))
    }

    @Test
    fun apply_decisionsThatDoNotFitThePlan_areRejectedBeforeAnyTransaction() {
        val githubId = store.add(githubAccount)
        store.add(bankAccount())
        val alias = entry(issuer = "GitHub", accountName = "alice (work)", createdAtMillis = 30)
        val prepared = prepare(github, bank(), alias)
        val conflicts = prepared.plan.conflicts
        assertEquals(listOf(githubId, store.ids[1], githubId), conflicts.map { it.existing.id })
        assertTrue(conflicts[2].sharesCounterpart)

        val rejected = listOf(
            emptyList(),
            listOf(SKIP, SKIP),
            listOf(SKIP, SKIP, SKIP, SKIP),
            listOf(SKIP, KEEP_BOTH, SKIP), // KEEP_BOTH is not offered for the same HOTP secret
            listOf(REPLACE, SKIP, REPLACE), // two replacements of the same account
        )
        for (decisions in rejected) {
            assertSame("$decisions", ImportResult.DecisionsRejected, runBlocking { service.applyImport(prepared, decisions) })
        }
        assertEquals(0, store.transactions)
        assertTrue(prepared.isOpen)

        val result = apply(prepared, REPLACE, SKIP, KEEP_BOTH).cast<ImportResult.Success>()
        assertEquals(listOf(1, 1, 1), listOf(result.inserted, result.replaced, result.skipped))
    }

    @Test
    fun apply_closedOrAppliedPlan_isPlanClosed() {
        val applied = prepare(github)
        apply(applied).cast<ImportResult.Success>()
        assertSame(ImportResult.PlanClosed, apply(applied))

        val closed = prepare(bank())
        closed.close()
        assertSame(ImportResult.PlanClosed, apply(closed))
        assertEquals(1, store.transactions)
    }

    @Test
    fun apply_secondApplyWhileTheFirstRuns_isPlanClosed() {
        val prepared = prepare(github)
        var nested: ImportResult? = null
        store.beforeTransaction = {
            store.beforeTransaction = null
            nested = service.applyImport(prepared, emptyList())
        }

        apply(prepared).cast<ImportResult.Success>()

        assertSame(ImportResult.PlanClosed, nested)
        assertEquals(1, store.ids.size)
    }

    @Test
    fun apply_writeFailure_rollsBackEverythingAndKeepsThePlanOpenForARetry() {
        val bankId = store.add(bankAccount(counter = 3))
        val prepared = prepare(bank(counter = 5), github)
        val failures = listOf(
            SQLException("disk full") to ImportResult.StorageFailed,
            SecretCipherException("Encryption failed") to ImportResult.StorageFailed,
            OutOfMemoryError("test") to ImportResult.NotEnoughMemory,
        )
        for ((failure, expected) in failures) {
            store.writeFailure = failure
            store.writesBeforeFailure = 1 // the replacement is written, the insert fails

            assertSame("$failure", expected, apply(prepared, REPLACE))

            assertEquals(listOf(bankAccount(counter = 3)), store.accounts())
            assertTrue(prepared.isOpen)
            assertTrue(prepared.incoming.none { it.account.secret.isWiped() })
        }
        store.writeFailure = null

        apply(prepared, REPLACE).cast<ImportResult.Success>()
        assertEquals(bankAccount(counter = 5), store.account(bankId))
        assertEquals(2, store.ids.size)
    }

    @Test
    fun apply_existingAccountUnreadable_isReportedByIdAndKeepsThePlanOpen() {
        val prepared = prepare(github)
        store.transactionReadFailure = UnreadableAccountException("row-9", SecretCipherException("Decryption failed"))

        val result = apply(prepared).cast<ImportResult.ExistingAccountUnreadable>()

        assertEquals("row-9", result.accountId)
        assertHides(result.toString(), "row-9")
        assertTrue(prepared.isOpen)
        assertTrue(store.ids.isEmpty())
    }

    @Test
    fun apply_closeDuringTheApply_writesIntactSecretsThenWipesEverything() {
        val prepared = prepare(github)
        store.beforeTransaction = { prepared.close() }

        apply(prepared).cast<ImportResult.Success>()

        assertArrayEquals(secretA(), store.accounts().single().secret)
        assertFalse(prepared.isOpen)
        assertTrue(codec.imported.all { it.isWiped() })
    }

    @Test
    fun apply_closeDuringAnApplyThatFindsThePlanChanged_isPlanClosedAndWipesEverything() {
        val prepared = prepare(github)
        store.beforeTransaction = {
            prepared.close()
            store.add(githubAccount)
        }

        assertSame(ImportResult.PlanClosed, apply(prepared))

        assertTrue(codec.imported.all { it.isWiped() })
        assertTrue(store.handedOut.all { it.isWiped() })
    }

    // ---- apply: cancellation and failures around the commit ----

    /** Runs applyImport on [service] in a job of its own, which [hook] gets first (to cancel it from the store); returns what it threw. */
    private fun applyInJob(
        prepared: PreparedImport,
        vararg decisions: ConflictDecision,
        service: BackupService = this.service,
        hook: (Job) -> Unit,
    ): Throwable? =
        runBlocking {
            var thrown: Throwable? = null
            val job = launch(start = CoroutineStart.LAZY) {
                try {
                    service.applyImport(prepared, decisions.toList())
                } catch (t: Throwable) {
                    thrown = t
                    throw t
                }
            }
            hook(job)
            job.start()
            job.join()
            thrown
        }

    /** A store holding [githubAccount] (created at 1), its service, and a plan to import [github] there. */
    private fun keepBothCase(): Triple<FakeAccountBackupStore, BackupService, PreparedImport> {
        val store = FakeAccountBackupStore()
        val service = BackupService(store, executor.asCoroutineDispatcher(), codec)
        store.add(githubAccount, createdAtMillis = 1)
        val read = runBlocking { service.readBackup(ByteArrayInputStream(seal(listOf(github))), password()) }
        return Triple(store, service, read.cast<ImportReadResult.Ready>().prepared)
    }

    /** Keep both wrote [github] once, and [prepared] is closed and wiped, so a retry writes nothing. */
    private fun assertWrittenOnceAndClosed(store: FakeAccountBackupStore, service: BackupService, prepared: PreparedImport) {
        assertEquals(listOf(githubAccount, github.account), store.accounts())
        assertFalse(prepared.isOpen)
        assertTrue(prepared.incoming.all { it.account.secret.isWiped() })
        assertTrue(store.handedOut.all { it.isWiped() })

        assertSame(ImportResult.PlanClosed, runBlocking { service.applyImport(prepared, listOf(KEEP_BOTH)) })

        assertEquals(1, store.transactions)
        assertEquals(1, store.writes)
        assertEquals(listOf(githubAccount, github.account), store.accounts())
    }

    @Test
    fun apply_storeThrowsCancellationBeforeTheBlockRuns_releasesThePlanForARetry() {
        store.add(githubAccount, createdAtMillis = 1)
        val prepared = prepare(github)
        // Only the fake can do this: a CancellationException from the store before the block has
        // returned its writes, so nothing may have been committed. Room cannot once applyImport has
        // started, since the service waits for its transaction (see the Room-shaped store test).
        store.beforeTransaction = { throw CancellationException("thrown by the store before the block") }

        assertThrows(CancellationException::class.java) { apply(prepared, KEEP_BOTH) }

        assertEquals(listOf(githubAccount), store.accounts())
        assertEquals(0, store.writes)
        assertTrue(prepared.isOpen)
        assertTrue(prepared.incoming.none { it.account.secret.isWiped() })
        store.beforeTransaction = null
        assertEquals(1, apply(prepared, KEEP_BOTH).cast<ImportResult.Success>().inserted)
        assertEquals(listOf(githubAccount, github.account), store.accounts())
    }

    @Test
    fun apply_cancelledRightAfterTheCommit_closesAndWipesThePlanSoARetryWritesNothing() {
        store.add(githubAccount, createdAtMillis = 1)
        val prepared = prepare(github)

        val thrown = applyInJob(prepared, KEEP_BOTH) { job ->
            // Cancelled as the transaction commits; the store checks for it as it returns (under the
            // service's NonCancellable it finds none, and withContext rethrows the cancellation).
            store.afterCommit = {
                job.cancel()
                currentCoroutineContext().ensureActive()
            }
        }

        assertTrue("$thrown", thrown is CancellationException)
        assertEquals(1, store.commits)
        assertWrittenOnceAndClosed(store, service, prepared)
    }

    @Test
    fun apply_failureAfterTheCommit_closesAndWipesThePlanInsteadOfReleasingIt() {
        val failures = listOf(
            OutOfMemoryError("test") to ImportResult.NotEnoughMemory,
            SQLException("disk I/O error") to ImportResult.StorageFailed,
            CancellationException("cancelled after the commit") to null,
        )
        for ((failure, expected) in failures) {
            val (store, service, prepared) = keepBothCase()
            store.afterCommit = { throw failure }

            if (expected != null) {
                assertSame("$failure", expected, runBlocking { service.applyImport(prepared, listOf(KEEP_BOTH)) })
            } else {
                assertThrows(CancellationException::class.java) { runBlocking { service.applyImport(prepared, listOf(KEEP_BOTH)) } }
            }

            assertWrittenOnceAndClosed(store, service, prepared)
        }
    }

    @Test
    fun apply_commitFailsAndRollsBack_closesThePlanAnyway() {
        val (store, service, prepared) = keepBothCase()
        store.commitFailure = SQLException("disk I/O error")

        assertSame(ImportResult.StorageFailed, runBlocking { service.applyImport(prepared, listOf(KEEP_BOTH)) })

        // Nothing was written here, but a failed commit may have written: the plan is closed either way.
        assertEquals(listOf(githubAccount), store.accounts())
        assertEquals(0, store.commits)
        assertFalse(prepared.isOpen)
        assertTrue(prepared.incoming.all { it.account.secret.isWiped() })
        assertTrue(store.handedOut.all { it.isWiped() })
        assertSame(ImportResult.PlanClosed, runBlocking { service.applyImport(prepared, listOf(KEEP_BOTH)) })
        assertEquals(1, store.transactions)
    }

    /**
     * Shaped like Room 2.8.5's `withTransaction`, unlike [FakeAccountBackupStore], which runs
     * the block on the caller's coroutine: the caller waits in `suspendCancellableCoroutine`
     * while the block runs in `runBlocking` (the caller's context without its dispatcher) on
     * a transaction thread of its own. A cancelled caller is therefore resumed at once, while
     * the block keeps running and then commits. Rows, commits and rollbacks are [rows]'s;
     * [onInsert] runs on the transaction thread at the start of every insert.
     */
    private class TransactionThreadStore(private val rows: FakeAccountBackupStore) : AccountBackupStore, Closeable {
        private val transactionThread = Executors.newSingleThreadExecutor { Thread(it, "transaction") }

        @Volatile
        var onInsert: (() -> Unit)? = null

        override suspend fun readAll(): List<StoredAccount> = rows.readAll()

        override suspend fun <R> writeTransaction(block: suspend AccountBackupWriter.() -> R): R =
            suspendCancellableCoroutine { continuation ->
                transactionThread.execute {
                    try {
                        runBlocking(continuation.context.minusKey(ContinuationInterceptor)) {
                            continuation.resume(rows.writeTransaction { HookedWriter(this).block() })
                        }
                    } catch (t: Throwable) {
                        continuation.cancel(t)
                    }
                }
            }

        override fun close() {
            transactionThread.shutdownNow()
        }

        private inner class HookedWriter(private val writer: AccountBackupWriter) : AccountBackupWriter by writer {
            override suspend fun insert(account: OtpAccount, createdAtMillis: Long): String {
                onInsert?.invoke()
                return writer.insert(account, createdAtMillis)
            }
        }
    }

    @Test
    fun apply_cancelledInsideTheLastInsertOnARoomShapedStore_stillWritesOnceAndClosesThePlan() {
        val rows = FakeAccountBackupStore()
        rows.add(githubAccount, createdAtMillis = 1)
        TransactionThreadStore(rows).use { store ->
            val service = BackupService(store, executor.asCoroutineDispatcher(), codec)
            val prepared = runBlocking { service.readBackup(ByteArrayInputStream(seal(listOf(github, bank()))), password()) }
                .cast<ImportReadResult.Ready>().prepared
            var inserts = 0

            // Keep both for github and a clean bank: two inserts, cancelled at the start of the second.
            val thrown = applyInJob(prepared, KEEP_BOTH, service = service) { job ->
                store.onInsert = {
                    if (++inserts == 2) {
                        job.cancel()
                        // A caller this resumed runs on the service's single worker: let it run to its end first.
                        executor.submit(Runnable {}).get()
                    }
                }
            }

            assertTrue("$thrown", thrown is CancellationException)
            val written = listOf(githubAccount, github.account, bank().account)
            assertEquals(written, rows.accounts())
            assertEquals(1, rows.commits)
            assertFalse(prepared.isOpen)
            assertTrue(codec.imported.all { it.isWiped() })
            assertTrue(rows.handedOut.all { it.isWiped() })

            assertSame(ImportResult.PlanClosed, runBlocking { service.applyImport(prepared, listOf(KEEP_BOTH)) })

            assertEquals(1, rows.transactions)
            assertEquals(2, rows.writes)
            assertEquals(written, rows.accounts())
        }
    }

    @Test
    fun apply_storageFailedInsideTheTransaction_rollsBackAndKeepsThePlanOpenForARetry() {
        store.add(githubAccount, createdAtMillis = 1)
        val prepared = prepare(github)
        store.writeFailure = SQLException("disk full")

        assertSame(ImportResult.StorageFailed, apply(prepared, KEEP_BOTH))

        assertEquals(listOf(githubAccount), store.accounts())
        assertTrue(prepared.isOpen)
        assertTrue(prepared.incoming.none { it.account.secret.isWiped() })
        store.writeFailure = null
        assertEquals(1, apply(prepared, KEEP_BOTH).cast<ImportResult.Success>().inserted)
        assertEquals(listOf(githubAccount, github.account), store.accounts())
    }

    @Test
    fun apply_cancelledDuringATransactionThatFindsThePlanChanged_makesNoNewPlanAndKeepsThisOne() {
        val prepared = prepare(github)

        val thrown = applyInJob(prepared) { job ->
            store.beforeTransaction = {
                store.add(githubAccount, createdAtMillis = 1)
                job.cancel()
            }
        }

        assertTrue("$thrown", thrown is CancellationException)
        assertEquals(listOf(githubAccount), store.accounts())
        assertEquals(0, store.writes)
        assertTrue(prepared.isOpen)
        assertTrue(prepared.incoming.none { it.account.secret.isWiped() })
        assertTrue(store.handedOut.single().isWiped()) // the rows read in the transaction
    }

    // ---- a result the cancelled caller never receives ----

    /** Queues what is dispatched to it, after [onDispatch]; [runAll] runs the queue on the calling thread. */
    private class QueueDispatcher(private val onDispatch: (CoroutineContext) -> Unit = {}) : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            onDispatch(context)
            tasks.addLast(block)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    /**
     * Runs [call] on a service whose dispatcher is a queue, from a caller that is cancelled
     * exactly when the service hands its result back: after the service's work has ended and
     * before the caller resumes, so `withContext` drops that result. Everything runs on this
     * thread, in order. Returns what [call] threw.
     */
    private fun callCancelledOnReturn(call: suspend BackupService.() -> Unit): Throwable? {
        val worker = QueueDispatcher()
        val caller = QueueDispatcher(onDispatch = { context -> context[Job]?.cancel() })
        val service = BackupService(store, worker, codec)
        var thrown: Throwable? = null
        val job = CoroutineScope(caller).launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                service.call()
            } catch (t: Throwable) {
                thrown = t
                throw t
            }
        }
        worker.runAll() // the whole call, up to dispatching its result back to the caller
        assertTrue(job.isCancelled)
        caller.runAll()
        assertTrue(job.isCompleted)
        return thrown
    }

    @Test
    fun read_planDroppedByACancellationAfterItWasMade_isWiped() {
        store.add(bankAccount())
        val file = seal(listOf(github, bank()))
        val password = password()

        val thrown = callCancelledOnReturn { readBackup(ByteArrayInputStream(file), password) }

        assertTrue("$thrown", thrown is CancellationException)
        assertTrue(password.all { it == '\u0000' })
        assertEquals(2, codec.imported.size)
        assertTrue(codec.imported.all { it.isWiped() })
        assertEquals(1, store.handedOut.size) // the plan was made against the account on the device
        assertTrue(store.handedOut.all { it.isWiped() })
    }

    @Test
    fun apply_newPlanDroppedByACancellationAfterItWasMade_isWiped() {
        val prepared = prepare(github)
        store.add(githubAccount, createdAtMillis = 1)

        val thrown = callCancelledOnReturn { applyImport(prepared, emptyList()) }

        assertTrue("$thrown", thrown is CancellationException)
        assertEquals(1, store.transactions)
        assertEquals(0, store.writes)
        assertEquals(listOf(githubAccount), store.accounts())
        assertFalse(prepared.isOpen) // replaced by the new plan, which the caller never received
        assertTrue(codec.imported.all { it.isWiped() })
        assertEquals(1, store.handedOut.size) // the row read in the transaction, held by the new plan
        assertTrue(store.handedOut.all { it.isWiped() })
    }

    @Test
    fun preparedImport_close_wipesEverySecretItHolds() {
        store.add(githubAccount)
        val prepared = prepare(github, bank())
        assertTrue(store.handedOut.none { it.isWiped() })

        prepared.close()
        prepared.close()

        assertFalse(prepared.isOpen)
        assertEquals(2, codec.imported.size)
        assertTrue(codec.imported.all { it.isWiped() })
        assertTrue(store.handedOut.all { it.isWiped() })
        assertArrayEquals(secretA(), store.accounts().single().secret)
    }

    @Test
    fun results_toStringNamesNoAccount() {
        val texts = listOf(
            ExportResult.Success(2),
            ExportResult.AccountRejected("row-1", AccountRejection.LABEL_TOO_LONG),
            ExportResult.AccountUnreadable("row-1"),
            ImportReadResult.FileRejected(BackupReadResult.WrongPasswordOrDamaged),
            ImportReadResult.ExistingAccountUnreadable("row-1"),
            ImportResult.Success(1, 2, 3),
            ImportResult.ExistingAccountUnreadable("row-1"),
            ImportResult.PlanChanged(prepare(github)),
        ).map { it.toString() }
        for (text in texts) assertHides(text, "row-1", "GitHub", "alice")
    }

    @Test
    fun defaultCodec_roundTripsWithTheDefaultParametersThroughTheStreamReader() {
        val file = DefaultBackupCodec.export(listOf(github), password())
        assertEquals(ScryptParams.DEFAULT.log2N, file[10].toInt())
        val read = DefaultBackupCodec.read(ByteArrayInputStream(file), password()).cast<BackupReadResult.Success>()
        assertEquals(listOf(github), read.accounts)
    }
}
