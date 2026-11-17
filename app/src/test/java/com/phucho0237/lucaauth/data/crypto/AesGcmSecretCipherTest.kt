package com.phucho0237.lucaauth.data.crypto

import com.phucho0237.lucaauth.domain.crypto.SecretCipherException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.security.KeyStoreException
import java.security.ProviderException
import javax.crypto.KeyGenerator
import kotlin.random.Random

class AesGcmSecretCipherTest {

    private fun newKeyProvider(): KeyProvider {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return KeyProvider { key }
    }

    private val cipher = AesGcmSecretCipher(newKeyProvider())
    private val plaintextText = "JBSWY3DPEHPK3PXP-super-secret"
    private val plaintext = plaintextText.toByteArray(Charsets.UTF_8)

    private fun assertDecryptFails(payload: ByteArray, aad: ByteArray = ByteArray(0)) {
        val error = assertThrows(SecretCipherException::class.java) { cipher.decrypt(payload, aad) }
        val message = error.message.orEmpty()
        assertFalse(message.contains(plaintextText))
        assertFalse(message.contains(plaintext.contentToString()))
    }

    @Test
    fun roundTrip_variousSizes() {
        for (input in listOf(plaintext, ByteArray(0), Random(42).nextBytes(1024))) {
            assertArrayEquals(input, cipher.decrypt(cipher.encrypt(input)))
        }
    }

    @Test
    fun encrypt_writesVersionAndIvHeader() {
        val payload = cipher.encrypt(plaintext)
        assertEquals(1, payload[0].toInt())
        assertEquals(12, payload[1].toInt())
        assertEquals(2 + 12 + plaintext.size + 16, payload.size)
    }

    @Test
    fun encrypt_usesFreshIvEachTime() {
        val first = cipher.encrypt(plaintext)
        val second = cipher.encrypt(plaintext)
        assertFalse(first.contentEquals(second))
        assertFalse(first.copyOfRange(2, 14).contentEquals(second.copyOfRange(2, 14)))
    }

    @Test
    fun encrypt_doesNotModifyCallerArrays() {
        val input = plaintext.copyOf()
        val aad = byteArrayOf(1, 2, 3)
        cipher.encrypt(input, aad)
        assertArrayEquals(plaintext, input)
        assertArrayEquals(byteArrayOf(1, 2, 3), aad)
    }

    @Test
    fun decrypt_rejectsAnySingleByteTamper() {
        val payload = cipher.encrypt(plaintext)
        // Covers version (0), IV length (1), IV (2..13), ciphertext and tag (last 16).
        for (index in payload.indices) {
            val tampered = payload.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
            assertDecryptFails(tampered)
        }
    }

    @Test
    fun decrypt_rejectsMalformedPayloads() {
        val payload = cipher.encrypt(plaintext)
        assertDecryptFails(ByteArray(0))
        assertDecryptFails(payload.copyOf(1))
        assertDecryptFails(payload.copyOf(2 + 12 + 15))
        assertDecryptFails(payload.copyOf(payload.size - 1))
        assertDecryptFails(payload.copyOf().also { it[0] = 2 })
        assertDecryptFails(payload.copyOf().also { it[1] = 16 })
        assertDecryptFails(payload + ByteArray(70_000))
    }

    @Test
    fun encrypt_rejectsOversizedPlaintext() {
        assertThrows(SecretCipherException::class.java) { cipher.encrypt(ByteArray(64 * 1024 + 1)) }
    }

    @Test
    fun decrypt_failsWithWrongKey() {
        val payload = cipher.encrypt(plaintext)
        val error = assertThrows(SecretCipherException::class.java) {
            AesGcmSecretCipher(newKeyProvider()).decrypt(payload)
        }
        assertFalse(error.message.orEmpty().contains(plaintextText))
    }

    @Test
    fun associatedData_mustMatch() {
        val aad = "account-1".toByteArray()
        val payload = cipher.encrypt(plaintext, aad)
        assertArrayEquals(plaintext, cipher.decrypt(payload, "account-1".toByteArray()))
        assertDecryptFails(payload, "account-2".toByteArray())
        assertDecryptFails(payload)
        assertDecryptFails(cipher.encrypt(plaintext), aad)
    }

    @Test
    fun keyProviderFailure_becomesSecretCipherException() {
        val failing = AesGcmSecretCipher { throw KeyStoreException("boom") }
        assertThrows(SecretCipherException::class.java) { failing.encrypt(plaintext) }
        assertThrows(SecretCipherException::class.java) { failing.decrypt(cipher.encrypt(plaintext)) }
    }

    @Test
    fun keyProviderIoException_becomesSecretCipherException() {
        val failing = AesGcmSecretCipher { throw IOException("boom") }
        assertThrows(SecretCipherException::class.java) { failing.encrypt(plaintext) }
        assertThrows(SecretCipherException::class.java) { failing.decrypt(cipher.encrypt(plaintext)) }
    }

    @Test
    fun keyProviderProviderException_becomesSecretCipherException() {
        val failing = AesGcmSecretCipher { throw ProviderException("boom") }
        assertThrows(SecretCipherException::class.java) { failing.encrypt(plaintext) }
        assertThrows(SecretCipherException::class.java) { failing.decrypt(cipher.encrypt(plaintext)) }
    }
}
