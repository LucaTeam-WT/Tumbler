package io.github.lucateam_wt.tumbler.domain.otp

import io.github.lucateam_wt.tumbler.backup.BackupPayload
import io.github.lucateam_wt.tumbler.domain.model.AccountLimitException
import io.github.lucateam_wt.tumbler.domain.model.AccountLimits
import io.github.lucateam_wt.tumbler.domain.model.OtpType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
    fun parse_labelPercentDecodesUtf8AndKeepsPlus() {
        val account = totp("Caf%C3%A9:a+b%40example.com")
        assertEquals("Café", account.issuer)
        assertEquals("a+b@example.com", account.accountName)
    }

    @Test
    fun parse_queryValueDecodesPlusAsSpace() {
        assertEquals("Example Inc", totp("a", "&issuer=Example+Inc").issuer)
        assertEquals("Café Bank", totp("a", "&issuer=Caf%C3%A9+Bank").issuer)
    }

    @Test
    fun parse_queryValueKeepsEncodedPlus() {
        assertEquals("A+B", totp("a", "&issuer=A%2BB").issuer)
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
    fun parse_acceptsLabelsUpToTheLimitInEveryUtf8Width() {
        // 1024 UTF-8 bytes each: 1-, 2-, 3- and 4-byte characters (the last one a surrogate pair).
        val labels = listOf(
            "a".repeat(1024),
            "é".repeat(512),
            "€".repeat(341) + "a",
            "😀".repeat(256),
        )
        for (label in labels) {
            assertEquals(label, totp("ACME:${encode(label)}").accountName)
            assertEquals(label, totp("${encode(label)}:alice").issuer)
            assertEquals(label, totp("alice", "&issuer=${encode(label)}").issuer)
        }
    }

    @Test
    fun parse_rejectsLabelsOverTheLimitOrWithAnUnpairedSurrogate() {
        val tooLong = listOf(
            "a".repeat(1025),
            "é".repeat(512) + "a",
            "€".repeat(342),
            "😀".repeat(256) + "a",
        )
        val notText = listOf("al\ud800ice", "al\udc00ice", "alice\ud83d")
        for (label in tooLong.map(::encode) + notText) {
            assertRejected("otpauth://totp/ACME:$label?secret=$secret")
            assertRejected("otpauth://totp/$label:alice?secret=$secret")
            assertRejected("otpauth://totp/alice?secret=$secret&issuer=$label")
        }
    }

    @Test
    fun parse_reportsLabelsOverTheLimitAsALimitErrorNamingTheField() {
        val tooLong = encode("é".repeat(512) + "a")
        val cases = mapOf(
            "otpauth://totp/ACME:$tooLong?secret=$secret" to AccountLimitException.Field.ACCOUNT_NAME,
            "otpauth://totp/$tooLong:alice?secret=$secret" to AccountLimitException.Field.ISSUER,
            "otpauth://totp/alice?secret=$secret&issuer=$tooLong" to AccountLimitException.Field.ISSUER,
        )
        for ((uri, field) in cases) {
            val error = assertThrows(AccountLimitException::class.java) { OtpAuthUri.parse(uri) }
            assertEquals(field, error.field)
            assertFalse(error.message.orEmpty().contains("é"))
        }
    }

    @Test
    fun parse_reportsAnUnpairedSurrogateAsAnInvalidLinkNotALimitError() {
        // Too long and not valid text: not valid text wins, so the add screen says "invalid link".
        for (label in listOf("al\ud800ice", "a".repeat(2000) + "\udc00")) {
            val error = assertThrows(IllegalArgumentException::class.java) { totp("ACME:$label") }
            assertFalse(error is AccountLimitException)
        }
    }

    @Test
    fun parse_reportsOnlySizeLimitsAsLimitErrors() {
        val uris = listOf(
            "otpauth://totp/a?secret=$secret&digits=5",
            "otpauth://totp/a?secret=${secret}1",
            "otpauth://hotp/a?secret=$secret",
            "otpauth://motp/a?secret=$secret",
        )
        for (uri in uris) {
            val error = assertThrows(uri, IllegalArgumentException::class.java) { OtpAuthUri.parse(uri) }
            assertFalse(uri, error is AccountLimitException)
        }
    }

    @Test
    fun parse_checksTheLabelsBeforeDecodingTheSecret() {
        val tooLong = "a".repeat(1025)
        var decoded = 0
        val decoder = { text: String -> decoded++; Base32.decode(text) }
        for (uri in listOf(
            "otpauth://totp/ACME:$tooLong?secret=$secret",
            "otpauth://totp/alice?secret=$secret&issuer=$tooLong",
            "otpauth://totp/al\ud800ice?secret=$secret",
        )) {
            assertThrows(IllegalArgumentException::class.java) { OtpAuthUri.parse(uri, decoder) }
        }
        assertEquals(0, decoded)
    }

    @Test
    fun parse_wipesADecodedSecretThatIsTooLong() {
        val decoded = mutableListOf<ByteArray>()
        val decoder = { text: String -> Base32.decode(text).also { decoded += it } }
        val error = assertThrows(AccountLimitException::class.java) {
            OtpAuthUri.parse("otpauth://totp/ACME:alice?secret=" + "GEZDGNBV".repeat(205), decoder)
        }
        assertEquals(AccountLimitException.Field.SECRET, error.field)
        assertEquals(1025, decoded.single().size)
        assertTrue(decoded.single().all { it == 0.toByte() })
    }

    @Test
    fun parse_wipesTheDecodedSecretWhenTheAccountIsInvalid() {
        val decoded = mutableListOf<ByteArray>()
        val decoder = { text: String -> Base32.decode(text).also { decoded += it } }
        assertThrows(IllegalArgumentException::class.java) {
            OtpAuthUri.parse("otpauth://totp/a?secret=$secret&digits=9", decoder)
        }
        assertEquals(20, decoded.single().size)
        assertTrue(decoded.single().all { it == 0.toByte() })
    }

    @Test
    fun parse_keepsTheSecretOfAValidLink() {
        val decoded = mutableListOf<ByteArray>()
        val account = OtpAuthUri.parse("otpauth://totp/a?secret=$secret") { text -> Base32.decode(text).also { decoded += it } }
        assertArrayEquals(seed, account.secret)
        assertArrayEquals(seed, decoded.single())
    }

    @Test
    fun accountLimits_checkLabelTellsTooLongFromNotValidText() {
        assertEquals(AccountLimits.LabelCheck.OK, AccountLimits.checkLabel(""))
        assertEquals(AccountLimits.LabelCheck.OK, AccountLimits.checkLabel("😀".repeat(256)))
        assertEquals(AccountLimits.LabelCheck.TOO_LONG, AccountLimits.checkLabel("😀".repeat(256) + "a"))
        assertEquals(AccountLimits.LabelCheck.NOT_VALID_TEXT, AccountLimits.checkLabel("\ud83d"))
        assertEquals(AccountLimits.LabelCheck.NOT_VALID_TEXT, AccountLimits.checkLabel("\ude00a"))
        // An unpaired surrogate after the limit is still found.
        assertEquals(AccountLimits.LabelCheck.NOT_VALID_TEXT, AccountLimits.checkLabel("a".repeat(1025) + "\ud800"))
    }

    @Test
    fun parse_limitAppliesToTheTrimmedLabel() {
        val label = "a".repeat(1024)
        assertEquals(label, totp("ACME:%20%20$label%20").accountName)
        assertEquals(label, totp("alice", "&issuer=+$label+").issuer)
    }

    @Test
    fun parse_acceptsSecretsUpTo1024BytesAndRejectsLonger() {
        // Repeats of the public RFC seed's Base32: "GEZDGNBV" is "12345", "GEZDGNA" is "1234".
        val max = "GEZDGNBV".repeat(204) + "GEZDGNA"
        val account = OtpAuthUri.parse("otpauth://totp/ACME:alice?secret=$max")
        assertEquals(1024, account.secret.size)
        assertRejected("otpauth://totp/ACME:alice?secret=" + "GEZDGNBV".repeat(205))
    }

    @Test
    fun accountLimits_matchTheBackupFormat() {
        assertEquals(BackupPayload.MAX_LABEL_BYTES, AccountLimits.MAX_LABEL_UTF8_BYTES)
        assertEquals(BackupPayload.MAX_SECRET_BYTES, AccountLimits.MAX_SECRET_BYTES)
    }

    @Test
    fun accountLimits_countUtf8BytesLikeTheEncoder() {
        val samples = listOf("", "ACME", "Việt Nam", "€", "😀", "aé€😀")
        for (sample in samples) {
            val repeated = sample.repeat(if (sample.isEmpty()) 1 else 1024 / sample.toByteArray(Charsets.UTF_8).size)
            val size = repeated.toByteArray(Charsets.UTF_8).size
            assertEquals(sample, size <= AccountLimits.MAX_LABEL_UTF8_BYTES, AccountLimits.isLabelWithinLimits(repeated))
            assertFalse(sample, AccountLimits.isLabelWithinLimits(repeated + "a".repeat(1025 - size.coerceAtMost(1024))))
        }
    }

    private fun encode(text: String): String = java.net.URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    @Test
    fun parse_roundTripMatchesRfc6238Vector() {
        val account = totp("ACME:alice", "&digits=8")
        val code = Totp(account.secret, account.algorithm, account.digits, account.period).generate(59)
        assertEquals("94287082", code)
    }
}
