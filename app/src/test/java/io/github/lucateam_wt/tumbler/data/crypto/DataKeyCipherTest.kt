package io.github.lucateam_wt.tumbler.data.crypto

import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipherException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.security.SecureRandom

class DataKeyCipherTest {

    /** A lock regression in [DataKey] would hang instead of failing. */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private val random = SecureRandom()
    private val secret = "JBSWY3DPEHPK3PXP".toByteArray(Charsets.US_ASCII)

    /**
     * AES-256-GCM known answers from McGrew and Viega, "The Galois/Counter Mode of Operation
     * (GCM)", test cases 14 to 16 (also in the NIST GCM validation set), run through the same
     * seal and open code as the app. Test case 13 has an empty plaintext, which the format
     * refuses; it is checked separately.
     */
    private class Vector(val key: String, val nonce: String, val plaintext: String, val aad: String, val ciphertext: String, val tag: String)

    /** Test case 13: an empty plaintext, which the format refuses in both directions. */
    private val emptyVector = Vector("00".repeat(32), "00".repeat(12), "", "", "", "530f8afbc74536b9a963b4f1c4cb738b")

    private val vectors = listOf(
        // Test case 14
        Vector(
            "00".repeat(32), "00".repeat(12), "00".repeat(16), "",
            "cea7403d4d606b6e074ec5d3baf39d18", "d0d1c8a799996bf0265b98b5d48ab919",
        ),
        // Test case 15
        Vector(
            "feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308",
            "cafebabefacedbaddecaf888",
            "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72" +
                "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b391aafd255",
            "",
            "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa" +
                "8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662898015ad",
            "b094dac5d93471bdec1a502270e3cc6c",
        ),
        // Test case 16
        Vector(
            "feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308",
            "cafebabefacedbaddecaf888",
            "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72" +
                "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39",
            "feedfacedeadbeeffeedfacedeadbeefabaddad2",
            "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa" +
                "8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662",
            "76fc6ece0f4e1768cddf8853bb2d551b",
        ),
    )

    @Test
    fun `known answers seal to the documented payload`() {
        for (v in vectors) {
            val payload = DataKeyCipher.sealWith(v.key.hex(), v.nonce.hex(), v.plaintext.hex(), v.aad.hex())
            assertEquals("02" + "0c" + v.nonce + v.ciphertext + v.tag, payload.toHex())
        }
    }

    @Test
    fun `known answers open to the plaintext`() {
        for (v in vectors) {
            val payload = ("02" + "0c" + v.nonce + v.ciphertext + v.tag).hex()
            assertEquals(v.plaintext, DataKeyCipher.openWith(v.key.hex(), payload, v.aad.hex()).toHex())
        }
    }

    @Test
    fun `an empty secret is refused when sealing and an authentic empty payload when opening`() {
        val v = emptyVector
        assertEquals(
            "Plaintext empty",
            assertThrows(SecretCipherException::class.java) {
                DataKeyCipher.sealWith(v.key.hex(), v.nonce.hex(), ByteArray(0), v.aad.hex())
            }.message,
        )
        // 30 bytes with a valid tag (McGrew-Viega test case 13): refused before decryption, with
        // the same error as any other payload that is too short.
        val payload = ("02" + "0c" + v.nonce + v.ciphertext + v.tag).hex()
        assertEquals(30, payload.size)
        assertEquals(
            "Payload too short",
            assertThrows(SecretCipherException::class.java) { DataKeyCipher.openWith(v.key.hex(), payload, v.aad.hex()) }.message,
        )
        val key = DataKey(1, v.key.hex())
        assertEquals(
            "Payload too short",
            assertThrows(SecretCipherException::class.java) { DataKeyCipher.open(key, "acc-1", payload) }.message,
        )
    }

