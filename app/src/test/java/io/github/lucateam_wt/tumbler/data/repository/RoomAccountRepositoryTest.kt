package io.github.lucateam_wt.tumbler.data.repository

import io.github.lucateam_wt.tumbler.backup.isWiped
import io.github.lucateam_wt.tumbler.data.crypto.AesGcmSecretCipher
import io.github.lucateam_wt.tumbler.data.crypto.KeyProvider
import io.github.lucateam_wt.tumbler.data.db.AccountEntity
import io.github.lucateam_wt.tumbler.domain.crypto.KeyUnavailableException
import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipher
import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipherException
import io.github.lucateam_wt.tumbler.domain.model.AccountSummary
import io.github.lucateam_wt.tumbler.domain.model.OtpAccount
import io.github.lucateam_wt.tumbler.domain.model.OtpType
import io.github.lucateam_wt.tumbler.domain.otp.Base32
import io.github.lucateam_wt.tumbler.domain.otp.Hotp
import io.github.lucateam_wt.tumbler.domain.otp.OtpAlgorithm
import io.github.lucateam_wt.tumbler.domain.repository.NextHotpCodeResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class RoomAccountRepositoryTest {

    /** Plain JVM AES key; [lost] simulates the Keystore entry being deleted. */
    private class TestKeyProvider : KeyProvider {
        private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        var lost = false

        override fun getOrCreateKey(): SecretKey = key
        override fun getExistingKey(): SecretKey? = if (lost) null else key
    }

    /** Remembers every plaintext it hands out, so tests can check that it was wiped; [onDecrypt] runs before it returns. */
    private class RecordingCipher(private val delegate: SecretCipher) : SecretCipher {
        val decrypted: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
        var onDecrypt: () -> Unit = {}

        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
            delegate.encrypt(plaintext, associatedData)

        override fun decrypt(payload: ByteArray, associatedData: ByteArray): ByteArray =
            delegate.decrypt(payload, associatedData).also {
                decrypted += it
                onDecrypt()
            }
    }

    /** One transaction at a time, every row restored if the block throws, like Room's write transactions. */
    private class SerialRunner(private val dao: FakeAccountDao) : TransactionRunner {
        private val mutex = Mutex()
        var transactions = 0
            private set

        override suspend fun <R> inTransaction(block: suspend () -> R): R = mutex.withLock {
            transactions++
            val before = dao.snapshot()
            try {
                block()
            } catch (t: Throwable) {
                dao.restore(before)
                throw t
            }
        }
    }

    /** No isolation at all, to show what the compare-and-set alone guarantees. */
    private object PassThroughRunner : TransactionRunner {
        override suspend fun <R> inTransaction(block: suspend () -> R): R = block()
    }

    private val keyProvider = TestKeyProvider()
    private val dao = FakeAccountDao()
    private val cipher = RecordingCipher(AesGcmSecretCipher(keyProvider))
    private val runner = SerialRunner(dao)
    private var nextId = 0
    private var now = 1_000L
    private val repository = RoomAccountRepository(
        dao = dao,
        cipher = cipher,
        transactions = runner,
        idGenerator = { "id-${nextId++}" },
        clock = { now++ },
    )

    private val totpSecretText = "totp-secret-12345"
    private val totp = OtpAccount(
        issuer = "Example",
        accountName = "alice@example.com",
        type = OtpType.TOTP,
        secret = totpSecretText.toByteArray(),
        algorithm = OtpAlgorithm.SHA256,
        digits = 8,
        period = 60,
    )
    private val hotp = OtpAccount(
        issuer = "Bank",
        accountName = "bob",
        type = OtpType.HOTP,
        secret = "12345678901234567890".toByteArray(),
        counter = 5,
    )

    private fun ByteArray.containsSequence(needle: ByteArray): Boolean =
        (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }

    @Test
    fun add_thenGetAccount_returnsEqualAccount() = runBlocking {
        val totpId = repository.add(totp)
        val hotpId = repository.add(hotp)

        assertEquals(totp, repository.getAccount(totpId))
        assertEquals(hotp, repository.getAccount(hotpId))
    }

    @Test
    fun storedBlob_doesNotEqualOrContainPlaintextSecret() = runBlocking {
        val blob = dao.stored(repository.add(totp)).secretBlob

        assertFalse(blob.contentEquals(totp.secret))
        assertFalse(blob.containsSequence(totp.secret))
    }

    @Test
    fun blobCopiedToAnotherRow_failsAssociatedDataCheck() = runBlocking {
        val idA = repository.add(totp)
        val idB = repository.add(hotp)
        dao.put(dao.stored(idB).copyWith(secretBlob = dao.stored(idA).secretBlob))

        val error = assertThrows(SecretCipherException::class.java) {
            runBlocking { repository.getAccount(idB) }
        }
        assertFalse(error is KeyUnavailableException)
        assertFalse(error.message.orEmpty().contains(totpSecretText))
    }

    @Test
    fun corruptedBlob_throwsSecretCipherException() = runBlocking {
        val id = repository.add(totp)
        val blob = dao.stored(id).secretBlob.copyOf()
        blob[blob.lastIndex] = (blob[blob.lastIndex].toInt() xor 1).toByte()
        dao.put(dao.stored(id).copyWith(secretBlob = blob))

        assertThrows(SecretCipherException::class.java) { runBlocking { repository.getAccount(id) } }
        Unit
    }

    @Test
    fun keyLost_getAccountThrowsKeyUnavailable() = runBlocking {
        val id = repository.add(totp)
        keyProvider.lost = true

        assertThrows(KeyUnavailableException::class.java) { runBlocking { repository.getAccount(id) } }
        Unit
    }

    @Test
    fun unknownId_returnsNull() = runBlocking {
        repository.add(totp)
        assertNull(repository.getAccount("missing"))
    }

    @Test
    fun delete_removesAccount() = runBlocking {
        val id = repository.add(totp)
        val otherId = repository.add(hotp)

        repository.delete(id)

        assertNull(repository.getAccount(id))
        assertEquals(listOf(otherId), repository.observeAccounts().first().map { it.id })
    }

    @Test
    fun nextHotpCode_returnsTheRfcCodeForTheStoredCounterAndStoresTheNextOne() = runBlocking {
        val hotpId = repository.add(hotp)

        val first = repository.nextHotpCode(hotpId)
        val second = repository.nextHotpCode(hotpId)

        // RFC 4226 Appendix D, counters 5 and 6.
        assertEquals(NextHotpCodeResult.Code("254676", 6), first)
        assertEquals(NextHotpCodeResult.Code("287922", 7), second)
        assertEquals(hotp.copy(counter = 7), repository.getAccount(hotpId))
        assertEquals(2, runner.transactions)
        assertFalse(first.toString().contains("254676"))
    }

    @Test
    fun nextHotpCode_usesTheStoredRowNotACopyReadEarlier() = runBlocking {
        val hotpId = repository.add(hotp)
        val cached = checkNotNull(repository.getAccount(hotpId))
        // A backup import replaces the row (new secret, counter 50) after the copy was read.
        val newSecret = Base32.decode("JBSWY3DPEHPK3PXP")
        val replaced = hotp.copy(secret = newSecret, counter = 50)
        dao.put(replaced.toEntity(hotpId, cipher.encrypt(newSecret, accountAssociatedData(hotpId)), 1_000))

        val result = repository.nextHotpCode(hotpId)

        assertEquals(NextHotpCodeResult.Code(Hotp(newSecret).generate(50), 51), result)
        assertNotEquals(Hotp(cached.secret).generate(cached.counter), (result as NextHotpCodeResult.Code).code)
        assertEquals(replaced.copy(counter = 51), repository.getAccount(hotpId))
    }

    @Test
    fun nextHotpCode_totpOrUnknownRow_writesNothing() = runBlocking {
        val totpId = repository.add(totp)
        val before = dao.stored(totpId)

        assertSame(NextHotpCodeResult.NotHotp, repository.nextHotpCode(totpId))
        assertSame(NextHotpCodeResult.NotFound, repository.nextHotpCode("missing"))
        assertSame(before, dao.stored(totpId))
        assertTrue(cipher.decrypted.isEmpty())
    }

    @Test
    fun nextHotpCode_atTheCounterLimit_writesNothingAndDecryptsNothing() = runBlocking {
        val hotpId = repository.add(hotp.copy(counter = Long.MAX_VALUE))
        val before = dao.stored(hotpId)

        assertSame(NextHotpCodeResult.LimitReached, repository.nextHotpCode(hotpId))
        assertSame(before, dao.stored(hotpId))
        assertTrue(cipher.decrypted.isEmpty())
    }

    @Test
    fun nextHotpCode_oneBelowTheLimit_worksOnceThenReachesTheLimit() = runBlocking {
        val hotpId = repository.add(hotp.copy(counter = Long.MAX_VALUE - 1))

        val result = repository.nextHotpCode(hotpId)

        assertEquals(NextHotpCodeResult.Code(Hotp(hotp.secret).generate(Long.MAX_VALUE - 1), Long.MAX_VALUE), result)
        assertSame(NextHotpCodeResult.LimitReached, repository.nextHotpCode(hotpId))
        assertEquals(Long.MAX_VALUE, dao.stored(hotpId).counter)
    }

    @Test
    fun nextHotpCode_unreadableSecret_failsAndWritesNothing() = runBlocking {
        val hotpId = repository.add(hotp)
        val blob = dao.stored(hotpId).secretBlob.copyOf()
        blob[blob.lastIndex] = (blob[blob.lastIndex].toInt() xor 1).toByte()
        dao.put(dao.stored(hotpId).copyWith(secretBlob = blob))
        val tampered = dao.stored(hotpId)

        assertSame(NextHotpCodeResult.Failed, repository.nextHotpCode(hotpId))
        assertSame(tampered, dao.stored(hotpId))

        keyProvider.lost = true
        assertSame(NextHotpCodeResult.Failed, repository.nextHotpCode(hotpId))
        assertSame(tampered, dao.stored(hotpId))
    }

    @Test
    fun nextHotpCode_wipesTheDecryptedSecretOnSuccessAndOnAnInvalidRow() = runBlocking {
        val hotpId = repository.add(hotp)
        assertTrue(repository.nextHotpCode(hotpId) is NextHotpCodeResult.Code)
        assertTrue(cipher.decrypted.single().isWiped())

        cipher.decrypted.clear()
        val invalid = AccountEntity(hotpId, "Bank", "bob", "HOTP", "SHA1", 5, 30, 6, dao.stored(hotpId).secretBlob, 1_000)
        dao.put(invalid)
        assertSame(NextHotpCodeResult.Failed, repository.nextHotpCode(hotpId))
        assertTrue(cipher.decrypted.single().isWiped())
        assertSame(invalid, dao.stored(hotpId))
    }

    /** The list's load can be cancelled by a reload (collectLatest) while it decrypts. */
    @Test
    fun getAccount_cancelledDuringDecrypt_wipesTheSecret() = runBlocking {
        val totpId = repository.add(totp)
        var result: OtpAccount? = null
        val job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) { result = repository.getAccount(totpId) }
        cipher.onDecrypt = { job.cancel() }

        job.start()
        job.join()

        assertTrue(job.isCancelled)
        assertNull(result)
        assertTrue(cipher.decrypted.single().isWiped())
    }

    @Test
    fun nextHotpCode_cancelledDuringDecrypt_wipesTheSecretAndWritesNothing() = runBlocking {
        val hotpId = repository.add(hotp)
        var result: NextHotpCodeResult? = null
        val job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) { result = repository.nextHotpCode(hotpId) }
        cipher.onDecrypt = { job.cancel() }

        job.start()
        job.join()

        assertTrue(job.isCancelled)
        assertNull(result)
        assertTrue(cipher.decrypted.single().isWiped())
        assertEquals(5L, dao.stored(hotpId).counter)
    }

    @Test
    fun nextHotpCode_overlappingCalls_getConsecutiveCountersAndDifferentCodes() = runBlocking {
        val hotpId = repository.add(hotp)

        val results = (1..20).map { async(Dispatchers.Default) { repository.nextHotpCode(hotpId) } }.awaitAll()

        val codes = results.map { it as NextHotpCodeResult.Code }
        assertEquals((6L..25L).toList(), codes.map { it.counter }.sorted())
        assertEquals(codes.map { Hotp(hotp.secret).generate(it.counter - 1) }, codes.map { it.code })
        assertEquals(25L, dao.stored(hotpId).counter)
    }

    @Test
    fun nextHotpCode_withoutIsolation_compareAndSetNeverHandsOutACounterTwice() = runBlocking {
        val hotpId = repository.add(hotp)
        val unisolated = RoomAccountRepository(dao, cipher, PassThroughRunner)
        // Both calls read counter 5 before either writes.
        val bothRead = CountDownLatch(2)
        cipher.onDecrypt = {
            bothRead.countDown()
            check(bothRead.await(5, TimeUnit.SECONDS))
        }

        val results = (1..2).map { async(Dispatchers.Default) { unisolated.nextHotpCode(hotpId) } }.awaitAll()

        assertEquals(listOf(NextHotpCodeResult.Code("254676", 6)), results.filterIsInstance<NextHotpCodeResult.Code>())
        assertEquals(listOf(NextHotpCodeResult.Failed), results.filter { it !is NextHotpCodeResult.Code })
        assertEquals(6L, dao.stored(hotpId).counter)
        assertTrue(cipher.decrypted.all { it.isWiped() })
    }

    @Test
    fun observeAccounts_emitsSummariesWithoutSecrets() = runBlocking {
        val totpId = repository.add(totp)
        val hotpId = repository.add(hotp)

        val summaries = repository.observeAccounts().first()

        assertEquals(
            listOf(
                AccountSummary(totpId, "Example", "alice@example.com", OtpType.TOTP, OtpAlgorithm.SHA256, 8, 60, 0),
                AccountSummary(hotpId, "Bank", "bob", OtpType.HOTP, OtpAlgorithm.SHA1, 6, 30, 5),
            ),
            summaries,
        )
        assertTrue(AccountSummary::class.java.declaredFields.none { it.type == ByteArray::class.java })
        assertFalse(summaries.toString().contains(totpSecretText))
    }
}
