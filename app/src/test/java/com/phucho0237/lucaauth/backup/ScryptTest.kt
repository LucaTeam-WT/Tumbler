package com.phucho0237.lucaauth.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScryptTest {

    private fun hex(text: String): ByteArray {
        val digits = text.filterNot { it.isWhitespace() }
        return ByteArray(digits.length / 2) { digits.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    private fun derive(password: String, salt: String, params: ScryptParams): ByteArray =
        BouncyCastleScrypt.derive(ascii(password), ascii(salt), params, 64)

    // RFC 7914 section 12, vectors 1-3, copied from the RFC text. Vector 4
    // (N = 2^20, r = 8) needs 1 GiB and is left out.

    @Test
    fun rfc7914_vector1_emptyPasswordAndSalt() {
        val expected = hex(
            "77 d6 57 62 38 65 7b 20 3b 19 ca 42 c1 8a 04 97" +
                "f1 6b 48 44 e3 07 4a e8 df df fa 3f ed e2 14 42" +
                "fc d0 06 9d ed 09 48 f8 32 6a 75 3a 0f c8 1f 17" +
                "e8 d3 e0 fb 2e 0d 36 28 cf 35 e2 0c 38 d1 89 06",
        )
        assertArrayEquals(expected, derive("", "", ScryptParams(log2N = 4, r = 1, p = 1)))
    }

    @Test
    fun rfc7914_vector2_passwordNaCl() {
        val expected = hex(
            "fd ba be 1c 9d 34 72 00 78 56 e7 19 0d 01 e9 fe" +
                "7c 6a d7 cb c8 23 78 30 e7 73 76 63 4b 37 31 62" +
                "2e af 30 d9 2e 22 a3 88 6f f1 09 27 9d 98 30 da" +
                "c7 27 af b9 4a 83 ee 6d 83 60 cb df a2 cc 06 40",
        )
        assertArrayEquals(expected, derive("password", "NaCl", ScryptParams(log2N = 10, r = 8, p = 16)))
    }

    @Test
    fun rfc7914_vector3_pleaseletmein() {
        val expected = hex(
            "70 23 bd cb 3a fd 73 48 46 1c 06 cd 81 fd 38 eb" +
                "fd a8 fb ba 90 4f 8e 3e a9 b5 43 f6 54 5d a1 f2" +
                "d5 43 29 55 61 3f 0f cf 62 d4 97 05 24 2a 9a f9" +
                "e6 1e 85 dc 0d 65 1e 40 df cf 01 7b 45 57 58 87",
        )
        assertArrayEquals(
            expected,
            derive("pleaseletmein", "SodiumChloride", ScryptParams(log2N = 14, r = 8, p = 1)),
        )
    }

    @Test
    fun derive_doesNotModifyInputs() {
        val password = ascii("password")
        val salt = ascii("NaCl")
        BouncyCastleScrypt.derive(password, salt, ScryptParams(log2N = 4, r = 1, p = 1), 32)
        assertArrayEquals(ascii("password"), password)
        assertArrayEquals(ascii("NaCl"), salt)
    }

    @Test
    fun defaults_areN65536R8P2AndAllowed() {
        val params = ScryptParams.DEFAULT
        assertEquals(65_536, params.n)
        assertEquals(8, params.r)
        assertEquals(2, params.p)
        assertTrue(params.isAllowedInBackup)
        assertEquals(64L * 1024 * 1024, 128L * params.n * params.r)
    }

    @Test
    fun bounds_acceptEdges() {
        assertTrue(ScryptParams.isAllowedInBackup(1L shl 14, 1, 1))
        assertTrue(ScryptParams.isAllowedInBackup(1L shl 16, 8, 4))
        assertTrue(ScryptParams.isAllowedInBackup(1L shl 16, 2, 1))
        assertTrue(ScryptParams.isAllowedInBackup(1L shl 14, 8, 4))
        assertTrue(ScryptParams.isAllowedInBackup(1L shl 15, 1, 4))
    }

    @Test
    fun bounds_rejectN() {
        assertFalse(ScryptParams.isAllowedInBackup(1L shl 13, 8, 1))
        assertFalse(ScryptParams.isAllowedInBackup(1L shl 17, 2, 1))
        assertFalse(ScryptParams.isAllowedInBackup(1L shl 17, 8, 2))
        assertFalse(ScryptParams.isAllowedInBackup(1L shl 18, 1, 1))
        assertFalse(ScryptParams.isAllowedInBackup(0, 8, 1))
        assertFalse(ScryptParams.isAllowedInBackup(-(1L shl 16), 8, 1))
        assertFalse(ScryptParams.isAllowedInBackup(Long.MIN_VALUE, 8, 1))
    }

    @Test
    fun bounds_rejectNNotPowerOfTwo() {
        // The file stores log2(N), so it cannot express these; the check rejects them anyway.
        assertFalse(ScryptParams.isAllowedInBackup(3L shl 14, 1, 1))
        assertFalse(ScryptParams.isAllowedInBackup((1L shl 16) + 1, 8, 1))
        assertFalse(ScryptParams.isAllowedInBackup((1L shl 17) - 1, 1, 1))
    }

    @Test
    fun bounds_rejectRAndP() {
        assertFalse(ScryptParams.isAllowedInBackup(1L shl 14, 0, 1))
        assertFalse(ScryptParams.isAllowedInBackup(1L shl 14, 9, 1))
        assertFalse(ScryptParams.isAllowedInBackup(1L shl 14, 16, 1))
        assertFalse(ScryptParams.isAllowedInBackup(1L shl 14, 17, 1))
        assertFalse(ScryptParams.isAllowedInBackup(1L shl 14, -1, 1))
        assertFalse(ScryptParams.isAllowedInBackup(1L shl 14, 1, 0))
        assertFalse(ScryptParams.isAllowedInBackup(1L shl 14, 1, 5))
        assertFalse(ScryptParams.isAllowedInBackup(1L shl 14, 1, -1))
    }

    @Test
    fun bounds_memoryNeverExceeds64MiB() {
        // The writer's defaults sit exactly at the cap; the old 128 MiB maximum is rejected.
        assertEquals(64L * 1024 * 1024, ScryptParams.MAX_MEMORY_BYTES)
        assertTrue(ScryptParams.isAllowedInBackup(1L shl 16, 8, 1))
        assertFalse(ScryptParams.isAllowedInBackup(1L shl 17, 8, 1))
        assertFalse(ScryptParams.isAllowedInBackup(1L shl 16, 16, 1))
        for (log2N in 1..30) for (r in 1..32) {
            if (ScryptParams.isAllowedInBackup(1L shl log2N, r, 1)) {
                assertTrue("2^$log2N, r = $r", 128L * (1L shl log2N) * r <= 64L * 1024 * 1024)
            }
        }
    }

    @Test
    fun bounds_rejectRfc7914LimitForROne() {
        // RFC 7914 requires N < 2^(128 * r / 8); Bouncy Castle throws for r = 1, N >= 2^16.
        assertFalse(ScryptParams.isAllowedInBackup(1L shl 16, 1, 1))
        assertTrue(ScryptParams.isAllowedInBackup(1L shl 15, 1, 1))
        assertTrue(ScryptParams.isAllowedInBackup(1L shl 16, 2, 1))
        assertThrows(IllegalArgumentException::class.java) {
            BouncyCastleScrypt.derive(ascii("p"), ascii("s"), ScryptParams(log2N = 16, r = 1, p = 1), 32)
        }
    }

    @Test
    fun bounds_everyAllowedCombinationIsAcceptedByBouncyCastleChecks() {
        // Bouncy Castle validates N, r and p before allocating; replay its documented
        // preconditions for every combination the reader allows.
        var allowed = 0
        for (log2N in 1..30) for (r in 1..32) for (p in 1..8) {
            if (!ScryptParams.isAllowedInBackup(1L shl log2N, r, p)) continue
            allowed++
            val n = 1 shl log2N
            assertTrue(n > 1 && !(r == 1 && n >= 65_536) && p <= Int.MAX_VALUE / (128 * r * 8))
        }
        // (r values) x 4 p values: N = 2^14: r = 1..8, 2^15: r = 1..8, 2^16: r = 2..8
        // (r = 1 breaks the RFC rule).
        assertEquals((8 + 8 + 7) * 4, allowed)
    }

    @Test
    fun params_rejectValuesScryptCannotUse() {
        assertThrows(IllegalArgumentException::class.java) { ScryptParams(log2N = 0, r = 1, p = 1) }
        assertThrows(IllegalArgumentException::class.java) { ScryptParams(log2N = 31, r = 1, p = 1) }
        assertThrows(IllegalArgumentException::class.java) { ScryptParams(log2N = 14, r = 0, p = 1) }
        assertThrows(IllegalArgumentException::class.java) { ScryptParams(log2N = 14, r = 1, p = 0) }
    }

    @Test
    fun params_isAllowedInBackupMatchesBounds() {
        assertFalse(ScryptParams(log2N = 4, r = 1, p = 1).isAllowedInBackup)
        assertFalse(ScryptParams(log2N = 14, r = 8, p = 16).isAllowedInBackup)
        assertTrue(ScryptParams(log2N = 14, r = 1, p = 1).isAllowedInBackup)
    }
}
