package com.phucho0237.lucaauth.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.Normalizer

class BackupPasswordTest {

    /** "ệ" precomposed (NFC, 1 code point) and decomposed (NFD, 3 code points). */
    private val nfcE = "ệ"
    private val nfdE = "ệ"

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

    @Test
    fun checks_neverContainThePassword() {
        val password = "correct horse battery staple"
        listOf(
            validatePassword(password.toCharArray()),
            validatePassword("short".toCharArray()),
            validatePasswordConfirmation(password.toCharArray(), "other password!".toCharArray()),
        ).forEach { result ->
            assertFalse(result.toString().contains("correct"))
            assertFalse(result.toString().contains("short"))
        }
    }
}
