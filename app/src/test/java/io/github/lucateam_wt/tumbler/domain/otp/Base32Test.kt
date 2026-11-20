package io.github.lucateam_wt.tumbler.domain.otp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Base32Test {

    private fun decodeToString(input: String) = String(Base32.decode(input), Charsets.US_ASCII)

    /** RFC 4648 section 10 test vectors. */
    @Test
    fun decode_rfc4648Vectors() {
        val vectors = listOf(
            "" to "",
            "MY======" to "f",
            "MZXQ====" to "fo",
            "MZXW6===" to "foo",
            "MZXW6YQ=" to "foob",
            "MZXW6YTB" to "fooba",
            "MZXW6YTBOI======" to "foobar",
        )
        for ((encoded, expected) in vectors) {
            assertEquals("decode($encoded)", expected, decodeToString(encoded))
        }
    }

    @Test
    fun decode_withoutPadding() {
        assertEquals("f", decodeToString("MY"))
        assertEquals("foobar", decodeToString("MZXW6YTBOI"))
    }

    @Test
    fun decode_isCaseInsensitive() {
        assertEquals("foobar", decodeToString("mzxw6ytboi======"))
        assertEquals("foobar", decodeToString("MzXw6YtBoI"))
    }

    @Test
    fun decode_ignoresSpacesAndHyphens() {
        assertEquals("foobar", decodeToString("MZXW 6YTB OI"))
        assertEquals("foobar", decodeToString("mzxw-6ytb-oi=="))
        assertEquals("foobar", decodeToString("  MZXW6YTBOI  "))
    }

    @Test
    fun decode_rfc6238Sha1Seed() {
        assertArrayEquals(
            "12345678901234567890".toByteArray(Charsets.US_ASCII),
            Base32.decode("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"),
        )
    }

    @Test
    fun decode_rejectsCharactersOutsideAlphabet() {
        for (input in listOf("MZXW0YTB", "MZXW1YTB", "MZXW8YTB", "MZXW9YTB", "MZXW!YTB", "MZXW_YTB")) {
            assertThrows(input, IllegalArgumentException::class.java) { Base32.decode(input) }
        }
    }

    @Test
    fun decode_rejectsPaddingInTheMiddle() {
        assertThrows(IllegalArgumentException::class.java) { Base32.decode("MY==MZXQ") }
    }

    @Test
    fun decode_rejectsImpossibleLengths() {
        for (input in listOf("M", "MZX", "MZXW6Y")) {
            assertThrows(input, IllegalArgumentException::class.java) { Base32.decode(input) }
        }
    }
}
