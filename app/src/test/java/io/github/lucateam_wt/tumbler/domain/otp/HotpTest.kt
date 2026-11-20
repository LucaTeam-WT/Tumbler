package io.github.lucateam_wt.tumbler.domain.otp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HotpTest {

    private val secret = "12345678901234567890".toByteArray(Charsets.US_ASCII)

    /** RFC 4226 Appendix D. */
    @Test
    fun generate_rfc4226AppendixD() {
        val expected = listOf(
            "755224", "287082", "359152", "969429", "338314",
            "254676", "287922", "162583", "399871", "520489",
        )
        val hotp = Hotp(secret)
        expected.forEachIndexed { counter, code ->
            assertEquals("counter $counter", code, hotp.generate(counter.toLong()))
        }
    }

    @Test
    fun generate_supportsSevenAndEightDigits() {
        // Same truncated value as the 6-digit vector for counter 0 (1284755224).
        assertEquals("4755224", Hotp(secret, digits = 7).generate(0))
        assertEquals("84755224", Hotp(secret, digits = 8).generate(0))
    }

    @Test
    fun init_rejectsDigitsOutOfRange() {
        for (digits in listOf(0, 5, 9, 10, -1)) {
            assertThrows("digits $digits", IllegalArgumentException::class.java) {
                Hotp(secret, digits = digits)
            }
        }
    }

    @Test
    fun init_rejectsEmptySecret() {
        assertThrows(IllegalArgumentException::class.java) { Hotp(ByteArray(0)) }
    }

    @Test
    fun init_copiesSecret() {
        val mutable = secret.copyOf()
        val hotp = Hotp(mutable)
        mutable.fill(0)
        assertEquals("755224", hotp.generate(0))
    }

    @Test
    fun generate_rejectsNegativeCounter() {
        assertThrows(IllegalArgumentException::class.java) { Hotp(secret).generate(-1) }
    }
}