    @Test
    fun `a sealed secret carries the key version bound in its associated data`() {
        val raw = ByteArray(32).also(random::nextBytes)
        val sealed = DataKeyCipher.seal(DataKey(7, raw.copyOf()), "acc-1", secret, random)
        assertEquals(7, sealed.keyVersion)
        assertArrayEquals(secret, DataKeyCipher.openWith(raw, sealed.blob, EnvelopeFormat.secretAssociatedData(7, "acc-1")))
        assertThrows(SecretCipherException::class.java) {
            DataKeyCipher.openWith(raw, sealed.blob, EnvelopeFormat.secretAssociatedData(6, "acc-1"))
        }
        assertFalse(sealed.toString().contains(sealed.blob.toHex()))
    }

    @Test
    fun `seal and open round trip with the documented associated data`() {
        val key = dataKey()
        val payload = DataKeyCipher.seal(key, "acc-1", secret, random).blob
        assertEquals(30 + secret.size, payload.size)
        assertEquals(2, payload[0].toInt())
        assertEquals(12, payload[1].toInt())
        assertArrayEquals(secret, DataKeyCipher.open(key, "acc-1", payload))
        // The associated data is exactly EnvelopeFormat.secretAssociatedData.
        val raw = key.use { it.copyOf() }!!
        assertArrayEquals(secret, DataKeyCipher.openWith(raw, payload, EnvelopeFormat.secretAssociatedData(1, "acc-1")))
    }

    @Test
    fun `the largest secret round trips and a larger one is refused`() {
        val key = dataKey()
        val largest = ByteArray(64 * 1024) { it.toByte() }
        assertArrayEquals(largest, DataKeyCipher.open(key, "a", DataKeyCipher.seal(key, "a", largest, random).blob))
        assertThrows(SecretCipherException::class.java) { DataKeyCipher.seal(key, "a", ByteArray(64 * 1024 + 1), random) }
        assertThrows(SecretCipherException::class.java) { DataKeyCipher.seal(key, "a", ByteArray(0), random) }
    }

    @Test
    fun `changing any byte of the payload fails`() {
        val key = dataKey()
        val payload = DataKeyCipher.seal(key, "acc-1", secret, random).blob
        for (index in payload.indices) {
            val tampered = payload.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
            assertThrows("byte $index", SecretCipherException::class.java) { DataKeyCipher.open(key, "acc-1", tampered) }
        }
    }

    @Test
    fun `nonce, ciphertext and tag are each authenticated`() {
        val key = dataKey()
        val payload = DataKeyCipher.seal(key, "acc-1", secret, random).blob
        val nonceAt = 2
        val ciphertextAt = 14
        val tagAt = payload.size - 16
        for (index in listOf(nonceAt, nonceAt + 11, ciphertextAt, tagAt - 1, tagAt, payload.size - 1)) {
            val tampered = payload.copyOf().also { it[index] = (it[index].toInt() xor 0x80).toByte() }
            val e = assertThrows(SecretCipherException::class.java) { DataKeyCipher.open(key, "acc-1", tampered) }
            assertEquals("Decryption failed", e.message)
        }
    }

    @Test
    fun `the payload opens only for its account, key version and purpose`() {
        val raw = ByteArray(32).also(random::nextBytes)
        val key = DataKey(1, raw.copyOf())
        val payload = DataKeyCipher.seal(key, "acc-1", secret, random).blob

        // Another account id.
        assertThrows(SecretCipherException::class.java) { DataKeyCipher.open(key, "acc-2", payload) }
        assertThrows(SecretCipherException::class.java) { DataKeyCipher.open(key, "acc-1 ", payload) }
        // The same key bytes under another version.
        assertThrows(SecretCipherException::class.java) { DataKeyCipher.open(DataKey(2, raw.copyOf()), "acc-1", payload) }
        // The same version and id under the other purpose.
        assertThrows(SecretCipherException::class.java) {
            DataKeyCipher.openWith(raw, payload, EnvelopeFormat.wrapAssociatedData(1, "acc-1"))
        }
        // No associated data at all.
        assertThrows(SecretCipherException::class.java) { DataKeyCipher.openWith(raw, payload, ByteArray(0)) }
        // Another key.
        assertThrows(SecretCipherException::class.java) { DataKeyCipher.open(dataKey(), "acc-1", payload) }
    }

