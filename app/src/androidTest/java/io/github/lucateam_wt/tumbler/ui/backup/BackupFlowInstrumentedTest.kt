package io.github.lucateam_wt.tumbler.ui.backup

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lucateam_wt.tumbler.backup.BackupCodec
import io.github.lucateam_wt.tumbler.backup.BackupEntry
import io.github.lucateam_wt.tumbler.backup.BackupFormat
import io.github.lucateam_wt.tumbler.backup.BackupPayload
import io.github.lucateam_wt.tumbler.backup.BackupReadResult
import io.github.lucateam_wt.tumbler.backup.BackupService
import io.github.lucateam_wt.tumbler.backup.ConflictDecision
import io.github.lucateam_wt.tumbler.backup.DefaultBackupCodec
import io.github.lucateam_wt.tumbler.backup.ScryptParams
import io.github.lucateam_wt.tumbler.backup.exportBackup
import io.github.lucateam_wt.tumbler.backup.readBackup
import io.github.lucateam_wt.tumbler.backup.sealBackup
import io.github.lucateam_wt.tumbler.data.crypto.AesGcmSecretCipher
import io.github.lucateam_wt.tumbler.data.crypto.AndroidKeystoreKeyProvider
import io.github.lucateam_wt.tumbler.data.crypto.DataKeyCipher
import io.github.lucateam_wt.tumbler.data.crypto.KeyProvider
import io.github.lucateam_wt.tumbler.data.db.AccountEntity
import io.github.lucateam_wt.tumbler.data.db.TumblerDatabase
import io.github.lucateam_wt.tumbler.data.repository.RoomAccountBackupStore
import io.github.lucateam_wt.tumbler.data.repository.RoomAccountRepository
import io.github.lucateam_wt.tumbler.data.repository.deleteTestKeys
import io.github.lucateam_wt.tumbler.data.repository.accountAssociatedData
import io.github.lucateam_wt.tumbler.data.repository.testDataKeyHolder
import io.github.lucateam_wt.tumbler.domain.model.OtpAccount
import io.github.lucateam_wt.tumbler.domain.model.OtpType
import io.github.lucateam_wt.tumbler.domain.otp.Base32
import io.github.lucateam_wt.tumbler.domain.repository.NextHotpCodeResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/**
 * Export and import end to end through [BackupViewModel], the way the screens drive it, on a
 * device: in-memory Room, the real Android Keystore (one key per simulated device), Bouncy
 * Castle scrypt with the default parameters and the platform's AES-GCM. Only the file picker
 * is replaced, by a document held in memory.
 */
@RunWith(AndroidJUnit4::class)
class BackupFlowInstrumentedTest {

    private class MemoryFile(initial: ByteArray = ByteArray(0)) : BackupFile {
        @Volatile
        var bytes: ByteArray = initial
        @Volatile
        var deleted = false

        override fun openOutput(): OutputStream = object : ByteArrayOutputStream() {
            override fun close() {
                bytes = toByteArray()
            }
        }

        override fun openInput(): InputStream = ByteArrayInputStream(bytes)

        override fun delete(): Boolean {
            deleted = true
            return true
        }

        override fun isKnownEmpty(): Boolean = bytes.isEmpty()
    }

    /** The real format with the smallest parameters the reader accepts, for the password matrix. */
    private object FastCodec : BackupCodec {
        override fun export(accounts: List<BackupEntry>, password: CharArray): ByteArray {
            val random = SecureRandom()
            val salt = ByteArray(BackupFormat.SALT_LENGTH).also(random::nextBytes)
            val nonce = ByteArray(BackupFormat.NONCE_LENGTH).also(random::nextBytes)
            return sealBackup(accounts, password, salt, nonce, ScryptParams(log2N = 14, r = 1, p = 1))
        }

        override fun read(input: InputStream, password: CharArray): BackupReadResult = readBackup(input, password)
    }

