package io.github.lucateam_wt.tumbler.data.crypto

import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipherException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvelopeFormatTest {

    @Test
    fun `sizes match the documented layout`() {
        assertEquals(32, EnvelopeFormat.DATA_KEY_BYTES)
        assertEquals(62, EnvelopeFormat.WRAPPED_DATA_KEY_BYTES)
        assertEquals(30, EnvelopeFormat.MIN_SECRET_PAYLOAD_BYTES)
        assertEquals(30 + 64 * 1024, EnvelopeFormat.MAX_SECRET_PAYLOAD_BYTES)
        assertEquals(0, EnvelopeFormat.LEGACY_KEY_VERSION)
    }

    @Test
    fun `secret associated data is purpose, zero, big-endian version and the id`() {
        val expected = "TumblerSecret".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0, 0x01, 0x02, 0x03, 0x04) +
            "acc-1".toByteArray(Charsets.UTF_8)
        assertArrayEquals(expected, EnvelopeFormat.secretAssociatedData(0x01020304, "acc-1"))
    }

    @Test
    fun `wrap associated data is purpose, zero, big-endian version and the alias`() {
        val expected = "TumblerDataKey".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0, 0, 0, 0, 1) +
            "lucaauth_wrap_key_v1".toByteArray(Charsets.UTF_8)
        assertArrayEquals(expected, EnvelopeFormat.wrapAssociatedData(1, EnvelopeFormat.WRAP_KEY_ALIAS))
    }

    @Test
    fun `associated data matches the examples in docs storage-format`() {
        assertEquals(
            "54756d626c6572536563726574" + "00" + "00000001" + "6163632d31",
            EnvelopeFormat.secretAssociatedData(1, "acc-1").toHex(),
        )
        assertEquals(
            "54756d626c6572446174614b6579" + "00" + "00000001" + "6c756361617574685f777261705f6b65795f7631",
            EnvelopeFormat.wrapAssociatedData(1, "lucaauth_wrap_key_v1").toHex(),
        )
    }

    @Test
    fun `the two purposes never produce the same associated data`() {
        assertFalse(
            EnvelopeFormat.secretAssociatedData(1, "x")
                .contentEquals(EnvelopeFormat.wrapAssociatedData(1, "x")),
        )
    }

    @Test
    fun `versions below 1 are refused`() {
        for (version in listOf(0, -1, Int.MIN_VALUE)) {
            assertThrows(SecretCipherException::class.java) { EnvelopeFormat.secretAssociatedData(version, "a") }
            assertThrows(SecretCipherException::class.java) { EnvelopeFormat.wrapAssociatedData(version, "a") }
        }
    }

    @Test
    fun `ids are strict UTF-8 of at most 255 bytes`() {
        assertEquals(
            "TumblerSecret".length + 5 + 255,
            EnvelopeFormat.secretAssociatedData(1, "a".repeat(255)).size,
        )
        assertThrows(SecretCipherException::class.java) { EnvelopeFormat.secretAssociatedData(1, "a".repeat(256)) }
        // 128 two-byte characters are 256 bytes.
        assertThrows(SecretCipherException::class.java) { EnvelopeFormat.secretAssociatedData(1, "é".repeat(128)) }
        // An unpaired surrogate is refused, not replaced by '?', so two ids never share associated data.
        assertThrows(SecretCipherException::class.java) { EnvelopeFormat.secretAssociatedData(1, "a\uD800") }
        assertThrows(SecretCipherException::class.java) { EnvelopeFormat.secretAssociatedData(1, "\uDC00a") }
    }

    @Test
    fun `every version has its own wrapping alias`() {
        assertEquals("lucaauth_wrap_key_v1", EnvelopeFormat.wrapAlias(EnvelopeFormat.WRAP_KEY_ALIAS, 1))
        assertEquals("lucaauth_wrap_key_v1_k2", EnvelopeFormat.wrapAlias(EnvelopeFormat.WRAP_KEY_ALIAS, 2))
        assertEquals("base_k17", EnvelopeFormat.wrapAlias("base", 17))
        assertThrows(IllegalArgumentException::class.java) { EnvelopeFormat.wrapAlias("base", 0) }
    }

    @Test
    fun `no data key version is ever wrapped by the legacy key`() {
        val aliases = (1..10_000).map { EnvelopeFormat.wrapAlias(EnvelopeFormat.WRAP_KEY_ALIAS, it) }
        assertFalse(AndroidKeystoreKeyProvider.DEFAULT_ALIAS in aliases)
        assertEquals(aliases.size, aliases.toSet().size)
    }

    @Test
    fun `a wrapped data key must have the exact size and header`() {
        val good = ByteArray(62).also { it[0] = 1; it[1] = 12 }
        assertTrue(EnvelopeFormat.isWellFormedWrappedKey(good))
        assertFalse(EnvelopeFormat.isWellFormedWrappedKey(good.copyOf(61)))
        assertFalse(EnvelopeFormat.isWellFormedWrappedKey(good.copyOf(63)))
        assertFalse(EnvelopeFormat.isWellFormedWrappedKey(ByteArray(0)))
        assertFalse(EnvelopeFormat.isWellFormedWrappedKey(good.copyOf().also { it[0] = 2 }))
        assertFalse(EnvelopeFormat.isWellFormedWrappedKey(good.copyOf().also { it[1] = 11 }))
    }
}
