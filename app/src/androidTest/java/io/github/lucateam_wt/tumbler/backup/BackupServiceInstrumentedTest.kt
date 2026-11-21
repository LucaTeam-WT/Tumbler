package io.github.lucateam_wt.tumbler.backup

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lucateam_wt.tumbler.data.crypto.AesGcmSecretCipher
import io.github.lucateam_wt.tumbler.data.crypto.AndroidKeystoreKeyProvider
import io.github.lucateam_wt.tumbler.data.db.TumblerDatabase
import io.github.lucateam_wt.tumbler.data.repository.RoomAccountBackupStore
import io.github.lucateam_wt.tumbler.data.repository.RoomAccountRepository
import io.github.lucateam_wt.tumbler.domain.model.OtpAccount
import io.github.lucateam_wt.tumbler.domain.model.OtpType
import io.github.lucateam_wt.tumbler.domain.otp.Base32
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher

/**
 * [BackupService] end to end on a device: in-memory Room, the real Android Keystore (one test
 * key per simulated device, so a backup never depends on the device key), Bouncy Castle
 * scrypt and the platform's AES-GCM (Conscrypt).
 */
@RunWith(AndroidJUnit4::class)
class BackupServiceInstrumentedTest {

    /** The real format with the smallest scrypt parameters the reader accepts, to keep the damage matrix fast. */
    private object FastCodec : BackupCodec {
        override fun export(accounts: List<BackupEntry>, password: CharArray): ByteArray {
            val random = SecureRandom()
            val salt = ByteArray(BackupFormat.SALT_LENGTH).also(random::nextBytes)
            val nonce = ByteArray(BackupFormat.NONCE_LENGTH).also(random::nextBytes)
            return sealBackup(accounts, password, salt, nonce, ScryptParams(log2N = 14, r = 1, p = 1))
        }

        override fun read(input: InputStream, password: CharArray): BackupReadResult = readBackup(input, password)
    }

    private class Device(context: Context, val alias: String, codec: BackupCodec = DefaultBackupCodec) : Closeable {
        val database: TumblerDatabase = Room.inMemoryDatabaseBuilder(context, TumblerDatabase::class.java).build()
        private val cipher = AesGcmSecretCipher(AndroidKeystoreKeyProvider(alias))
        val repository = RoomAccountRepository(database.accountDao(), cipher)
        val store = RoomAccountBackupStore(database, cipher)
        val service = BackupService(store, codec = codec)

        override fun close() = database.close()
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val aliases = listOf("lucaauth_test_key_backup_a", "lucaauth_test_key_backup_b")
    private lateinit var source: Device
    private lateinit var target: Device

