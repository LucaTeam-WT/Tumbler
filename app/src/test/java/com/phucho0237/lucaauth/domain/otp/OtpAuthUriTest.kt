package com.phucho0237.lucaauth.domain.otp

import com.phucho0237.lucaauth.domain.model.OtpType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class OtpAuthUriTest {

    /** Base32 of the RFC 4226 / RFC 6238 SHA1 seed "12345678901234567890". */
    private val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
    private val seed = "12345678901234567890".toByteArray(Charsets.US_ASCII)

    private fun totp(label: String, query: String = "") =
        OtpAuthUri.parse("otpauth://totp/$label?secret=$secret$query")

    private fun assertRejected(uri: String) {
        val error = assertThrows(uri, IllegalArgumentException::class.java) { OtpAuthUri.parse(uri) }
        val message = error.message.orEmpty()
        assertFalse("message leaks secret: $uri", message.contains(secret))
        if (uri.isNotEmpty()) assertFalse("message leaks URI", message.contains(uri))
    }

    @Test
    fun parse_standardTotp() {
        val account = OtpAuthUri.parse(
            "otpauth://totp/ACME%20Co:john@example.com" +
                "?secret=$secret&issuer=ACME%20Co&algorithm=SHA1&digits=6&period=30",
        )
        assertEquals("ACME Co", account.issuer)
        assertEquals("john@example.com", account.accountName)
        assertEquals(OtpType.TOTP, account.type)
        assertArrayEquals(seed, account.secret)
        assertEquals(OtpAlgorithm.SHA1, account.algorithm)
        assertEquals(6, account.digits)
        assertEquals(30, account.period)
    }

    @Test
    fun parse_standardHotp() {
        val account = OtpAuthUri.parse("otpauth://hotp/ACME:alice?secret=$secret&counter=42")
        assertEquals(OtpType.HOTP, account.type)
        assertEquals("ACME", account.issuer)
        assertEquals("alice", account.accountName)
        assertEquals(42L, account.counter)
        assertArrayEquals(seed, account.secret)
    }

    @Test
    fun parse_appliesDefaults() {
        val account = totp("alice")
        assertEquals(OtpAlgorithm.SHA1, account.algorithm)
        assertEquals(6, account.digits)
        assertEquals(30, account.period)
    }

    @Test
    fun parse_sha256AndSha512CaseInsensitive() {
        assertEquals(OtpAlgorithm.SHA256, totp("a", "&algorithm=SHA256").algorithm)
        assertEquals(OtpAlgorithm.SHA512, totp("a", "&algorithm=sha512").algorithm)
    }

    @Test
    fun parse_eightDigitsAndCustomPeriod() {
        val account = totp("a", "&digits=8&period=60")
        assertEquals(8, account.digits)
        assertEquals(60, account.period)
    }

    @Test
    fun parse_schemeAndTypeCaseInsensitive() {
        assertEquals(OtpType.TOTP, OtpAuthUri.parse("OTPAUTH://TOTP/a?secret=$secret").type)
    }

    @Test
    fun parse_labelForms() {
        totp("ACME:alice").let { assertEquals("ACME" to "alice", it.issuer to it.accountName) }
        totp("ACME:%20%20alice").let { assertEquals("ACME" to "alice", it.issuer to it.accountName) }
        totp("alice").let { assertEquals("" to "alice", it.issuer to it.accountName) }
        totp("ACME%3Aalice").let { assertEquals("ACME" to "alice", it.issuer to it.accountName) }
    }

    @Test
    fun parse_percentDecodesUtf8AndKeepsPlus() {
        val account = totp("Caf%C3%A9:a+b%40example.com", "&issuer=Caf%C3%A9+Bank")
        assertEquals("Café+Bank", account.issuer)
        assertEquals("a+b@example.com", account.accountName)
    }

    @Test
    fun parse_issuerParamOverridesLabelPrefix() {
        assertEquals("Real", totp("Label:alice", "&issuer=Real").issuer)
        assertEquals("Label", totp("Label:alice", "&issuer=").issuer)
    }

    @Test
    fun parse_ignoresUnknownAndUsesLastDuplicate() {
        val account = totp("a", "&image=x&digits=7&digits=8")
        assertEquals(8, account.digits)
    }

    @Test
    fun parse_hotpIgnoresPeriodAndTotpIgnoresCounter() {
        assertEquals(30, OtpAuthUri.parse("otpauth://hotp/a?secret=$secret&counter=1&period=0").period)
        assertEquals(0L, totp("a", "&counter=-5").counter)
    }

    @Test
    fun parse_rejectsInvalidInput() {
        listOf(
            "",
            "   ",
            "https://totp/a?secret=$secret",
            "otpauth:totp/a?secret=$secret",
            "otpauth://motp/a?secret=$secret",
            "otpauth://totp?secret=$secret",
            "otpauth://totp/a",
            "otpauth://totp/a?secret=",
            "otpauth://totp/a?secret====",
            "otpauth://totp/a?secret=${secret}1",
            "otpauth://totp/a?secret=$secret%",
            "otpauth://totp/a%zz?secret=$secret",
            "otpauth://hotp/a?secret=$secret",
            "otpauth://hotp/a?secret=$secret&counter=-1",
            "otpauth://hotp/a?secret=$secret&counter=x",
            "otpauth://totp/a?secret=$secret&digits=5",
            "otpauth://totp/a?secret=$secret&digits=9",
            "otpauth://totp/a?secret=$secret&digits=six",
            "otpauth://totp/a?secret=$secret&period=0",
            "otpauth://totp/a?secret=$secret&period=-30",
            "otpauth://totp/a?secret=$secret&algorithm=MD5",
        ).forEach(::assertRejected)
    }

    @Test
    fun parse_roundTripMatchesRfc6238Vector() {
        val account = totp("ACME:alice", "&digits=8")
        val code = Totp(account.secret, account.algorithm, account.digits, account.period).generate(59)
        assertEquals("94287082", code)
    }
}