    private inner class Device(alias: String, codec: BackupCodec = DefaultBackupCodec) : Closeable {
        val database: TumblerDatabase = Room.inMemoryDatabaseBuilder(context, TumblerDatabase::class.java).build()
        private val cipher = AesGcmSecretCipher(AndroidKeystoreKeyProvider(alias))
        val dataKeys = testDataKeyHolder(database, "${alias}_wrap")
        val repository = RoomAccountRepository(database, cipher, dataKeys)
        val store = RoomAccountBackupStore(database, cipher, dataKeys)
        val viewModel = onMain {
            BackupViewModel(BackupService(store, codec = codec), { repository.observeAccounts().first() })
        }

        fun accounts(): List<OtpAccount> = runBlocking { store.readAll() }.map { it.account }

        override fun close() = database.close()
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val aliases = listOf("lucaauth_test_key_flow_a", "lucaauth_test_key_flow_b")
    private val devices = mutableListOf<Device>()

    private val password = "correct horse battery staple"
    private val totpSecret = Base32.decode("JBSWY3DPEHPK3PXP")

    /** The RFC 4226 test secret "12345678901234567890". */
    private val rfcSecret = Base32.decode("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")

    /** RFC 4226 appendix D: the codes for counters 0 to 9 of [rfcSecret]. */
    private val rfcCodes = listOf("755224", "287082", "359152", "969429", "338314", "254676", "287922", "162583", "399871", "520489")

    private val totp = OtpAccount("Example", "alice@example.com", OtpType.TOTP, totpSecret)
    private fun hotp(counter: Long, issuer: String = "Bank") = OtpAccount(issuer, "bob", OtpType.HOTP, rfcSecret, counter = counter)

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Before
    fun setUp() = aliases.forEach(::deleteTestKeys)

    @After
    fun tearDown() {
        devices.forEach(Device::close)
        aliases.forEach(::deleteTestKeys)
    }

    private fun device(index: Int, codec: BackupCodec = DefaultBackupCodec) = Device(aliases[index], codec).also { devices += it }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    /** Waits for [done]; a timeout fails with the name of the phase ([what]) instead of a bare cancellation. */
    private fun BackupViewModel.await(
        what: String,
        timeoutMillis: Long = TIMEOUT_MILLIS,
        done: (BackupUiState) -> Boolean,
    ): BackupUiState = runBlocking {
        withTimeoutOrNull(timeoutMillis) { state.first(done) }
            ?: throw AssertionError("$what did not finish within $timeoutMillis ms")
    }.also { assertTrue("waiting for $what", done(it)) }

    private fun BackupViewModel.export(
        password: String = this@BackupFlowInstrumentedTest.password,
        timeoutMillis: Long = TIMEOUT_MILLIS,
    ): MemoryFile {
        val file = MemoryFile()
        onMain {
            startExport()
            exportPassword.state.setTextAndPlaceCursorAtEnd(password)
            exportConfirmation.state.setTextAndPlaceCursorAtEnd(password)
            assertTrue(submitExportPassword())
            exportFileChosen(file)
        }
        await("the export", timeoutMillis) { it == BackupUiState.Idle }
        return file
    }

    /** Opens [file] with [password] and returns the state the read ends in. */
    private fun BackupViewModel.open(
        file: MemoryFile,
        password: String = this@BackupFlowInstrumentedTest.password,
        timeoutMillis: Long = TIMEOUT_MILLIS,
    ): BackupUiState {
        onMain {
            importFileChosen(file)
            importPassword.state.setTextAndPlaceCursorAtEnd(password)
            submitImportPassword()
        }
        return await("the read", timeoutMillis) { it != BackupUiState.ImportReading }
    }

    private fun BackupViewModel.review(): PlanReview = (state.value as BackupUiState.Reviewing).review

    private fun BackupViewModel.confirm(timeoutMillis: Long = TIMEOUT_MILLIS): BackupMessage? {
        onMain {
            continueImport()
            confirmImport()
        }
        await("the import", timeoutMillis) { it !is BackupUiState.Importing }
        return onMain { message.value }
    }

    private fun BackupViewModel.reloadRequested(): Boolean = runBlocking {
        withTimeoutOrNull(5_000) { reloadRequests.first() } != null
    }

    @Test
    fun roundTrip_threeAccountsAtDefaultParameters_intoAnEmptyDevice() {
        val source = device(0)
        val target = device(1)
        runBlocking {
            source.repository.add(totp)
            source.repository.add(hotp(counter = 0))
            source.repository.add(hotp(counter = Long.MAX_VALUE, issuer = "Max"))
        }

        val started = SystemClock.elapsedRealtime()
        val file = source.viewModel.export()
        val exported = SystemClock.elapsedRealtime()

        assertEquals(BackupMessage.Exported(3), onMain { source.viewModel.message.value })
        assertEquals("LUCABKUP", String(file.bytes, 0, 8, Charsets.US_ASCII))
        assertEquals(ScryptParams.DEFAULT.log2N, file.bytes[BackupFormat.LOG2_N_OFFSET].toInt())
        assertEquals(false, file.deleted)

        val read = target.viewModel.open(file)
        val readDone = SystemClock.elapsedRealtime()
        assertEquals(PlanReview(newAccounts = 3, identical = 0, conflicts = emptyList()), (read as BackupUiState.Reviewing).review)
        assertTrue("nothing is written before the confirmation", target.accounts().isEmpty())

        assertEquals(BackupMessage.Imported(added = 3, replaced = 0, skipped = 0), target.viewModel.confirm())
        assertEquals(source.accounts().sortedBy { it.issuer }, target.accounts().sortedBy { it.issuer })
        assertTrue(target.viewModel.reloadRequested())
        // The accounts list observes the database: the import reaches it without a reload.
        val listed = runBlocking { withTimeout(TIMEOUT_MILLIS) { target.repository.observeAccounts().first { it.size == 3 } } }
        assertEquals(setOf("Example", "Bank", "Max"), listed.map { it.issuer }.toSet())
        Log.i(TAG, "default scrypt on API ${Build.VERSION.SDK_INT}: export ${exported - started} ms, read ${readDone - exported} ms")
    }

    @Test
    fun roundTrip_withConflicts_appliesEachDecision() {
        val source = device(0)
        val target = device(1)
        runBlocking {
            source.repository.add(totp)
            source.repository.add(hotp(counter = 5))
            source.repository.add(OtpAccount("Mail", "carol", OtpType.TOTP, totpSecret + byteArrayOf(1)))
            source.repository.add(OtpAccount("New", "dave", OtpType.TOTP, totpSecret + byteArrayOf(2)))
            target.repository.add(totp) // identical
            target.repository.add(hotp(counter = 2)) // same HOTP secret and label: Skip or Replace
            target.repository.add(OtpAccount("Mail", "carol", OtpType.TOTP, totpSecret + byteArrayOf(3))) // same label
        }
        val file = source.viewModel.export()

        target.viewModel.open(file)
        val review = target.viewModel.review()
        assertEquals(1, review.newAccounts)
        assertEquals(1, review.identical)
        assertEquals(2, review.conflicts.size)
        assertTrue("Replace is never preselected", review.conflicts.all { it.decision == ConflictDecision.SKIP })
        val hotpRow = review.conflicts.single { it.counters != null }
        val labelRow = review.conflicts.single { it.counters == null }
        assertEquals(listOf(ConflictDecision.SKIP, ConflictDecision.REPLACE), hotpRow.choices)
        assertEquals(HotpCounters(stored = 2, imported = 5, replaceKeepsLarger = true), hotpRow.counters)
        onMain {
            target.viewModel.setDecision(hotpRow.index, ConflictDecision.REPLACE)
            target.viewModel.setDecision(labelRow.index, ConflictDecision.KEEP_BOTH)
        }

        assertEquals(BackupMessage.Imported(added = 2, replaced = 1, skipped = 1), target.viewModel.confirm())

        val accounts = target.accounts()
        assertEquals(5, accounts.size)
        assertEquals(5L, accounts.single { it.issuer == "Bank" }.counter)
        assertEquals(2, accounts.count { it.issuer == "Mail" })
        assertEquals(1, accounts.count { it == totp })
    }

    /**
     * Passwords are NFC-normalized and nothing else (docs/backup-format.md, "Password"), with
     * the device's own `java.text.Normalizer`: canonically equal spellings open the backup,
     * compatibility-equal ones (NFKC) and changed whitespace do not.
     */
    @Test
    fun passwords_normalizationAndWhitespace_followTheSpecOnThisDevice() {
        val source = device(0, FastCodec)
        val target = device(1, FastCodec)
        runBlocking { source.repository.add(totp) }
        data class Case(val written: String, val typed: String, val opens: Boolean, val name: String)
        val cases = listOf(
            // "mật khẩu rất dài" precomposed (NFC) and decomposed (NFD).
            Case("m\u1eadt kh\u1ea9u r\u1ea5t d\u00e0i", "ma\u0323\u0302t kha\u0302\u0309u ra\u0302\u0301t da\u0300i", true, "Vietnamese NFC vs NFD"),
            Case("ma\u0323\u0302t kha\u0302\u0309u ra\u0302\u0301t da\u0300i", "m\u1eadt kh\u1ea9u r\u1ea5t d\u00e0i", true, "Vietnamese NFD vs NFC"),
            // "한국어 비밀번호 아주 길게" with the first word as conjoining jamo.
            Case(
                "\ud55c\uad6d\uc5b4 \ube44\ubc00\ubc88\ud638 \uc544\uc8fc \uae38\uac8c",
                "\u1112\u1161\u11ab\u1100\u116e\u11a8\u110b\u1165 \ube44\ubc00\ubc88\ud638 \uc544\uc8fc \uae38\uac8c",
                true,
                "Hangul syllables vs jamo",
            ),
            Case("\ufb01nal password here", "final password here", false, "ligature vs letters (NFKC only)"),
            Case("\uff21\uff22\uff23 full width password", "ABC full width password", false, "full width vs ASCII (NFKC only)"),
            Case("  spaces around it  ", "spaces around it", false, "surrounding spaces are kept"),
            Case("tab\tinside password", "tab inside password", false, "tab vs space"),
            Case("\ud83d\ude00\ud83d\udd11 emoji password \ud83d\udd12", "\ud83d\ude00\ud83d\udd11 emoji password \ud83d\udd12", true, "characters outside the BMP"),
        )
        for (case in cases) {
            val file = source.viewModel.export(case.written)
            assertEquals(case.name, BackupMessage.Exported(1), onMain { source.viewModel.message.value })
            onMain { source.viewModel.messageShown() }

            val state = target.viewModel.open(file, case.typed)

            if (case.opens) {
                assertEquals(case.name, 1, (state as BackupUiState.Reviewing).review.newAccounts)
            } else {
                assertEquals(case.name, BackupUiState.ImportPassword(wrongPasswordOrDamaged = true), state)
                // The exact spelling still opens it.
                onMain {
                    target.viewModel.importPassword.state.setTextAndPlaceCursorAtEnd(case.written)
                    target.viewModel.submitImportPassword()
                }
                val retry = target.viewModel.await("the retry") { it != BackupUiState.ImportReading }
                assertTrue(case.name, retry is BackupUiState.Reviewing)
            }
            onMain { target.viewModel.cancelImport() }
        }
        Log.i(TAG, "password normalization matrix passed on API ${Build.VERSION.SDK_INT} (${cases.size} cases)")
    }

    @Test
    fun wrongPasswordAndDamagedFile_endInTheSameState() {
        val source = device(0)
        val target = device(1)
        runBlocking { source.repository.add(totp) }
        val file = source.viewModel.export()
        val damaged = MemoryFile(file.bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })

        val wrong = target.viewModel.open(file, "correct horse battery stapler")
        onMain { target.viewModel.cancelImport() }
        val broken = target.viewModel.open(damaged)

        assertEquals(BackupUiState.ImportPassword(wrongPasswordOrDamaged = true), wrong)
        assertEquals(wrong, broken)
        assertNull(onMain { target.viewModel.message.value })
        onMain { target.viewModel.cancelImport() }
    }