    @Test
    fun `truncated, oversized and wrongly framed payloads are refused before decryption`() {
        val key = dataKey()
        val payload = DataKeyCipher.seal(key, "acc-1", secret, random).blob
        for (size in listOf(0, 1, 2, 14, 29, 30)) {
            val e = assertThrows(SecretCipherException::class.java) { DataKeyCipher.open(key, "acc-1", payload.copyOf(size)) }
            assertEquals("Payload too short", e.message)
        }
        // One byte short of the real payload: framing is fine, the tag no longer matches.
        assertThrows(SecretCipherException::class.java) { DataKeyCipher.open(key, "acc-1", payload.copyOf(payload.size - 1)) }
        // One byte too many.
        assertThrows(SecretCipherException::class.java) { DataKeyCipher.open(key, "acc-1", payload.copyOf(payload.size + 1)) }

        val oversized = ByteArray(30 + 64 * 1024 + 1).also { it[0] = 2; it[1] = 12 }
        assertEquals(
            "Payload too large",
            assertThrows(SecretCipherException::class.java) { DataKeyCipher.open(key, "acc-1", oversized) }.message,
        )
        // A legacy payload (version 1) is not a data key payload.
        val legacy = payload.copyOf().also { it[0] = 1 }
        assertEquals(
            "Unsupported payload version",
            assertThrows(SecretCipherException::class.java) { DataKeyCipher.open(key, "acc-1", legacy) }.message,
        )
        val badNonceLength = payload.copyOf().also { it[1] = 16 }
        assertEquals(
            "Invalid nonce length",
            assertThrows(SecretCipherException::class.java) { DataKeyCipher.open(key, "acc-1", badNonceLength) }.message,
        )
    }

    @Test
    fun `wrong key and nonce lengths are refused`() {
        for (size in listOf(0, 16, 24, 31, 33)) {
            assertThrows(SecretCipherException::class.java) {
                DataKeyCipher.sealWith(ByteArray(size), ByteArray(12), secret, ByteArray(0))
            }
        }
        for (size in listOf(0, 8, 11, 13, 16)) {
            assertThrows(SecretCipherException::class.java) {
                DataKeyCipher.sealWith(ByteArray(32), ByteArray(size), secret, ByteArray(0))
            }
        }
        assertThrows(IllegalArgumentException::class.java) { DataKey(1, ByteArray(31)) }
        assertThrows(IllegalArgumentException::class.java) { DataKey(0, ByteArray(32)) }
    }

    @Test
    fun `every seal draws a fresh nonce`() {
        val key = dataKey()
        val nonces = HashSet<String>()
        val payloads = HashSet<String>()
        repeat(10_000) {
            val payload = DataKeyCipher.seal(key, "acc-1", secret, random).blob
            nonces += payload.copyOfRange(2, 14).toHex()
            payloads += payload.toHex()
        }
        assertEquals(10_000, nonces.size)
        assertEquals(10_000, payloads.size)
    }

    @Test
    fun `a wiped key neither seals nor opens`() {
        val key = dataKey()
        val payload = DataKeyCipher.seal(key, "acc-1", secret, random).blob
        key.wipe()
        assertTrue(key.isWiped)
        assertNull(key.use { it.copyOf() })
        assertThrows(SecretCipherException::class.java) { DataKeyCipher.seal(key, "acc-1", secret, random) }
        assertThrows(SecretCipherException::class.java) { DataKeyCipher.open(key, "acc-1", payload) }
    }

    @Test
    fun `failures and the key never print key material`() {
        val raw = ByteArray(32) { 0x5a }
        val key = DataKey(1, raw.copyOf())
        assertFalse(key.toString().contains("5a", ignoreCase = true))
        assertFalse(key.toString().contains("90"))
        val e = assertThrows(SecretCipherException::class.java) {
            DataKeyCipher.open(key, "acc-1", ByteArray(40).also { it[0] = 2; it[1] = 12 })
        }
        assertEquals("Decryption failed", e.message)
    }

    private fun dataKey() = DataKey(1, ByteArray(32).also(random::nextBytes))
}

internal fun String.hex(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { i -> substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
