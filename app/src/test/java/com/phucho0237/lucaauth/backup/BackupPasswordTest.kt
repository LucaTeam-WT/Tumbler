package com.phucho0237.lucaauth.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.text.Normalizer

class BackupPasswordTest {

    /** "ệ" precomposed (NFC, 1 code point) and decomposed (NFD, 3 code points), as escapes. */
    private val nfcE = "\u1EC7"
    private val nfdE = "e\u0323\u0302"

    /** One emoji outside the BMP: 1 code point, 2 UTF-16 units, 4 UTF-8 bytes. */
    private val emoji = "🔐"

    private fun check(text: String) = validatePassword(text.toCharArray())

    @Test
    fun validate_minimumIsTwelveCharacters() {
        assertEquals(PasswordCheck.TooShort(12), check(""))
        assertEquals(PasswordCheck.TooShort(12), check("a"))
        assertEquals(PasswordCheck.TooShort(12), check("abcdefghijk"))
        assertEquals(PasswordCheck.Ok, check("abcdefghijkl"))
        assertEquals(PasswordCheck.Ok, check("a".repeat(500)))
        assertEquals(12, MIN_PASSWORD_CHARACTERS)
    }

    @Test
    fun validate_countsCharactersNotBytes() {
        // 12 Vietnamese characters are 12 code points but more than 12 UTF-8 bytes.
        val vietnamese = "mật khẩu đây"
        assertEquals(12, vietnamese.codePointCount(0, vietnamese.length))
        assertEquals(PasswordCheck.Ok, check(vietnamese))
        assertEquals(PasswordCheck.TooShort(12), check(vietnamese.dropLast(1)))
    }

    @Test
    fun validate_countsCodePointsNotUtf16Units() {
        // 6 emoji are 12 UTF-16 units but only 6 characters.
        assertEquals(PasswordCheck.TooShort(12), check(emoji.repeat(6)))
        assertEquals(PasswordCheck.TooShort(12), check(emoji.repeat(11)))
        assertEquals(PasswordCheck.Ok, check(emoji.repeat(12)))
    }

    @Test
    fun validate_countsAfterNfcNormalization() {
        // 11 decomposed "ệ" are 33 code points as typed, 11 after NFC.
        assertEquals(PasswordCheck.TooShort(12), check(nfdE.repeat(11)))
        assertEquals(PasswordCheck.Ok, check(nfdE.repeat(12)))
        assertEquals(PasswordCheck.Ok, check(nfcE.repeat(12)))
    }

    @Test
    fun validate_rejectsUnpairedSurrogates() {
        assertEquals(PasswordCheck.InvalidCharacters, check("abcdefghijkl\uD83D"))
        assertEquals(PasswordCheck.InvalidCharacters, check("\uDD10abcdefghijkl"))
        assertEquals(PasswordCheck.InvalidCharacters, check("abcdef\uDD10\uD83Dghijkl"))
        assertEquals(PasswordCheck.InvalidCharacters, check("\uD83D"))
    }

    @Test
    fun validate_doesNotModifyInput() {
        val password = "abcdefghijkl".toCharArray()
        validatePassword(password)
        assertArrayEquals("abcdefghijkl".toCharArray(), password)
        val decomposed = nfdE.repeat(12).toCharArray()
        validatePassword(decomposed)
        assertArrayEquals(nfdE.repeat(12).toCharArray(), decomposed)
    }

    @Test
    fun confirmation_matchingIsOk() {
        val password = "correct horse battery staple"
        assertEquals(
            PasswordCheck.Ok,
            validatePasswordConfirmation(password.toCharArray(), password.toCharArray()),
        )
    }

    @Test
    fun confirmation_mismatchIsReported() {
        val password = "correct horse battery staple".toCharArray()
        listOf(
            "correct horse battery stapl",
            "correct horse battery staple ",
            "Correct horse battery staple",
            "",
            "correct horse battery staple\uD83D",
        ).forEach { confirmation ->
            assertEquals(confirmation, PasswordCheck.Mismatch, validatePasswordConfirmation(password, confirmation.toCharArray()))
        }
    }

