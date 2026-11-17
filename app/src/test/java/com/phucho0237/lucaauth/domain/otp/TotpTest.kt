package com.phucho0237.lucaauth.domain.otp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TotpTest {

    private fun seed(length: Int): ByteArray {
        val pattern = "1234567890"
        return String(CharArray(length) { pattern[it % pattern.length] })
            .toByteArray(Charsets.US_ASCII)
    }

    private val sha1Seed = seed(20)
    private val sha256Seed = seed(32)
    private val sha512Seed = seed(64)

    private data class Vector(val time: Long, val sha1: String, val sha256: String, val sha512: String)

    /** RFC 6238 Appendix B (8 digits, 30 s period, T0 = 0). */
    private val rfc6238Vectors = listOf(
        Vector(59L, "94287082", "46119246", "90693936"),
        Vector(1111111109L, "07081804", "68084774", "25091201"),
        Vector(1111111111L, "14050471", "67062674", "99943326"),
        Vector(1234567890L, "89005924", "91819424", "93441116"),
        Vector(2000000000L, "69279037", "90698825", "38618901"),
        Vector(20000000000L, "65353130", "77737706", "47863826"),
    )

    @Test
    fun seeds_matchRfc6238Lengths() {
        assertEquals("12345678901234567890", String(sha1Seed, Charsets.US_ASCII))
        assertEquals(32, sha256Seed.size)
        assertEquals(64, sha512Seed.size)
    }

    @Test
    fun generate_rfc6238AppendixB_sha1() {
        val totp = Totp(sha1Seed, OtpAlgorithm.SHA1, digits = 8)
        for (v in rfc6238Vectors) assertEquals("SHA1 @ ${v.time}", v.sha1, totp.generate(v.time))
    }

    @Test
    fun generate_rfc6238AppendixB_sha256() {
        val totp = Totp(sha256Seed, OtpAlgorithm.SHA256, digits = 8)
        for (v in rfc6238Vectors) assertEquals("SHA256 @ ${v.time}", v.sha256, totp.generate(v.time))
    }

    @Test
    fun generate_rfc6238AppendixB_sha512() {
        val totp = Totp(sha512Seed, OtpAlgorithm.SHA512, digits = 8)
        for (v in rfc6238Vectors) assertEquals("SHA512 @ ${v.time}", v.sha512, totp.generate(v.time))
    }

    @Test
    fun generate_defaultsToSixDigitsSha1Period30() {
        val totp = Totp(sha1Seed)
        assertEquals(6, totp.digits)
        assertEquals(30, totp.period)
        assertEquals(OtpAlgorithm.SHA1, totp.algorithm)
        assertEquals("287082", totp.generate(59))
    }

    @Test
    fun generate_changesExactlyAtPeriodBoundary() {
        val totp = Totp(sha1Seed)
        val hotp = Hotp(sha1Seed)
        assertEquals(hotp.generate(0), totp.generate(0))
        assertEquals(hotp.generate(0), totp.generate(29))
        assertEquals(hotp.generate(1), totp.generate(30))
        assertNotEquals(totp.generate(29), totp.generate(30))
    }

    @Test
    fun generate_honorsCustomPeriod() {
        val totp = Totp(sha1Seed, period = 60)
        assertEquals(Hotp(sha1Seed).generate(1), totp.generate(119))
    }

    @Test
    fun generate_rejectsTimeBeforeEpoch() {
        assertThrows(IllegalArgumentException::class.java) { Totp(sha1Seed).generate(-1) }
    }

    @Test
    fun init_rejectsInvalidDigits() {
        for (digits in listOf(5, 9)) {
            assertThrows("digits $digits", IllegalArgumentException::class.java) {
                Totp(sha1Seed, digits = digits)
            }
        }
    }

    @Test
    fun init_rejectsNonPositivePeriod() {
        for (period in listOf(0, -1, -30)) {
            assertThrows("period $period", IllegalArgumentException::class.java) {
                Totp(sha1Seed, period = period)
            }
        }
    }

    @Test
    fun secondsRemaining_atBoundaries() {
        val totp = Totp(sha1Seed)
        assertEquals(30, totp.secondsRemaining(0))
        assertEquals(29, totp.secondsRemaining(1))
        assertEquals(1, totp.secondsRemaining(29))
        assertEquals(30, totp.secondsRemaining(30))
        assertEquals(1, totp.secondsRemaining(59))
        assertEquals(30, totp.secondsRemaining(60))
    }

    @Test
    fun secondsRemaining_usesFloorModForNegativeTime() {
        val totp = Totp(sha1Seed)
        assertEquals(1, totp.secondsRemaining(-1))
        assertEquals(30, totp.secondsRemaining(-30))
    }

    @Test
    fun secondsRemaining_honorsCustomPeriod() {
        val totp = Totp(sha1Seed, period = 60)
        assertEquals(60, totp.secondsRemaining(0))
        assertEquals(31, totp.secondsRemaining(29))
        assertEquals(1, totp.secondsRemaining(59))
    }
}
