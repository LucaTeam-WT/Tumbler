package com.phucho0237.lucaauth.data.repository

import com.phucho0237.lucaauth.data.crypto.AesGcmSecretCipher
import com.phucho0237.lucaauth.data.crypto.KeyProvider
import com.phucho0237.lucaauth.domain.crypto.KeyUnavailableException
import com.phucho0237.lucaauth.domain.crypto.SecretCipherException
import com.phucho0237.lucaauth.domain.model.AccountSummary
import com.phucho0237.lucaauth.domain.model.OtpAccount
import com.phucho0237.lucaauth.domain.model.OtpType
import com.phucho0237.lucaauth.domain.otp.OtpAlgorithm
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
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

    private val keyProvider = TestKeyProvider()
    private val dao = FakeAccountDao()
    private var nextId = 0
    private var now = 1_000L
    private val repository = RoomAccountRepository(
        dao = dao,
        cipher = AesGcmSecretCipher(keyProvider),
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
    fun updateCounter_hotpRow_changesOnlyTheCounter() = runBlocking {
        val hotpId = repository.add(hotp)

        repository.updateCounter(hotpId, 42)

        assertEquals(hotp.copy(counter = 42), repository.getAccount(hotpId))
    }

    @Test
    fun updateCounter_totpRow_throwsAndChangesNothing() = runBlocking {
        val totpId = repository.add(totp)

        val error = assertThrows(NoSuchElementException::class.java) {
            runBlocking { repository.updateCounter(totpId, 42) }
        }

        assertFalse(error.message.orEmpty().contains(totpId))
        assertEquals(totp, repository.getAccount(totpId))
    }

    @Test
    fun updateCounter_unknownId_throws() = runBlocking {
        repository.add(hotp)

        val error = assertThrows(NoSuchElementException::class.java) {
            runBlocking { repository.updateCounter("missing", 42) }
        }
        assertFalse(error.message.orEmpty().contains("missing"))
    }

    @Test
    fun updateCounter_negative_throwsAndChangesNothing() = runBlocking {
        val hotpId = repository.add(hotp)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.updateCounter(hotpId, -1) }
        }
        assertEquals(hotp, repository.getAccount(hotpId))
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
