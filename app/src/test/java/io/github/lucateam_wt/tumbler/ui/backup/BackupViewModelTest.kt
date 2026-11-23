package io.github.lucateam_wt.tumbler.ui.backup

import android.database.SQLException
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import io.github.lucateam_wt.tumbler.backup.BackupCodec
import io.github.lucateam_wt.tumbler.backup.BackupEntry
import io.github.lucateam_wt.tumbler.backup.BackupFixtures
import io.github.lucateam_wt.tumbler.backup.BackupFixtures.PASSWORD
import io.github.lucateam_wt.tumbler.backup.BackupFixtures.entry
import io.github.lucateam_wt.tumbler.backup.BackupFixtures.longSecret
import io.github.lucateam_wt.tumbler.backup.BackupFixtures.secretA
import io.github.lucateam_wt.tumbler.backup.BackupFixtures.secretB
import io.github.lucateam_wt.tumbler.backup.BackupFormat
import io.github.lucateam_wt.tumbler.backup.BackupReadResult
import io.github.lucateam_wt.tumbler.backup.BackupService
import io.github.lucateam_wt.tumbler.backup.ConflictDecision.KEEP_BOTH
import io.github.lucateam_wt.tumbler.backup.ConflictDecision.REPLACE
import io.github.lucateam_wt.tumbler.backup.ConflictDecision.SKIP
import io.github.lucateam_wt.tumbler.backup.ConflictReason
import io.github.lucateam_wt.tumbler.backup.FakeAccountBackupStore
import io.github.lucateam_wt.tumbler.backup.ImportResult
import io.github.lucateam_wt.tumbler.backup.MAX_PASSWORD_CHARACTERS
import io.github.lucateam_wt.tumbler.backup.MAX_PASSWORD_INPUT_UTF16_UNITS
import io.github.lucateam_wt.tumbler.backup.PreparedImport
import io.github.lucateam_wt.tumbler.backup.isWiped
import io.github.lucateam_wt.tumbler.backup.readBackup
import io.github.lucateam_wt.tumbler.backup.sealBackup
import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipherException
import io.github.lucateam_wt.tumbler.domain.model.AccountSummary
import io.github.lucateam_wt.tumbler.domain.model.OtpAccount
import io.github.lucateam_wt.tumbler.domain.model.OtpType
import io.github.lucateam_wt.tumbler.domain.otp.OtpAlgorithm
import io.github.lucateam_wt.tumbler.domain.repository.UnreadableAccountException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {

    /** The real format with the fast test parameters; remembers every imported secret array. */
    private class FastCodec : BackupCodec {
        val imported = mutableListOf<ByteArray>()

        override fun export(accounts: List<BackupEntry>, password: CharArray): ByteArray {
            val random = SecureRandom()
            val salt = ByteArray(BackupFormat.SALT_LENGTH).also(random::nextBytes)
            val nonce = ByteArray(BackupFormat.NONCE_LENGTH).also(random::nextBytes)
            return sealBackup(accounts, password, salt, nonce, BackupFixtures.FAST)
        }

        override fun read(input: InputStream, password: CharArray): BackupReadResult =
            readBackup(input, password).also { result ->
                if (result is BackupReadResult.Success) result.accounts.forEach { imported += it.account.secret }
            }
    }

    /**
     * A document from the picker whose streams can fail; [onClose] runs when a stream is
     * closed. [content] is what it holds before anything is written: empty for a file the
     * picker created, a backup for an existing file the picker returned. Records every open
     * for writing (each one truncates, like "wt"), every look into it and every delete.
     */
    private class FakeBackupFile(val content: ByteArray = ByteArray(0)) : BackupFile {
        var openFailure: Exception? = null

        /** Bytes the output accepts before it throws, or -1 for no limit. */
        var writeFailsAfter = -1
        var closeFails = false
        var deleteRefused = false
        var deleteCalls = 0

        /** Calls of [openOutput], including ones that failed. */
        var openOutputCalls = 0

        /** True once [openOutput] succeeded: [content] is gone. */
        var truncated = false
        var openInputCalls = 0
        var emptyChecks = 0
        var closed = false
        var onClose: (() -> Unit)? = null
        var onWrite: (() -> Unit)? = null

        /** Runs when [openInput] is called, before [openFailure] is thrown. */
        var onOpenInput: (() -> Unit)? = null
        val written = ByteArrayOutputStream()

        /** Neither opened for writing nor looked into nor deleted. */
        val untouched: Boolean get() = openOutputCalls == 0 && emptyChecks == 0 && deleteCalls == 0

        override fun openOutput(): OutputStream {
            openOutputCalls++
            openFailure?.let { throw it }
            truncated = true
            return object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (writeFailsAfter >= 0 && written.size() + len > writeFailsAfter) {
                        written.write(b, off, writeFailsAfter - written.size())
                        throw IOException("disk full")
                    }
                    written.write(b, off, len)
                    onWrite?.invoke()
                }

                override fun close() {
                    closed = true
                    onClose?.invoke()
                    if (closeFails) throw IOException("the provider could not commit the file")
                }
            }
        }

        override fun openInput(): InputStream {
            openInputCalls++
            onOpenInput?.invoke()
            openFailure?.let { throw it }
            return object : ByteArrayInputStream(content) {
                override fun close() {
                    closed = true
                    onClose?.invoke()
                }
            }
        }

        override fun delete(): Boolean {
            deleteCalls++
            return !deleteRefused
        }

        override fun isKnownEmpty(): Boolean {
            emptyChecks++
            return if (truncated) written.size() == 0 else content.isEmpty()
        }
    }

    private val main = UnconfinedTestDispatcher()
    private val store = FakeAccountBackupStore()
    private val codec = FastCodec()

    /** Every array a password field was copied into. */
    private val copies = mutableListOf<CharArray>()
    private var summaries: List<AccountSummary> = emptyList()
    private val owner = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }

    private val github = OtpAccount("GitHub", "alice", OtpType.TOTP, secretA())
    private fun bank(counter: Long) = OtpAccount("Bank", "bob", OtpType.HOTP, secretB(), counter = counter)

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(io: CoroutineDispatcher = main, serviceDispatcher: CoroutineDispatcher = main): BackupViewModel {
        val viewModel = BackupViewModel(
            service = BackupService(store, serviceDispatcher, codec),
            accountSummaries = { summaries },
            ioDispatcher = io,
            chars = { text -> text.toCharArrayCopy().also { copies += it } },
        )
        // Owned by a store, so a test can clear it like the system does.
        @Suppress("UNCHECKED_CAST")
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = viewModel as T
        }
        // A key of its own, or the provider would hand back the first view model again.
        return ViewModelProvider(owner, factory)["backup-${created++}", BackupViewModel::class.java]
    }

    private var created = 0

    private fun clear() = owner.viewModelStore.clear()

    private fun PasswordInput.type(text: String) = state.setTextAndPlaceCursorAtEnd(text)

    private fun assertCopiesWiped() {
        assertTrue("no password was copied", copies.isNotEmpty())
        copies.forEachIndexed { index, copy -> assertTrue("password copy $index not wiped", copy.all { it == '\u0000' }) }
    }

    private fun backupFile(vararg entries: BackupEntry, password: String = PASSWORD) =
        FakeBackupFile(BackupFixtures.seal(entries.toList(), password))

    private inline fun <reified T> Any?.cast(): T {
        assertTrue("unexpected $this", this is T)
        return this as T
    }

    private fun BackupViewModel.enterExportPassword(password: String = PASSWORD, confirmation: String = password): Boolean {
        startExport()
        exportPassword.type(password)
        exportConfirmation.type(confirmation)
        return submitExportPassword()
    }

    private fun BackupViewModel.export(file: FakeBackupFile): BackupMessage? {
        assertTrue(enterExportPassword())
        exportFileChosen(file)
        return message.value
    }

    private fun BackupViewModel.openBackup(file: FakeBackupFile, password: String = PASSWORD) {
        importFileChosen(file)
        importPassword.type(password)
        submitImportPassword()
    }

    private fun BackupViewModel.review(): PlanReview = state.value.cast<BackupUiState.Reviewing>().review

    private fun BackupViewModel.confirm() {
        continueImport()
        state.value.cast<BackupUiState.Confirming>()
        confirmImport()
    }

    /** Whether the view model asked for the list to be reloaded since the last call. */
    private fun BackupViewModel.reloadRequested(): Boolean = runBlocking {
        withTimeoutOrNull(100) { reloadRequests.first() } != null
    }

    // ---- export: the password dialog ----

    @Test
    fun export_startsWithEmptyFieldsAndOnlyFromIdle() {
        val viewModel = viewModel()
        viewModel.exportPassword.type("left over")

        viewModel.startExport()

        assertSame(BackupUiState.ExportPassword, viewModel.state.value)
        assertTrue(viewModel.exportPassword.isEmpty)
        viewModel.startExport()
        viewModel.importFileChosen(FakeBackupFile())
        assertSame(BackupUiState.ExportPassword, viewModel.state.value)
    }

    @Test
    fun export_passwordShorterThan12Characters_isRefusedAndItsCopiesWiped() {
        val viewModel = viewModel()

        assertFalse(viewModel.enterExportPassword("a".repeat(11)))

        assertSame(BackupUiState.ExportPassword, viewModel.state.value)
        assertCopiesWiped()
        // The fields keep what was typed, so the user can fix it.
        assertFalse(viewModel.exportPassword.isEmpty)
    }

    @Test
    fun export_mismatchedConfirmation_isRefused() {
        val viewModel = viewModel()

        assertFalse(viewModel.enterExportPassword(PASSWORD, "$PASSWORD!"))

        assertSame(BackupUiState.ExportPassword, viewModel.state.value)
        assertCopiesWiped()
    }

    @Test
    fun export_passwordAboveTheCap_isRefused() {
        val viewModel = viewModel()
        assertFalse(viewModel.enterExportPassword("a".repeat(MAX_PASSWORD_CHARACTERS + 1)))
        assertFalse(viewModel.enterExportPassword("a".repeat(MAX_PASSWORD_INPUT_UTF16_UNITS + 1)))
        assertCopiesWiped()
    }

    @Test
    fun export_passwordsAtTheLimits_areAccepted() {
        for (password in listOf("a".repeat(12), "😀".repeat(12), "a".repeat(MAX_PASSWORD_CHARACTERS))) {
            val viewModel = viewModel()
            assertTrue(password, viewModel.enterExportPassword(password))
            assertSame(BackupUiState.ExportChoosingFile, viewModel.state.value)
            viewModel.cancelExport()
        }
        assertCopiesWiped()
    }

    @Test
    fun export_acceptedPassword_clearsTheFieldsAndWaitsForTheFile() {
        val viewModel = viewModel()

        assertTrue(viewModel.enterExportPassword())

        assertSame(BackupUiState.ExportChoosingFile, viewModel.state.value)
        assertTrue(viewModel.exportPassword.isEmpty)
        assertTrue(viewModel.exportConfirmation.isEmpty)
        // The confirmation copy is wiped at once; the password copy waits for the file.
        assertEquals(2, copies.size)
        assertTrue(copies[1].all { it == '\u0000' })
        assertArrayEquals(PASSWORD.toCharArray(), copies[0])
    }

    @Test
    fun export_pickerCancelled_wipesThePassword() {
        val viewModel = viewModel()
        viewModel.enterExportPassword()

        viewModel.exportFileChosen(null)

        assertSame(BackupUiState.Idle, viewModel.state.value)
        assertCopiesWiped()
        assertNull(viewModel.message.value)
    }

    @Test
    fun export_cancelWhileChoosingTheFile_wipesThePassword() {
        val viewModel = viewModel()
        viewModel.enterExportPassword()

        viewModel.cancelExport()

        assertSame(BackupUiState.Idle, viewModel.state.value)
        assertCopiesWiped()
    }

    @Test
    fun export_pickerUnavailable_wipesThePasswordAndSaysSo() {
        val viewModel = viewModel()
        viewModel.enterExportPassword()

        viewModel.exportPickerUnavailable()

        assertCopiesWiped()
        assertEquals(BackupMessage.ExportFailed(ExportFailure.NO_FILE_PICKER, PartialFile.NONE), viewModel.message.value)
    }

    @Test
    fun export_cancelInTheDialog_clearsTheFields() {
        val viewModel = viewModel()
        viewModel.startExport()
        viewModel.exportPassword.type(PASSWORD)
        viewModel.exportConfirmation.type(PASSWORD)

        viewModel.cancelExport()

        assertSame(BackupUiState.Idle, viewModel.state.value)
        assertTrue(viewModel.exportPassword.isEmpty)
        assertTrue(viewModel.exportConfirmation.isEmpty)
    }

    @Test
    fun export_clearedWhileChoosingTheFile_wipesThePasswordAndTheFields() {
        val viewModel = viewModel()
        viewModel.enterExportPassword()
        viewModel.importPassword.type("typed")

        clear()

        assertCopiesWiped()
        assertTrue(viewModel.importPassword.isEmpty)
    }

    // ---- export: writing the file ----

    @Test
    fun export_success_writesABackupOfEveryAccountAndKeepsTheFile() {
        store.add(github)
        store.add(bank(counter = 7))
        val viewModel = viewModel()
        val file = FakeBackupFile()

        val message = viewModel.export(file)

        assertEquals(BackupMessage.Exported(2), message)
        assertSame(BackupUiState.Idle, viewModel.state.value)
        assertEquals(1, file.openOutputCalls)
        assertTrue(file.closed)
        assertEquals(0, file.deleteCalls)
        val read = BackupFixtures.read(file.written.toByteArray()).cast<BackupReadResult.Success>()
        assertEquals(listOf(github, bank(counter = 7)), read.accounts.map { it.account })
        assertCopiesWiped()
        assertTrue(store.handedOut.all { it.isWiped() })
    }

    @Test
    fun export_streamFailsMidway_deletesThePartialFile() {
        store.add(github)
        val viewModel = viewModel()
        val file = FakeBackupFile().apply { writeFailsAfter = 40 }

        val message = viewModel.export(file)

        assertEquals(40, file.written.size()) // part of a backup reached the file
        assertEquals(BackupMessage.ExportFailed(ExportFailure.WRITE_FAILED, PartialFile.DELETED), message)
        assertEquals(1, file.deleteCalls)
        assertTrue(file.closed)
        assertCopiesWiped()
    }

    @Test
    fun export_closeFails_isAFailedWriteAndDeletesTheFile() {
        store.add(github)
        val viewModel = viewModel()
        val file = FakeBackupFile().apply { closeFails = true }

        val message = viewModel.export(file)

        assertEquals(BackupMessage.ExportFailed(ExportFailure.WRITE_FAILED, PartialFile.DELETED), message)
        assertEquals(1, file.deleteCalls)
    }

    @Test
    fun export_deleteRefused_tellsTheUserToDeleteTheFile() {
        store.add(github)
        val viewModel = viewModel()
        val file = FakeBackupFile().apply {
            writeFailsAfter = 10
            deleteRefused = true
        }

        val message = viewModel.export(file)

        assertEquals(BackupMessage.ExportFailed(ExportFailure.WRITE_FAILED, PartialFile.NOT_DELETED), message)
    }

    /**
     * SAF cannot replace a file atomically: once an existing file the picker returned is
     * opened for writing, its earlier content is gone, and a write that fails halfway leaves
     * a broken file, which is deleted.
     */
    @Test
    fun export_failureAfterTheFirstWrite_deletesTheFileEvenIfItHeldSomethingBefore() {
        store.add(github)
        val viewModel = viewModel()
        val file = backupFile(entry("Old", "backup")).apply { writeFailsAfter = 40 }

        val message = viewModel.export(file)

        assertTrue(file.truncated)
        assertEquals(BackupMessage.ExportFailed(ExportFailure.WRITE_FAILED, PartialFile.DELETED), message)
        assertEquals(1, file.deleteCalls)
        assertEquals("never asks whether a file it wrote is empty", 0, file.emptyChecks)
    }

    @Test
    fun export_fileCannotBeOpened_isDeletedOnlyIfEmpty() {
        val failures = listOf(
            FileNotFoundException("gone"),
            SecurityException("permission revoked"),
            IllegalArgumentException("unknown document"),
            UnsupportedOperationException("read-only provider"),
        )
        store.add(github)
        for (failure in failures) {
            val created = FakeBackupFile().apply { openFailure = failure }
            val existing = backupFile(entry("Old", "backup")).apply { openFailure = failure }

            val deleted = viewModel().export(created)
            val kept = viewModel().export(existing)

            assertEquals("$failure", BackupMessage.ExportFailed(ExportFailure.FILE_NOT_OPENED, PartialFile.DELETED), deleted)
            assertEquals(1, created.deleteCalls)
            assertEquals("$failure", BackupMessage.ExportFailed(ExportFailure.FILE_NOT_OPENED, PartialFile.UNTOUCHED), kept)
            assertEquals("$failure: a file that holds something is never deleted", 0, existing.deleteCalls)
            assertEquals(1, existing.openOutputCalls)
            assertFalse(existing.truncated)
        }
        assertCopiesWiped()
    }

    @Test
    fun export_unreadableAccount_isNotNamedAndLeavesTheFileUntouched() {
        store.add(github)
        store.readFailure = UnreadableAccountException("row-0", SecretCipherException("Decryption failed"))
        summaries = listOf(AccountSummary("row-0", "GitHub", "alice", OtpType.TOTP, OtpAlgorithm.SHA1, 6, 30, 0))
        val viewModel = viewModel()
        val file = FakeBackupFile()

        val message = viewModel.export(file)

        assertEquals(BackupMessage.ExportFailed(ExportFailure.ACCOUNT_UNREADABLE, PartialFile.UNTOUCHED, account = null), message)
        assertTrue(file.untouched)
    }

    @Test
    fun export_accountTheBackupCannotHold_isNamedFromTheList() {
        val long = OtpAccount("a".repeat(1025), "alice", OtpType.TOTP, secretA())
        val id = store.add(long)
        summaries = listOf(AccountSummary(id, long.issuer, "alice", OtpType.TOTP, OtpAlgorithm.SHA1, 6, 30, 0))
        val viewModel = viewModel()

        val message = viewModel.export(FakeBackupFile())

        assertEquals(
            BackupMessage.ExportFailed(ExportFailure.ACCOUNT_LABEL_TOO_LONG, PartialFile.UNTOUCHED, AccountLabel(long.issuer, "alice")),
            message,
        )
        assertFalse(message.toString().contains("alice"))
    }

    @Test
    fun export_storageFailure_isReportedAndLeavesTheFileUntouched() {
        store.readFailure = SQLException("locked")
        val viewModel = viewModel()
        val file = FakeBackupFile()

        assertEquals(BackupMessage.ExportFailed(ExportFailure.STORAGE_FAILED, PartialFile.UNTOUCHED), viewModel.export(file))
        assertTrue(file.untouched)
    }

    @Test
    fun export_unexpectedFailure_leavesTheFileUntouchedAndWipesThePassword() {
        store.readFailure = IllegalStateException("unexpected")
        val viewModel = viewModel()
        val file = FakeBackupFile()

        assertEquals(BackupMessage.ExportFailed(ExportFailure.UNEXPECTED, PartialFile.UNTOUCHED), viewModel.export(file))
        assertTrue(file.untouched)
        assertCopiesWiped()
    }

    /** The picker may return a file that already holds a backup: no failure before the write may touch it. */
    @Test
    fun export_failureBeforeTheFirstWrite_leavesAnExistingFileUntouched() {
        val failures = listOf<Pair<Throwable, ExportFailure>>(
            SQLException("locked") to ExportFailure.STORAGE_FAILED,
            UnreadableAccountException("row-0", SecretCipherException("Decryption failed")) to ExportFailure.ACCOUNT_UNREADABLE,
            OutOfMemoryError("test") to ExportFailure.NOT_ENOUGH_MEMORY,
            IllegalStateException("unexpected") to ExportFailure.UNEXPECTED,
        )
        for ((failure, reason) in failures) {
            val store = FakeAccountBackupStore().apply {
                add(github)
                readFailure = failure
            }
            val viewModel = BackupViewModel(BackupService(store, main, codec), { emptyList() }, main)
            val file = backupFile(entry("Old", "backup"))

            assertEquals("$failure", BackupMessage.ExportFailed(reason, PartialFile.UNTOUCHED), viewModel.export(file))
            assertTrue("$failure", file.untouched)
            assertFalse(file.truncated)
        }
    }

    @Test
    fun export_fileArrivingAfterTheAppRestarted_isDeletedIfEmpty() {
        // A new view model (as after process death) receives the picker's result: the password is gone.
        val viewModel = viewModel()
        val file = FakeBackupFile()

        viewModel.exportFileChosen(file)

        assertEquals(1, file.deleteCalls)
        assertEquals(0, file.openOutputCalls)
        assertEquals(BackupMessage.ExportFailed(ExportFailure.INTERRUPTED, PartialFile.DELETED), viewModel.message.value)
        assertSame(BackupUiState.Idle, viewModel.state.value)
    }

    @Test
    fun export_fileWithContentArrivingAfterTheAppRestarted_isNeverDeleted() {
        val viewModel = viewModel()
        val file = backupFile(entry("Old", "backup"))

        viewModel.exportFileChosen(file)

        assertEquals(0, file.deleteCalls)
        assertEquals(0, file.openOutputCalls)
        assertFalse(file.truncated)
        assertEquals(BackupMessage.ExportFailed(ExportFailure.INTERRUPTED, PartialFile.UNTOUCHED), viewModel.message.value)
    }

    @Test
    fun export_clearedBeforeTheBackupIsWritten_leavesTheFileUntouchedAndWipesThePassword() {
        store.add(github)
        val service = StandardTestDispatcher(main.scheduler)
        val viewModel = viewModel(serviceDispatcher = service)
        val file = backupFile(entry("Old", "backup"))
        viewModel.enterExportPassword()
        viewModel.exportFileChosen(file)
        assertSame(BackupUiState.Exporting, viewModel.state.value)

        clear() // the activity finished: viewModelScope is cancelled before the service ran
        main.scheduler.advanceUntilIdle()

        assertTrue(file.untouched)
        assertCopiesWiped()
    }

    @Test
    fun export_cancelledWhileTheServiceWrites_deletesTheFile() {
        store.add(github)
        val viewModel = viewModel()
        // Cancelled while the bytes are being written: the service's result is dropped, so the
        // user never learns whether the file is complete, and it must not stay behind.
        val file = FakeBackupFile().apply { onWrite = { clear() } }
        viewModel.enterExportPassword()

        viewModel.exportFileChosen(file)

        assertEquals(1, file.deleteCalls)
        assertTrue(file.closed)
        assertCopiesWiped()
    }

    // ---- import: reading ----

    @Test
    fun import_intoAnEmptyDevice_showsNewAccountsThenWritesThemAfterConfirmation() {
        val viewModel = viewModel()
        val file = backupFile(entry("GitHub", "alice"), entry("Bank", "bob", OtpType.HOTP, secretB(), counter = 4))

        viewModel.openBackup(file)

        assertTrue(viewModel.importPassword.isEmpty)
        assertTrue(file.closed)
        val review = viewModel.review()
        assertEquals(PlanReview(newAccounts = 2, identical = 0, conflicts = emptyList()), review)
        assertEquals(0, store.writes) // nothing before the confirmation

        viewModel.continueImport()
        assertEquals(2, viewModel.state.value.cast<BackupUiState.Confirming>().review.toAdd)
        viewModel.confirmImport()

        assertEquals(BackupMessage.Imported(added = 2, replaced = 0, skipped = 0), viewModel.message.value)
        assertSame(BackupUiState.Idle, viewModel.state.value)
        assertEquals(2, store.ids.size)
        assertTrue(viewModel.reloadRequested())
        assertCopiesWiped()
        assertTrue(codec.imported.all { it.isWiped() })
    }

    @Test
    fun import_wrongPasswordAndDamagedFile_lookTheSameAndKeepTheFileForAnotherTry() {
        val file = backupFile(entry())
        val damagedBytes = file.content.copyOf().also { it[it.lastIndex - 3] = (it[it.lastIndex - 3].toInt() xor 1).toByte() }

        val wrong = viewModel()
        wrong.openBackup(file, password = "wrong horse battery staple")
        val damaged = viewModel()
        damaged.openBackup(FakeBackupFile(damagedBytes))

        assertEquals(BackupUiState.ImportPassword(wrongPasswordOrDamaged = true), wrong.state.value)
        assertEquals(wrong.state.value, damaged.state.value)
        assertEquals(wrong.message.value, damaged.message.value)
        assertNull(wrong.message.value)

        wrong.importPassword.type(PASSWORD)
        wrong.submitImportPassword()
        assertEquals(1, wrong.review().newAccounts)
        assertCopiesWiped()
    }

    @Test
    fun import_notABackup_endsWithItsOwnMessage() {
        val viewModel = viewModel()

        viewModel.openBackup(FakeBackupFile(ByteArray(64)))

        assertEquals(BackupMessage.ImportFailed(ImportFailure.NOT_A_BACKUP), viewModel.message.value)
        assertSame(BackupUiState.Idle, viewModel.state.value)
    }

    @Test
    fun import_fileCannotBeOpened_wipesThePassword() {
        val viewModel = viewModel()
        val file = backupFile(entry()).apply { openFailure = SecurityException("permission revoked") }

        viewModel.openBackup(file)

        assertEquals(BackupMessage.ImportFailed(ImportFailure.FILE_NOT_OPENED), viewModel.message.value)
        assertCopiesWiped() // the service never ran
    }

    @Test
    fun import_existingAccountUnreadable_isNotNamed() {
        store.add(github)
        store.readFailure = UnreadableAccountException("row-0", SecretCipherException("Decryption failed"))
        val viewModel = viewModel()

        viewModel.openBackup(backupFile(entry()))

        assertEquals(BackupMessage.ImportFailed(ImportFailure.EXISTING_ACCOUNT_UNREADABLE), viewModel.message.value)
        assertTrue(codec.imported.all { it.isWiped() })
    }

    @Test
    fun import_cancelWhileReading_dropsTheResultAndWipesEverything() {
        val io = StandardTestDispatcher(main.scheduler)
        val viewModel = viewModel(io = io)
        viewModel.openBackup(backupFile(entry()))
        assertSame(BackupUiState.ImportReading, viewModel.state.value)

        viewModel.cancelImport()
        main.scheduler.advanceUntilIdle()

        assertSame(BackupUiState.Idle, viewModel.state.value)
        assertCopiesWiped()
        assertTrue(codec.imported.all { it.isWiped() })
    }

    @Test
    fun import_cancelledJustAfterThePlanWasMade_closesThePlan() {
        store.add(github)
        val io = StandardTestDispatcher(main.scheduler)
        val viewModel = viewModel(io = io)
        // The input is closed after the service returned the plan, inside the view model's own
        // withContext, which then drops the plan: the view model must close it.
        val file = backupFile(entry("GitHub", "alice"), entry("Bank", "bob")).apply { onClose = { viewModel.cancelImport() } }

        viewModel.openBackup(file)
        main.scheduler.advanceUntilIdle()

        assertSame(BackupUiState.Idle, viewModel.state.value)
        assertEquals(2, codec.imported.size)
        assertTrue("imported secrets", codec.imported.all { it.isWiped() })
        assertTrue("rows the plan was made against", store.handedOut.all { it.isWiped() })
    }

    // ---- import: the review ----

    @Test
    fun import_conflictsStartAsSkipAndOfferOnlyTheAllowedDecisions() {
        // Distinct secrets (all made from the public test secrets), so only the intended pairs match.
        store.add(github.copy(secret = longSecret(25))) // same label, new secret: all three decisions
        store.add(bank(counter = 3)) // same HOTP secret: no Keep both
        store.add(OtpAccount("Mail", "carol", OtpType.TOTP, longSecret(30))) // identical: counted, skipped
        val viewModel = viewModel()

        viewModel.openBackup(
            backupFile(
                entry("GitHub", "alice"),
                entry("Bank", "bob", OtpType.HOTP, secretB(), counter = 9),
                entry("Mail", "carol", secret = longSecret(30)),
                entry("New", "dave", secret = longSecret(35)),
            ),
        )

        val review = viewModel.review()
        assertEquals(1, review.newAccounts)
        assertEquals(1, review.identical)
        assertEquals(2, review.conflicts.size)
        assertTrue(review.conflicts.all { it.decision == SKIP })
        val (label, hotp) = review.conflicts
        assertEquals(listOf(SKIP, KEEP_BOTH, REPLACE), label.choices)
        assertEquals(listOf(SKIP, REPLACE), hotp.choices)
        assertEquals(HotpCounters(stored = 3, imported = 9, replaceKeepsLarger = true), hotp.counters)

        viewModel.setDecision(hotp.index, KEEP_BOTH) // not offered: ignored
        assertEquals(SKIP, viewModel.review().conflicts[1].decision)
        viewModel.setDecision(hotp.index, REPLACE)
        viewModel.setDecision(label.index, KEEP_BOTH)
        assertEquals(listOf(KEEP_BOTH, REPLACE), viewModel.review().conflicts.map { it.decision })
        viewModel.confirm()

        assertEquals(BackupMessage.Imported(added = 2, replaced = 1, skipped = 1), viewModel.message.value)
        assertEquals(9L, store.accounts().single { it.issuer == "Bank" }.counter)
        assertEquals(5, store.ids.size)
    }

    @Test
    fun import_secondReplaceOfTheSameAccount_isIgnored() {
        store.add(github)
        val viewModel = viewModel()
        // Both imported accounts match the one on the device (same label, then same secret).
        viewModel.openBackup(backupFile(entry("GitHub", "alice", secret = secretB()), entry("Other", "x", secret = secretA())))
        val rows = viewModel.review().conflicts
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.sharesCounterpart })

        viewModel.setDecision(rows[0].index, REPLACE)
        viewModel.setDecision(rows[1].index, REPLACE)

        val after = viewModel.review().conflicts
        assertEquals(listOf(REPLACE, SKIP), after.map { it.decision })
        assertTrue(after[1].replaceTaken)
    }

    /**
     * A row's index is its position in the plan, not on the screen: the identical conflict is
     * only counted, so from the second row on the two differ. Each decision must reach the
     * conflict it was made for, and only that one.
     */
    @Test
    fun import_eachDecisionAppliesToItsOwnConflict() {
        val githubId = store.add(github.copy(secret = longSecret(25)), createdAtMillis = 11) // same label
        val mailId = store.add(OtpAccount("Mail", "carol", OtpType.TOTP, longSecret(30))) // identical
        val bankId = store.add(bank(counter = 3), createdAtMillis = 33) // same HOTP secret and label
        val shopId = store.add(OtpAccount("Shop", "eve", OtpType.TOTP, longSecret(40))) // same label
        val viewModel = viewModel()
        viewModel.openBackup(
            backupFile(
                entry("GitHub", "alice"),
                entry("Mail", "carol", secret = longSecret(30)),
                entry("Bank", "bob", OtpType.HOTP, secretB(), counter = 9),
                entry("Shop", "eve", secret = longSecret(45)),
                entry("New", "dave", secret = longSecret(35)),
            ),
        )
        val rows = viewModel.review().conflicts
        assertEquals(listOf(0, 2, 3), rows.map { it.index })
        assertEquals(listOf("GitHub", "Bank", "Shop"), rows.map { it.imported.issuer })
        assertEquals(listOf(listOf(SKIP, KEEP_BOTH, REPLACE), listOf(SKIP, REPLACE), listOf(SKIP, KEEP_BOTH, REPLACE)), rows.map { it.choices })

        viewModel.setDecision(rows[1].index, REPLACE)
        viewModel.setDecision(rows[2].index, KEEP_BOTH)
        viewModel.setDecision(rows[0].index, REPLACE)
        // By screen position, "Bank" would be 1: the identical conflict, which takes no decision.
        viewModel.setDecision(1, REPLACE)
        viewModel.setDecision(1, KEEP_BOTH)

        val decided = viewModel.review()
        assertEquals(listOf(0 to REPLACE, 2 to REPLACE, 3 to KEEP_BOTH), decided.conflicts.map { it.index to it.decision })
        assertEquals(1, decided.identical)
        viewModel.confirm()

        assertEquals(BackupMessage.Imported(added = 2, replaced = 2, skipped = 1), viewModel.message.value)
        assertEquals(6, store.ids.size)
        // GitHub: replaced by the backup's account, keeping its id and creation time.
        assertArrayEquals(secretA(), store.account(githubId).secret)
        assertEquals(11L, store.createdAt(githubId))
        // Mail (identical) and Shop (Keep both) are untouched; Bank is replaced with the larger counter.
        assertArrayEquals(longSecret(30), store.account(mailId).secret)
        assertArrayEquals(longSecret(40), store.account(shopId).secret)
        assertEquals(9L, store.account(bankId).counter)
        assertEquals(33L, store.createdAt(bankId))
        // Keep both added the backup's Shop next to the device's; New was added.
        assertEquals(1, store.accounts().count { it.issuer == "Shop" && it.secret.contentEquals(longSecret(45)) })
        assertEquals(1, store.accounts().count { it.issuer == "New" })
        assertEquals(1, store.accounts().count { it.issuer == "GitHub" })
    }

    @Test
    fun import_backFromTheConfirmation_keepsTheDecisions() {
        store.add(github.copy(secret = secretB()))
        val viewModel = viewModel()
        viewModel.openBackup(backupFile(entry("GitHub", "alice")))
        viewModel.setDecision(0, KEEP_BOTH)

        viewModel.continueImport()
        viewModel.backToReview()

        assertEquals(KEEP_BOTH, viewModel.review().conflicts.single().decision)
        assertEquals(0, store.writes)
    }

    @Test
    fun import_cancelAtEveryStepBeforeTheConfirmation_writesNothingAndWipesThePlan() {
        for (step in 0..3) {
            val store = FakeAccountBackupStore().apply { add(github) }
            val codec = FastCodec()
            val viewModel = BackupViewModel(BackupService(store, main, codec), { emptyList() }, main)
            viewModel.importFileChosen(backupFile(entry("GitHub", "alice"), entry("Bank", "bob")))
            if (step >= 1) {
                viewModel.importPassword.type(PASSWORD)
                viewModel.submitImportPassword()
            }
            if (step >= 2) viewModel.setDecision(0, REPLACE)
            if (step >= 3) viewModel.continueImport()

            viewModel.cancelImport()

            assertSame("step $step", BackupUiState.Idle, viewModel.state.value)
            assertEquals(0, store.writes)
            assertTrue("step $step", codec.imported.all { it.isWiped() })
            assertTrue("step $step", store.handedOut.all { it.isWiped() })
            assertTrue(viewModel.importPassword.isEmpty)
        }
    }

    @Test
    fun import_clearedDuringTheReview_closesThePlan() {
        store.add(github)
        val viewModel = viewModel()
        viewModel.openBackup(backupFile(entry("GitHub", "alice")))

        clear()

        assertTrue(codec.imported.all { it.isWiped() })
        assertTrue(store.handedOut.all { it.isWiped() })
    }

    @Test
    fun import_clearedBeforeTheApplyRuns_writesNothingAndWipesThePlan() {
        val service = StandardTestDispatcher(main.scheduler)
        val viewModel = viewModel(serviceDispatcher = service)
        viewModel.openBackup(backupFile(entry()))
        main.scheduler.advanceUntilIdle()
        viewModel.continueImport()
        viewModel.confirmImport()
        assertTrue(viewModel.state.value is BackupUiState.Importing)

        clear()
        main.scheduler.advanceUntilIdle()

        assertEquals(0, store.writes)
        assertTrue(codec.imported.all { it.isWiped() })
    }

    // ---- import: every result of the apply ----

    @Test
    fun import_planChanged_showsTheNewPlanForANewConfirmationAndNeverAppliesIt() {
        store.add(github.copy(secret = secretB()))
        val viewModel = viewModel()
        viewModel.openBackup(backupFile(entry("GitHub", "alice"), entry("Bank", "bob")))
        viewModel.setDecision(0, REPLACE)
        store.beforeTransaction = {
            store.beforeTransaction = null
            store.add(OtpAccount("Bank", "bob", OtpType.TOTP, secretB()))
        }

        viewModel.confirm()

        val changed = viewModel.state.value.cast<BackupUiState.Reviewing>()
        assertEquals(ReviewNotice.PLAN_CHANGED, changed.notice)
        assertEquals(2, changed.review.conflicts.size)
        assertTrue("decisions reset, Replace not kept", changed.review.conflicts.all { it.decision == SKIP })
        assertEquals(1, store.transactions)
        assertEquals(0, store.writes)
        assertNull(viewModel.message.value)

        viewModel.confirm()
        assertEquals(BackupMessage.Imported(added = 0, replaced = 0, skipped = 2), viewModel.message.value)
        assertTrue(codec.imported.all { it.isWiped() })
    }

    @Test
    fun import_storageFailureThatWroteNothing_offersARetryWithTheSameDecisions() {
        store.add(github.copy(secret = secretB()))
        val viewModel = viewModel()
        viewModel.openBackup(backupFile(entry("GitHub", "alice"), entry("Bank", "bob")))
        viewModel.setDecision(0, KEEP_BOTH)
        store.writeFailure = SQLException("disk full")

        viewModel.confirm()

        val retry = viewModel.state.value.cast<BackupUiState.Reviewing>()
        assertEquals(ReviewNotice.STORAGE_FAILED, retry.notice)
        assertEquals(KEEP_BOTH, retry.review.conflicts.single().decision)
        assertEquals(1, store.ids.size)
        assertFalse(viewModel.reloadRequested())

        store.writeFailure = null
        viewModel.confirm()
        assertEquals(BackupMessage.Imported(added = 2, replaced = 0, skipped = 0), viewModel.message.value)
    }

    @Test
    fun import_outOfMemoryThatWroteNothing_offersARetry() {
        val viewModel = viewModel()
        viewModel.openBackup(backupFile(entry()))
        store.writeFailure = OutOfMemoryError("test")

        viewModel.confirm()

        assertEquals(ReviewNotice.NOT_ENOUGH_MEMORY, viewModel.state.value.cast<BackupUiState.Reviewing>().notice)
    }

    @Test
    fun import_failureWhileCommitting_saysItMayHaveBeenAppliedAndReloads() {
        for (failure in listOf(SQLException("commit failed"), OutOfMemoryError("test"))) {
            val store = FakeAccountBackupStore().apply { commitFailure = failure }
            val codec = FastCodec()
            val viewModel = BackupViewModel(BackupService(store, main, codec), { emptyList() }, main)
            viewModel.openBackup(backupFile(entry()))

            viewModel.confirm()

            assertSame("$failure", BackupUiState.Idle, viewModel.state.value)
            assertSame(BackupMessage.ImportMayHaveBeenApplied, viewModel.message.value)
            assertTrue(viewModel.reloadRequested())
            assertTrue(codec.imported.all { it.isWiped() })
        }
    }

    @Test
    fun import_existingAccountUnreadableWhileApplying_endsWithoutALabel() {
        val viewModel = viewModel()
        viewModel.openBackup(backupFile(entry()))
        store.transactionReadFailure = UnreadableAccountException("row-9", SecretCipherException("Decryption failed"))

        viewModel.confirm()

        assertEquals(BackupMessage.ImportFailed(ImportFailure.EXISTING_ACCOUNT_UNREADABLE), viewModel.message.value)
        assertTrue(codec.imported.all { it.isWiped() })
    }

    @Test
    fun importStep_mapsEveryResult() {
        val open = PreparedImport(listOf(entry()), emptyList())
        val closed = PreparedImport(listOf(entry()), emptyList()).apply { close() }
        val next = PreparedImport(listOf(entry()), emptyList())

        assertEquals(
            ImportStep.Finish(BackupMessage.Imported(1, 2, 3), reload = true),
            importStep(ImportResult.Success(1, 2, 3), closed),
        )
        importStep(ImportResult.PlanChanged(next), closed).cast<ImportStep.Review>().let {
            assertSame(next, it.plan)
            assertEquals(ReviewNotice.PLAN_CHANGED, it.notice)
            assertTrue(it.resetDecisions)
        }
        importStep(ImportResult.DecisionsRejected, open).cast<ImportStep.Review>().let {
            assertSame(open, it.plan)
            assertEquals(ReviewNotice.DECISIONS_REJECTED, it.notice)
            assertTrue(it.resetDecisions)
        }
        assertEquals(
            ImportStep.Finish(BackupMessage.ImportMayHaveBeenApplied, reload = true),
            importStep(ImportResult.PlanClosed, closed),
        )
        assertEquals(
            ImportStep.Finish(BackupMessage.ImportFailed(ImportFailure.EXISTING_ACCOUNT_UNREADABLE), reload = false),
            importStep(ImportResult.ExistingAccountUnreadable("row-1"), open),
        )
        for ((result, notice) in listOf(ImportResult.StorageFailed to ReviewNotice.STORAGE_FAILED, ImportResult.NotEnoughMemory to ReviewNotice.NOT_ENOUGH_MEMORY)) {
            importStep(result, open).cast<ImportStep.Review>().let {
                assertEquals(notice, it.notice)
                assertFalse("a retry keeps the decisions", it.resetDecisions)
            }
            assertEquals(
                "isOpen false: may have been written",
                ImportStep.Finish(BackupMessage.ImportMayHaveBeenApplied, reload = true),
                importStep(result, closed),
            )
        }
        open.close()
        next.close()
    }

    @Test
    fun state_andInputs_neverPrintAPasswordOrALabel() {
        val viewModel = viewModel()
        viewModel.startExport()
        viewModel.exportPassword.type(PASSWORD)
        store.add(github)

        assertFalse(viewModel.exportPassword.toString().contains(PASSWORD))
        // Why the wrapper redacts: the platform's state prints its text.
        assertTrue(viewModel.exportPassword.state.toString().contains(PASSWORD))
        val texts = listOf(
            viewModel.state.value,
            BackupUiState.Reviewing(
                PlanReview(
                    newAccounts = 1,
                    identical = 0,
                    conflicts = listOf(
                        ConflictRow(
                            index = 0,
                            imported = AccountLabel("GitHub", "alice"),
                            existing = AccountLabel("GitHub", "alice"),
                            reason = ConflictReason.SAME_LABEL,
                            sharesCounterpart = false,
                            choices = listOf(SKIP),
                            decision = SKIP,
                            replaceTaken = false,
                            counters = null,
                        ),
                    ),
                ),
                null,
            ),
            BackupMessage.ExportFailed(ExportFailure.ACCOUNT_LABEL_TOO_LONG, PartialFile.DELETED, AccountLabel("GitHub", "alice")),
        ).map { it.toString() }
        for (text in texts) {
            assertFalse(text, text.contains(PASSWORD))
            assertFalse(text, text.contains("alice"))
        }
    }

    @Test
    fun reloadRequests_isEmptyUntilAnImportEnds() {
        val viewModel = viewModel()
        assertFalse(viewModel.reloadRequested())
    }
}