    /** Account [i] of the 1000-account test: public test secret with the index appended, so no two match. */
    private fun bulkAccount(i: Int) = OtpAccount(
        issuer = "Issuer $i ".padEnd(200, 'x'),
        accountName = "user$i@example.com".padEnd(200, 'y'),
        type = OtpType.TOTP,
        secret = rfcSecret + byteArrayOf((i shr 8).toByte(), i.toByte()),
    )

    /** One line under [PERF_TAG]. Only counts, sizes, parameters and times: never a label, secret or password. */
    private fun perf(line: String) = Log.i(PERF_TAG, line)

    private fun since(startedMillis: Long) = SystemClock.elapsedRealtime() - startedMillis

    private fun millis(value: Double) = String.format(Locale.ROOT, "%.1f", value)

    /** Where the Keystore keeps [alias]'s key: StrongBox, the TEE or software. Not secret. */
    private fun keySecurityLevel(alias: String): String {
        val key = keyStore().getKey(alias, null) as? SecretKey ?: return "no key"
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (val level = info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software"
                else -> "other ($level)"
            }
        } else {
            @Suppress("DEPRECATION")
            if (info.isInsideSecureHardware) "secure hardware" else "software"
        }
    }

    /**
     * Splits the per-row cost: the average of [KEYSTORE_SAMPLE] calls each of a legacy key lookup
     * alone, a legacy decrypt as the app does it for a row not migrated yet (lookup included), the
     * same decrypt with the key already looked up, an encrypt (lookup included) of zeros, and the
     * software open of one stored row with its data key, as the app reads every row now. Every
     * decrypted array is wiped.
     */
    private fun keystoreSample(device: Device, alias: String): String = runBlocking {
        val row = device.database.accountDao().getAll().first()
        val associatedData = accountAssociatedData(row.id)
        val provider = AndroidKeystoreKeyProvider(alias)
        val zeros = ByteArray(rfcSecret.size + 2)
        // Nothing writes the legacy format any more: make one legacy payload (and key) to time.
        val legacyPayload = AesGcmSecretCipher(provider).encrypt(zeros, associatedData)
        val dataKey = device.dataKeys.key(row.keyVersion)
        val key = checkNotNull(provider.getExistingKey())
        val loaded = object : KeyProvider {
            override fun getOrCreateKey(): SecretKey = key
            override fun getExistingKey(): SecretKey = key
        }
        fun perCall(block: () -> Unit): String {
            val started = SystemClock.elapsedRealtimeNanos()
            repeat(KEYSTORE_SAMPLE) { block() }
            return millis((SystemClock.elapsedRealtimeNanos() - started) / 1e6 / KEYSTORE_SAMPLE)
        }
        val lookup = perCall { checkNotNull(provider.getExistingKey()) }
        val decrypt = perCall { AesGcmSecretCipher(provider).decrypt(legacyPayload, associatedData).fill(0) }
        val decryptLoaded = perCall { AesGcmSecretCipher(loaded).decrypt(legacyPayload, associatedData).fill(0) }
        val encrypt = perCall { AesGcmSecretCipher(provider).encrypt(zeros, associatedData) }
        val open = perCall { DataKeyCipher.open(dataKey, row.id, row.secretBlob).fill(0) }
        "legacy key lookup $lookup ms, legacy decrypt $decrypt ms, legacy decrypt with the key already looked up " +
            "$decryptLoaded ms, legacy encrypt $encrypt ms, data-key open $open ms (average of $KEYSTORE_SAMPLE each)"
    }

    /**
     * The largest backup there can be, by account count, at the default parameters: 1000
     * accounts exported, read and imported into an empty device. It checks correctness (the
     * counts and the round trip), not speed, within one budget of [PERF_TIMEOUT_MILLIS] for
     * the whole test. Each phase logs its time under [PERF_TAG] as soon as it ends, so a slow
     * device shows where the time goes even if a later phase fails. Run it alone with the
     * command in docs/backup-format.md (item 11), then `adb logcat -d -s TumblerBackupPerf:I`.
     */
    @Test
    fun memory_thousandAccountsExportAndImportWithoutRunningOutOfMemory() {
        val testStarted = SystemClock.elapsedRealtime()
        val deadline = testStarted + PERF_TIMEOUT_MILLIS
        fun remaining() = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1)
        val source = device(0)
        val target = device(1)
        val count = BackupPayload.MAX_ACCOUNTS
        val defaults = ScryptParams.DEFAULT
        val runtime = Runtime.getRuntime()
        perf(
            "start: $count accounts, API ${Build.VERSION.SDK_INT}, default scrypt N=2^${defaults.log2N} r=${defaults.r} " +
                "p=${defaults.p}, heap limit ${runtime.maxMemory() / MIB} MiB, budget ${PERF_TIMEOUT_MILLIS / 60_000} min",
        )

        // Setup: each account is one software seal with the data key and one insert (the first add makes the key).
        val setupStarted = SystemClock.elapsedRealtime()
        runBlocking {
            withTimeoutOrNull(remaining()) {
                for (i in 0 until count) {
                    source.repository.add(bulkAccount(i))
                    if ((i + 1) % PERF_PROGRESS_EVERY == 0) perf("setup: ${i + 1} of $count accounts added after ${since(setupStarted)} ms")
                }
            } ?: throw AssertionError("the setup did not finish within the budget")
        }
        val setup = since(setupStarted)
        perf("setup: $count accounts in $setup ms (${millis(setup.toDouble() / count)} ms each)")
        perf("key: ${keySecurityLevel(aliases[0])}")
        perf("keystore: ${keystoreSample(source, aliases[0])}")

        // One scrypt with the default parameters, the cost every export and every read pays once.
        val scryptStarted = SystemClock.elapsedRealtime()
        val scryptPassword = password.toCharArray()
        try {
            exportBackup(emptyList(), scryptPassword)
        } finally {
            scryptPassword.fill(WIPED_CHAR)
        }
        val scrypt = since(scryptStarted)
        perf("scrypt: $scrypt ms (an empty backup at the default parameters)")

        // Export: reads and decrypts every account (one Keystore decryption each), one scrypt.
        val exportStarted = SystemClock.elapsedRealtime()
        val file = source.viewModel.export(timeoutMillis = remaining())
        val exportTime = since(exportStarted)
        assertEquals(BackupMessage.Exported(count), onMain { source.viewModel.message.value })
        val header = file.bytes
        perf(
            "export: $exportTime ms, file ${header.size} bytes, written with N=2^${header[BackupFormat.LOG2_N_OFFSET]} " +
                "r=${header[BackupFormat.R_OFFSET]} p=${header[BackupFormat.P_OFFSET]}",
        )
        assertEquals(defaults.log2N, header[BackupFormat.LOG2_N_OFFSET].toInt())
        assertEquals(defaults.r, header[BackupFormat.R_OFFSET].toInt())
        assertEquals(defaults.p, header[BackupFormat.P_OFFSET].toInt())

        // Read: one scrypt, decryption of the file, and a plan against the empty device.
        val readStarted = SystemClock.elapsedRealtime()
        val read = target.viewModel.open(file, timeoutMillis = remaining())
        val readTime = since(readStarted)
        perf("read: $readTime ms")
        assertEquals(PlanReview(newAccounts = count, identical = 0, conflicts = emptyList()), (read as BackupUiState.Reviewing).review)

        // Import: one transaction that reads the device again (empty), then encrypts and inserts every account.
        val importStarted = SystemClock.elapsedRealtime()
        val message = target.viewModel.confirm(timeoutMillis = remaining())
        val importTime = since(importStarted)
        perf("import: $importTime ms")
        assertEquals(BackupMessage.Imported(added = count, replaced = 0, skipped = 0), message)

        // The round trip, checked without decrypting every row again: every field but the secret
        // on all rows, and the secret (with its binding to the row's id) on a sample.
        val sourceRows = runBlocking { source.database.accountDao().getAll() }
        val targetRows = runBlocking { target.database.accountDao().getAll() }
        assertEquals(count, targetRows.size)
        fun fields(row: AccountEntity) = listOf(row.issuer, row.accountName, row.type, row.algorithm, row.digits, row.period, row.counter, row.createdAt)
        assertEquals(sourceRows.map(::fields).toSet(), targetRows.map(::fields).toSet())
        val expected = (0 until count).map(::bulkAccount)
        assertEquals(expected.map { it.issuer to it.accountName }.toSet(), targetRows.map { it.issuer to it.accountName }.toSet())
        // Imported rows are sealed with the target's data key, never its legacy key.
        assertTrue(targetRows.all { it.keyVersion == 1 && it.legacySecretBlob == null })
        val targetKey = runBlocking { target.dataKeys.key(1) }
        for (i in listOf(0, 1, count / 2, count - 2, count - 1)) {
            val row = targetRows.single { it.issuer == expected[i].issuer }
            val secret = DataKeyCipher.open(targetKey, row.id, row.secretBlob)
            try {
                assertTrue("secret of account $i", secret.contentEquals(expected[i].secret))
            } finally {
                secret.fill(0)
            }
        }
        perf(
            "done: total ${since(testStarted)} ms (setup $setup, export $exportTime, read $readTime, import $importTime), " +
                "heap in use ${(runtime.totalMemory() - runtime.freeMemory()) / MIB} MiB",
        )
    }

    /**
     * A Replace that lands while the user keeps taking "next code" on the same HOTP account:
     * the counter never goes back, so no code is shown twice (the write-time counter rule
     * together with the atomic "next code").
     */
    @Test
    fun replaceWhileTakingNextCodes_neverShowsACodeTwice() {
        val source = device(0)
        val target = device(1)
        runBlocking { source.repository.add(hotp(counter = 5)) }
        val id = runBlocking { target.repository.add(hotp(counter = 2)) }
        val file = source.viewModel.export()

        target.viewModel.open(file)
        val row = target.viewModel.review().conflicts.single()
        onMain { target.viewModel.setDecision(row.index, ConflictDecision.REPLACE) }
        // While the review is open the user takes five codes: counters 2 to 6.
        val shown = mutableListOf<String>()
        repeat(5) { shown += (runBlocking { target.repository.nextHotpCode(id) } as NextHotpCodeResult.Code).code }
        assertEquals(rfcCodes.subList(2, 7), shown)

        assertEquals(BackupMessage.Imported(added = 0, replaced = 1, skipped = 0), target.viewModel.confirm())

        // The stored 7 is larger than the backup's 5, so it stays; the next code is new.
        assertEquals(7L, runBlocking { target.repository.getAccount(id) }?.counter)
        val next = runBlocking { target.repository.nextHotpCode(id) } as NextHotpCodeResult.Code
        assertEquals(rfcCodes[7], next.code)
        assertTrue(next.code !in shown)
    }

    private companion object {
        const val TAG = "BackupFlowTest"
        const val TIMEOUT_MILLIS = 5 * 60 * 1000L

        /** The 1000-account test's own tag, so `adb logcat -s TumblerBackupPerf:I` shows only its timings. */
        const val PERF_TAG = "TumblerBackupPerf"

        /** One budget for the whole 1000-account test, so a slow device finishes and reports. */
        const val PERF_TIMEOUT_MILLIS = 30 * 60 * 1000L
        const val PERF_PROGRESS_EVERY = 250
        const val KEYSTORE_SAMPLE = 20
        const val MIB = 1024 * 1024
    }
}