    @Test
    fun confirmation_policyIsCheckedFirst() {
        assertEquals(PasswordCheck.TooShort(12), validatePasswordConfirmation("short".toCharArray(), "other".toCharArray()))
        assertEquals(PasswordCheck.TooShort(12), validatePasswordConfirmation("short".toCharArray(), "short".toCharArray()))
        assertEquals(
            PasswordCheck.InvalidCharacters,
            validatePasswordConfirmation("abcdefghijkl\uD83D".toCharArray(), "abcdefghijkl\uD83D".toCharArray()),
        )
    }

    @Test
    fun confirmation_nfcAndNfdFormsMatch() {
        // The same password typed with two keyboards that compose differently.
        assertEquals(
            PasswordCheck.Ok,
            validatePasswordConfirmation(nfcE.repeat(12).toCharArray(), nfdE.repeat(12).toCharArray()),
        )
    }

    @Test
    fun confirmation_doesNotModifyInputs() {
        val password = "correct horse battery staple".toCharArray()
        val confirmation = "correct horse battery staple".toCharArray()
        validatePasswordConfirmation(password, confirmation)
        assertArrayEquals("correct horse battery staple".toCharArray(), password)
        assertArrayEquals("correct horse battery staple".toCharArray(), confirmation)
    }

    @Test
    fun kdfInput_isNfcUtf8() {
        listOf("correct horse battery staple", "", nfcE, emoji, "Ngân hàng 例 مثال").forEach { text ->
            val expected = Normalizer.normalize(text, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)
            assertArrayEquals(text, expected, passwordKdfInput(text.toCharArray()))
        }
        assertArrayEquals(byteArrayOf(0xE1.toByte(), 0xBB.toByte(), 0x87.toByte()), passwordKdfInput(nfdE.toCharArray()))
        assertArrayEquals(
            byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x94.toByte(), 0x90.toByte()),
            passwordKdfInput(emoji.toCharArray()),
        )
    }

    @Test
    fun kdfInput_rejectsUnpairedSurrogates() {
        assertNull(passwordKdfInput("abc\uD83D".toCharArray()))
        assertNull(passwordKdfInput("\uDD10".toCharArray()))
    }

    @Test
    fun kdfInput_doesNotModifyInput() {
        val password = nfdE.repeat(3).toCharArray()
        passwordKdfInput(password)
        assertArrayEquals(nfdE.repeat(3).toCharArray(), password)
    }

    // Fast path: passwords whose chars are all below U+0300 skip java.text.Normalizer

    @Test
    fun everyCharBelowU0300_isNfcAloneAndInEveryPair() {
        // Alone: no decomposition. In pairs: no reordering and no composition. Every such
        // char has combining class 0, so longer strings cannot compose either.
        assertEquals(0x300, NFC_FAST_PATH_LIMIT.code)
        for (a in 0 until NFC_FAST_PATH_LIMIT.code) {
            val single = a.toChar().toString()
            assertEquals("U+%04X".format(a), single, Normalizer.normalize(single, Normalizer.Form.NFC))
            for (b in 0 until NFC_FAST_PATH_LIMIT.code) {
                val pair = String(charArrayOf(a.toChar(), b.toChar()))
                if (Normalizer.normalize(pair, Normalizer.Form.NFC) != pair) fail("U+%04X U+%04X is not NFC".format(a, b))
            }
        }
    }

    @Test
    fun fastPath_skipsTheNormalizerOnlyWhenEveryCharIsBelowU0300() {
        var calls = 0
        val counting: (CharArray) -> CharArray = { calls++; it.copyOf() }
        val fast = listOf("", "correct horse battery staple", "\u00DCn\u00EFc\u00F6d\u00E9 \u00C4\u00DF\u00F8", "\u0000\u007F\u00FF\u0100\u02FF")
        for (password in fast) {
            calls = 0
            val input = password.toCharArray()
            val normalized = normalizePassword(input, counting)
            assertEquals(password, 0, calls)
            assertArrayEquals(password.toCharArray(), normalized)
            if (input.isNotEmpty()) assertNotSame(input, normalized)
        }
        // At or above U+0300 anywhere, including combining marks, Vietnamese, emoji, CJK
        // and a lone surrogate: the normalizer runs.
        val slow = listOf("a\u0300", "\u0300", "abcdefghijk\u0301", nfcE, nfdE, emoji, "\u4F8B\u3048", "abc\uD83D")
        for (password in slow) {
            calls = 0
            normalizePassword(password.toCharArray(), counting)
            assertEquals(password, 1, calls)
        }
    }

    @Test
    fun firstCombiningMark_isNormalized() {
        // "a" + U+0300 composes to U+00E0 under NFC: U+0300 itself must not take the fast path.
        assertEquals("\u00E0", Normalizer.normalize("a\u0300", Normalizer.Form.NFC))
        assertArrayEquals(
            "\u00E0bcdefghijkl".toByteArray(Charsets.UTF_8),
            passwordKdfInput("a\u0300bcdefghijkl".toCharArray()),
        )
        assertEquals(PasswordCheck.TooShort(12), validatePassword("a\u0300bcdefghijk".toCharArray()))
    }

    // Maximum length: 1024 code points after NFC

    /** U+1F82, whose canonical decomposition is 4 code points: U+03B1 U+0313 U+0300 U+0345. */
    private val alphaNfc = "ᾂ"
    private val alphaNfd = "ᾂ"

    @Test
    fun validate_maximumIs1024Characters() {
        assertEquals(1024, MAX_PASSWORD_CHARACTERS)
        val cases = mapOf(
            "ASCII" to "a",
            "emoji (2 UTF-16 units)" to emoji,
            "precomposed" to nfcE,
            "decomposed, 3 code points each" to nfdE,
            "decomposed, 4 code points each" to alphaNfd,
        )
        for ((description, unit) in cases) {
            assertEquals("$description x 1024", PasswordCheck.Ok, check(unit.repeat(1024)))
            assertEquals("$description x 1025", PasswordCheck.TooLong(1024), check(unit.repeat(1025)))
        }
        // 1023 ASCII letters plus one emoji: 1024 code points, 1025 UTF-16 units.
        assertEquals(PasswordCheck.Ok, check("a".repeat(1023) + emoji))
    }

    @Test
    fun validate_hugeInputIsTooLongWithoutNormalizing() {
        val limit = MAX_PASSWORD_INPUT_UTF16_UNITS
        assertEquals(8192, limit)
        // Exactly at the input limit: normalized and counted (4 code points compose into 1).
        assertEquals(PasswordCheck.Ok, check(alphaNfd.repeat(1024)))
        assertEquals(PasswordCheck.TooLong(1024), check("a".repeat(limit)))
        // Above it: rejected before any copy, whatever the content.
        assertEquals(PasswordCheck.TooLong(1024), validatePassword(CharArray(limit + 1) { 'a' }))
        assertEquals(PasswordCheck.TooLong(1024), validatePassword(CharArray(limit + 1) { '\uD83D' }))
        assertEquals(PasswordCheck.TooLong(1024), validatePassword(CharArray(1 shl 24) { 'a' }))
    }

    @Test
    fun confirmation_tooLongIsReportedFirstAndATooLongConfirmationIsAMismatch() {
        val long = "a".repeat(1025).toCharArray()
        assertEquals(PasswordCheck.TooLong(1024), validatePasswordConfirmation(long, long))
        val ok = "a".repeat(1024)
        assertEquals(PasswordCheck.Ok, validatePasswordConfirmation(ok.toCharArray(), ok.toCharArray()))
        assertEquals(PasswordCheck.Mismatch, validatePasswordConfirmation(ok.toCharArray(), (ok + "a").toCharArray()))
    }

    @Test
    fun kdfInput_atTheCapIsEncodedAndAboveItIsNull() {
        assertArrayEquals(ByteArray(1024) { 'a'.code.toByte() }, passwordKdfInput("a".repeat(1024).toCharArray()))
        // 1024 emoji: 2048 UTF-16 units, 4096 UTF-8 bytes, the largest possible result.
        assertEquals(4096, passwordKdfInput(emoji.repeat(1024).toCharArray())?.size)
        assertArrayEquals(
            nfcE.repeat(1024).toByteArray(Charsets.UTF_8),
            passwordKdfInput(nfdE.repeat(1024).toCharArray()),
        )
        assertNull(passwordKdfInput("a".repeat(1025).toCharArray()))
        assertNull(passwordKdfInput(emoji.repeat(1025).toCharArray()))
        assertNull(passwordKdfInput(nfdE.repeat(1025).toCharArray()))
        assertNull(passwordKdfInput(CharArray(MAX_PASSWORD_INPUT_UTF16_UNITS + 1) { 'a' }))
    }

    @Test
    fun encodeUtf8_failsOnOverflowInsteadOfTruncating() {
        val text = "abcệ".toCharArray() // 6 UTF-8 bytes
        assertArrayEquals("abcệ".toByteArray(Charsets.UTF_8), encodeUtf8OrNull(text, capacity = 6))
        assertArrayEquals("abcệ".toByteArray(Charsets.UTF_8), encodeUtf8OrNull(text))
        // A buffer one byte too small, or far too small, is an overflow: no result, never
        // a truncated prefix such as "abc".
        for (capacity in listOf(0L, 1L, 3L, 5L)) assertNull("capacity $capacity", encodeUtf8OrNull(text, capacity))
        assertNull(encodeUtf8OrNull("🔐".toCharArray(), capacity = 3))
    }

    @Test
    fun encodeUtf8_rejectsCapacitiesOutsideTheIntRangeWithoutAllocating() {
        // The product chars * 3 overflows an Int above 715,827,882 chars; as a Long it is
        // checked instead (these calls would throw if they tried to allocate).
        val text = "abc".toCharArray()
        assertNull(encodeUtf8OrNull(text, capacity = Int.MAX_VALUE.toLong() + 1))
        assertNull(encodeUtf8OrNull(text, capacity = 715_827_883L * 3))
        assertNull(encodeUtf8OrNull(text, capacity = -1))
        assertNull(encodeUtf8OrNull(text, capacity = Long.MIN_VALUE))
    }

    @Test
    fun encodeUtf8_rejectsMalformedInput() {
        assertNull(encodeUtf8OrNull("ab\uD83D".toCharArray()))
        assertNull(encodeUtf8OrNull("\uDD10ab".toCharArray()))
    }

    @Test
    fun canonicalDecompositions_haveAtMostFourCodePoints() {
        // MAX_PASSWORD_INPUT_UTF16_UNITS relies on this: NFC can compose at most this many
        // code points into one. Checked on this JVM's Unicode data, for every code point.
        var longest = 0
        var code = 0
        while (code <= Character.MAX_CODE_POINT) {
            if (Character.getType(code) != Character.SURROGATE.toInt()) {
                val text = String(Character.toChars(code))
                if (!Normalizer.isNormalized(text, Normalizer.Form.NFD)) {
                    val nfd = Normalizer.normalize(text, Normalizer.Form.NFD)
                    longest = maxOf(longest, nfd.codePointCount(0, nfd.length))
                }
            }
            code++
        }
        assertEquals(MAX_CANONICAL_DECOMPOSITION_LENGTH, longest)
        assertEquals(alphaNfd, Normalizer.normalize(alphaNfc, Normalizer.Form.NFD))
        assertEquals(alphaNfc, Normalizer.normalize(alphaNfd, Normalizer.Form.NFC))
    }
}