    private val password = "correct horse battery staple"
    private val totpSecret = Base32.decode("JBSWY3DPEHPK3PXP")
    private val hotpSecret = Base32.decode("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
    private val totp = OtpAccount("Example", "alice@example.com", OtpType.TOTP, totpSecret)
    private fun hotp(counter: Long) = OtpAccount("Bank", "bob", OtpType.HOTP, hotpSecret, counter = counter)

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Before
    fun setUp() {
        aliases.forEach(keyStore()::deleteEntry)
        source = Device(context, aliases[0])
        target = Device(context, aliases[1])
    }

    @After
    fun tearDown() {
        source.close()
        target.close()
        aliases.forEach(keyStore()::deleteEntry)
    }

    private inline fun <reified T> Any?.cast(): T {
        assertTrue("unexpected result $this", this is T)
        return this as T
    }

    private fun export(device: Device): ByteArray {
        val output = ByteArrayOutputStream()
        val chars = password.toCharArray()
        runBlocking { device.service.exportAccounts(chars, output) }.cast<ExportResult.Success>()
        assertTrue(chars.all { it == '\u0000' })
        return output.toByteArray()
    }

    private fun read(device: Device, file: ByteArray, password: String = this.password): ImportReadResult =
        runBlocking { device.service.readBackup(ByteArrayInputStream(file), password.toCharArray()) }

    private fun prepare(device: Device, file: ByteArray): PreparedImport =
        read(device, file).cast<ImportReadResult.Ready>().prepared

    private fun apply(device: Device, prepared: PreparedImport, vararg decisions: ConflictDecision): ImportResult =
        runBlocking { device.service.applyImport(prepared, decisions.toList()) }

    private fun ByteArray.containsSequence(needle: ByteArray): Boolean =
        (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }

    @Test
    fun roundTrip_intoAnEmptyDatabase_restoresEveryAccountAndItsCreationTime() = runBlocking {
        source.repository.add(totp)
        source.repository.add(hotp(counter = 7))

        val file = export(source)

        assertEquals("LUCABKUP", String(file, 0, 8, Charsets.US_ASCII))
        assertEquals(ScryptParams.DEFAULT.log2N, file[BackupFormat.LOG2_N_OFFSET].toInt())
        for (plain in listOf(totpSecret, hotpSecret, "alice@example.com".toByteArray(), "Bank".toByteArray())) {
            assertFalse(file.containsSequence(plain))
        }
        val prepared = prepare(target, file)
        assertTrue(prepared.plan.conflicts.isEmpty())
        val result = apply(target, prepared).cast<ImportResult.Success>()
        assertEquals(listOf(2, 0, 0), listOf(result.inserted, result.replaced, result.skipped))

        val expected = source.store.readAll().sortedBy { it.account.issuer }
        val actual = target.store.readAll().sortedBy { it.account.issuer }
        assertEquals(expected.map { it.account }, actual.map { it.account })
        assertEquals(expected.map { it.createdAtMillis }, actual.map { it.createdAtMillis })
        assertFalse(prepared.isOpen)
    }

    @Test
    fun roundTrip_withConflicts_keepBothAndReplaceWithACounterIncrementedAfterThePlan() = runBlocking {
        source.repository.add(totp)
        source.repository.add(hotp(counter = 7))
        val totpId = target.repository.add(totp)
        val hotpId = target.repository.add(hotp(counter = 3))
        val hotpCreatedAt = target.store.readAll().single { it.id == hotpId }.createdAtMillis

        val prepared = prepare(target, export(source))
        val conflicts = prepared.plan.conflicts
        assertEquals(listOf(totpId, hotpId), conflicts.map { it.existing.id })
        assertEquals(setOf(ConflictDecision.SKIP, ConflictDecision.REPLACE), allowedDecisions(conflicts[1]))
        // The user generates codes while deciding: the counter moves past the imported one.
        target.repository.updateCounter(hotpId, 10)

        val result = apply(target, prepared, ConflictDecision.KEEP_BOTH, ConflictDecision.REPLACE).cast<ImportResult.Success>()

        assertEquals(listOf(1, 1, 0), listOf(result.inserted, result.replaced, result.skipped))
        val all = target.store.readAll()
        assertEquals(3, all.size)
        assertEquals(2, all.count { it.account == totp })
        assertEquals(hotp(counter = 10), target.repository.getAccount(hotpId))
        assertEquals(hotpCreatedAt, all.single { it.id == hotpId }.createdAtMillis)
    }

    @Test
    fun abort_accountAddedBetweenPlanAndWrite_writesNothingAndOffersANewPlan() = runBlocking {
        source.repository.add(totp)
        val prepared = prepare(target, export(source))
        assertTrue(prepared.plan.conflicts.isEmpty())
        val addedId = target.repository.add(totp)

        val next = apply(target, prepared).cast<ImportResult.PlanChanged>().newPlan

        assertEquals(listOf(addedId), target.store.readAll().map { it.id })
        assertSame(ImportResult.PlanClosed, apply(target, prepared))
        assertEquals(addedId, next.plan.conflicts.single().existing.id)
        val result = apply(target, next, ConflictDecision.SKIP).cast<ImportResult.Success>()
        assertEquals(listOf(0, 0, 1), listOf(result.inserted, result.replaced, result.skipped))
        assertEquals(listOf(addedId), target.store.readAll().map { it.id })
    }

    @Test
    fun abort_counterpartDeletedBetweenPlanAndWrite_writesNothing() = runBlocking {
        source.repository.add(totp)
        val existingId = target.repository.add(totp)
        val prepared = prepare(target, export(source))
        target.repository.delete(existingId)

        val next = apply(target, prepared, ConflictDecision.REPLACE).cast<ImportResult.PlanChanged>().newPlan

        assertTrue(target.store.readAll().isEmpty())
        assertTrue(next.plan.conflicts.isEmpty())
        apply(target, next).cast<ImportResult.Success>()
        assertEquals(listOf(totp), target.store.readAll().map { it.account })
    }

    @Test
    fun conscrypt_wrongPasswordAndEveryDamagedFile_areWrongPasswordOrDamaged() = runBlocking {
        assertEquals("AndroidOpenSSL", Cipher.getInstance(BackupFormat.TRANSFORMATION).provider.name)
        val fastSource = Device(context, aliases[0], FastCodec)
        try {
            fastSource.repository.add(totp)
            val file = export(fastSource)
            val header = BackupFormat.headerLength(BackupFormat.SALT_LENGTH)
            fun flipped(index: Int) = file.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }

            read(target, file).cast<ImportReadResult.Ready>().prepared.close()
            val damaged = mapOf(
                "wrong password" to (file to "wrong horse battery staple"),
                "salt byte" to (flipped(BackupFormat.SALT_OFFSET) to password),
                "nonce byte" to (flipped(header - 1) to password),
                "first ciphertext byte" to (flipped(header) to password),
                "tag byte" to (flipped(file.lastIndex) to password),
                "last byte removed" to (file.copyOf(file.size - 1) to password),
                "byte appended" to (file + 0 to password),
            )
            for ((name, case) in damaged) {
                val (bytes, pass) = case
                val result = read(target, bytes, pass).cast<ImportReadResult.FileRejected>()
                assertSame(name, BackupReadResult.WrongPasswordOrDamaged, result.reason)
            }
            assertTrue(target.store.readAll().isEmpty())
            assertArrayEquals(totpSecret, fastSource.store.readAll().single().account.secret)
        } finally {
            fastSource.close()
        }
    }
}
