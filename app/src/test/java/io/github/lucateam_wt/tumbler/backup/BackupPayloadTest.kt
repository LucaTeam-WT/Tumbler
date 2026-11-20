package io.github.lucateam_wt.tumbler.backup

import io.github.lucateam_wt.tumbler.backup.BackupFixtures.SECRET_A_BASE32
import io.github.lucateam_wt.tumbler.backup.BackupFixtures.entry
import io.github.lucateam_wt.tumbler.backup.BackupFixtures.longSecret
import io.github.lucateam_wt.tumbler.backup.BackupFixtures.secretA
import io.github.lucateam_wt.tumbler.backup.BackupFixtures.secretB
import io.github.lucateam_wt.tumbler.backup.SpecCodec.PayloadBuilder
import io.github.lucateam_wt.tumbler.domain.model.OtpType
import io.github.lucateam_wt.tumbler.domain.otp.OtpAlgorithm
import io.github.lucateam_wt.tumbler.domain.otp.OtpAuthUri
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPayloadTest {

    private fun hex(text: String): ByteArray {
        val digits = text.filterNot { it.isWhitespace() }
        return ByteArray(digits.length / 2) { digits.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun decodeValid(payload: ByteArray): List<BackupEntry> {
        val decoded = BackupPayload.decode(payload)
        assertTrue("expected Valid, got $decoded", decoded is BackupPayload.Decoded.Valid)
        return (decoded as BackupPayload.Decoded.Valid).accounts
    }

    private fun assertInvalid(description: String, payload: ByteArray) {
        assertSame(description, BackupPayload.Decoded.Invalid, BackupPayload.decode(payload))
    }

    /** 1024 UTF-8 bytes of 3-byte characters, then [extra] ASCII letters. */
    private fun label(extra: Int = 0) = "ệ".repeat(341) + "x" + "y".repeat(extra)

    @Test
    fun encode_matchesSpecByteForByte() {
        val payload = BackupPayload.encode(
            listOf(entry(issuer = "A", accountName = "b", createdAtMillis = 1)),
        )
        val expected = hex(
            "01 00000001" +
                "01 01 06 0000001e 0000000000000000 0000000000000001" +
                "0001 41 0001 62 000a 48656c6c6f21deadbeef",
        )
        assertArrayEquals(expected, payload)
    }

    @Test
    fun encode_isDeterministic() {
        val accounts = listOf(entry(), entry(type = OtpType.HOTP, counter = 7, secret = secretB()))
        assertArrayEquals(BackupPayload.encode(accounts), BackupPayload.encode(accounts))
    }

    @Test
    fun encode_emptyList() {
        assertArrayEquals(hex("01 00000000"), BackupPayload.encode(emptyList()))
        assertEquals(emptyList<BackupEntry>(), decodeValid(hex("01 00000000")))
    }

    @Test
    fun roundTrip_preservesEveryField() {
        val accounts = listOf(
            entry(algorithm = OtpAlgorithm.SHA256, digits = 7, period = 60, counter = 5),
            entry(
                issuer = "Ngân hàng Ví dụ",
                accountName = "",
                type = OtpType.HOTP,
                secret = secretB(),
                algorithm = OtpAlgorithm.SHA512,
                digits = 8,
                period = Int.MAX_VALUE,
                counter = Long.MAX_VALUE,
                createdAtMillis = Long.MAX_VALUE,
            ),
            entry(issuer = "", accountName = "🔐 例 مثال", createdAtMillis = 0),
        )
        assertEquals(accounts, decodeValid(BackupPayload.encode(accounts)))
    }

    @Test
    fun limits_acceptExactMaximums() {
        val accounts = listOf(entry(issuer = label(), accountName = label(), secret = longSecret(1024)))
        assertEquals(1024, accounts[0].account.issuer.toByteArray(Charsets.UTF_8).size)
        assertEquals(accounts, decodeValid(BackupPayload.encode(accounts)))
    }

    @Test
    fun limits_maximalPayloadFitsInAFile() {
        val maxHeader = BackupFormat.headerLength(BackupFormat.MAX_SALT_LENGTH)
        assertTrue(maxHeader + BackupPayload.MAX_PAYLOAD_BYTES + BackupFormat.TAG_LENGTH <= BackupFormat.MAX_FILE_BYTES)
        val big = entry(issuer = label(), accountName = label(), secret = longSecret(1024))
        val payload = BackupPayload.encode(List(BackupPayload.MAX_ACCOUNTS) { big })
        assertEquals(BackupPayload.MAX_PAYLOAD_BYTES, payload.size)
        assertEquals(BackupPayload.MAX_ACCOUNTS, decodeValid(payload).size)
    }

    /** A rejected account in [accounts], expected at [index] for [reason] in [field]. */
    private class RejectionCase(
        val accounts: List<BackupEntry>,
        val index: Int,
        val reason: AccountRejection,
        val field: String?,
    )

    /** Two valid accounts, then [bad], then a valid one: the reported index must be 2. */
    private fun third(bad: BackupEntry) = listOf(entry(), entry(secret = secretB()), bad, entry())

    @Test
    fun encode_reportsWhichAccountIsRejectedAndWhy() {
        val cases = mapOf(
            "too many accounts" to RejectionCase(
                List(BackupPayload.MAX_ACCOUNTS + 1) { entry() },
                BackupPayload.MAX_ACCOUNTS,
                AccountRejection.TOO_MANY_ACCOUNTS,
                null,
            ),
            "issuer too long" to RejectionCase(third(entry(issuer = label(extra = 1))), 2, AccountRejection.LABEL_TOO_LONG, "issuer"),
            "account name too long" to
                RejectionCase(third(entry(accountName = label(extra = 1))), 2, AccountRejection.LABEL_TOO_LONG, "account name"),
            "secret too long" to RejectionCase(third(entry(secret = longSecret(1025))), 2, AccountRejection.SECRET_TOO_LONG, "secret"),
            "issuer unpaired surrogate" to
                RejectionCase(third(entry(issuer = "ACME\uD83D")), 2, AccountRejection.LABEL_UNPAIRED_SURROGATE, "issuer"),
            "account name unpaired surrogate" to
                RejectionCase(third(entry(accountName = "\uDD10alice")), 2, AccountRejection.LABEL_UNPAIRED_SURROGATE, "account name"),
            "issuer not trimmed" to RejectionCase(third(entry(issuer = " ACME")), 2, AccountRejection.LABEL_NOT_TRIMMED, "issuer"),
            "account name not trimmed" to
                RejectionCase(third(entry(accountName = "alice@example.com ")), 2, AccountRejection.LABEL_NOT_TRIMMED, "account name"),
            "first account" to RejectionCase(listOf(entry(issuer = "ACME\t"), entry()), 0, AccountRejection.LABEL_NOT_TRIMMED, "issuer"),
            "issuer checked before the account name and the secret" to RejectionCase(
                listOf(entry(issuer = " ACME", accountName = label(extra = 1), secret = longSecret(1025))),
                0,
                AccountRejection.LABEL_NOT_TRIMMED,
                "issuer",
            ),
            "first bad account wins" to RejectionCase(
                listOf(entry(), entry(secret = longSecret(1025)), entry(issuer = " ACME")),
                1,
                AccountRejection.SECRET_TOO_LONG,
                "secret",
            ),
        )
        for ((description, case) in cases) {
            val error = assertThrows(description, AccountRejectedException::class.java) {
                BackupPayload.encode(case.accounts)
            }
            assertEquals(description, case.index, error.accountIndex)
            assertEquals(description, case.reason, error.reason)
            val message = error.message.orEmpty()
            val expectedMessage = "Account ${case.index}: ${case.reason}" + if (case.field == null) "" else " (${case.field})"
            assertEquals(description, expectedMessage, message)
            // The rejected accounts hold these labels and secrets; none may leak into the message.
            assertFalse(description, message.contains("ACME") || message.contains("alice") || message.contains("ệ"))
            assertFalse(description, message.contains(SECRET_A_BASE32))
            assertFalse(description, message.contains(secretB().contentToString()))
        }
    }

    @Test
    fun decode_unknownVersion() {
        for (version in listOf(0, 2, 255)) {
            assertSame(BackupPayload.Decoded.UnsupportedVersion, BackupPayload.decode(PayloadBuilder(version, 0).build()))
        }
    }

    @Test
    fun decode_rejectsBadCounts() {
        assertInvalid("empty", ByteArray(0))
        assertInvalid("version only", byteArrayOf(1))
        assertInvalid("negative count", PayloadBuilder(count = -1).build())
        assertInvalid("count above max", PayloadBuilder(count = BackupPayload.MAX_ACCOUNTS + 1).build())
        assertInvalid("count larger than records", PayloadBuilder(count = 2).record().build())
        assertInvalid("count smaller than records", PayloadBuilder(count = 1).record().record().build())
    }

    @Test
    fun decode_rejectsEveryTruncation() {
        val payload = BackupPayload.encode(listOf(entry(), entry(type = OtpType.HOTP, secret = secretB())))
        for (length in 0 until payload.size) {
            assertInvalid("length $length", payload.copyOf(length))
        }
    }

    @Test
    fun decode_rejectsTrailingBytes() {
        val payload = BackupPayload.encode(listOf(entry()))
        assertInvalid("one byte", payload + 0.toByte())
        assertInvalid("part of a record", payload + PayloadBuilder(count = 0).record().build().copyOfRange(5, 5 + 40))
        assertInvalid("a whole record", payload + PayloadBuilder(count = 0).record().build().copyOfRange(5, 5 + 48))
    }

    @Test
    fun decode_rejectsUnknownCodes() {
        for (type in listOf(0, 3, 255)) assertInvalid("type $type", PayloadBuilder(count = 1).record(type = type).build())
        for (algorithm in listOf(0, 4, 255)) {
            assertInvalid("algorithm $algorithm", PayloadBuilder(count = 1).record(algorithm = algorithm).build())
        }
    }

    @Test
    fun decode_rejectsValuesTheParserRejects() {
        for (digits in listOf(0, 5, 9, 255)) assertInvalid("digits $digits", PayloadBuilder(count = 1).record(digits = digits).build())
        for (period in listOf(0, -1, Int.MIN_VALUE)) assertInvalid("period $period", PayloadBuilder(count = 1).record(period = period).build())
        for (counter in listOf(-1L, Long.MIN_VALUE)) {
            assertInvalid("counter $counter", PayloadBuilder(count = 1).record(type = 2, counter = counter).build())
        }
        assertInvalid("createdAt -1", PayloadBuilder(count = 1).record(createdAt = -1).build())
    }

    @Test
    fun decode_acceptsBoundaryValues() {
        val payload = PayloadBuilder(count = 2)
            .record(digits = 8, period = 1, counter = 0, createdAt = 0)
            .record(type = 2, algorithm = 3, digits = 6, period = Int.MAX_VALUE, counter = Long.MAX_VALUE)
            .build()
        assertEquals(2, decodeValid(payload).size)
    }

    @Test
    fun decode_rejectsLengthsOutsideTheLimits() {
        val big = ByteArray(1025) { 'a'.code.toByte() }
        assertInvalid("issuer 1025", PayloadBuilder(count = 1).record(issuer = big).build())
        assertInvalid("account name 1025", PayloadBuilder(count = 1).record(accountName = big).build())
        assertInvalid("secret 1025", PayloadBuilder(count = 1).record(secret = longSecret(1025)).build())
        assertInvalid("secret empty", PayloadBuilder(count = 1).record(secret = ByteArray(0)).build())
        assertInvalid("issuer length 65535", PayloadBuilder(count = 1).record(issuerLength = 0xFFFF).build())
        assertInvalid("secret length past end", PayloadBuilder(count = 1).record(secretLength = 11).build())
    }

    @Test
    fun decode_rejectsMalformedUtf8() {
        listOf(
            "c0 80", // overlong NUL
            "ed a0 80", // UTF-8 encoded surrogate
            "ff",
            "e1 bb", // truncated sequence
            "80", // lone continuation byte
            "f4 90 80 80", // above U+10FFFF
        ).forEach { bytes ->
            assertInvalid("issuer $bytes", PayloadBuilder(count = 1).record(issuer = hex(bytes)).build())
            assertInvalid("account name $bytes", PayloadBuilder(count = 1).record(accountName = hex(bytes)).build())
        }
    }

    @Test
    fun decode_oneInvalidAccountFailsTheWholePayload() {
        val payload = PayloadBuilder(count = 3).record().record(secret = secretB()).record(digits = 9).build()
        assertInvalid("third account invalid", payload)
        val first = PayloadBuilder(count = 3).record(digits = 9).record().record().build()
        assertInvalid("first account invalid", first)
    }

    @Test
    fun decode_copiesSecretsAndLeavesInputUnchanged() {
        val payload = BackupPayload.encode(listOf(entry()))
        val copy = payload.copyOf()
        val accounts = decodeValid(payload)
        assertArrayEquals(copy, payload)
        payload.fill(0)
        assertArrayEquals(secretA(), accounts[0].account.secret)
    }

    @Test
    fun toString_neverShowsSecretsOrLabels() {
        val accounts = listOf(entry(issuer = "ACME", accountName = "alice@example.com"))
        val texts = listOf(accounts[0].toString(), BackupPayload.decode(BackupPayload.encode(accounts)).toString())
        for (text in texts) {
            assertFalse(text, text.contains("ACME"))
            assertFalse(text, text.contains("alice"))
            assertFalse(text, text.contains(SECRET_A_BASE32))
            assertFalse(text, text.contains(secretA().contentToString()))
            assertFalse(text, text.contains(String(secretA(), Charsets.ISO_8859_1)))
        }
    }

    @Test
    fun entry_rejectsNegativeCreationTime() {
        assertThrows(IllegalArgumentException::class.java) { entry(createdAtMillis = -1) }
    }

    // Labels: the form the otpauth:// parser stores (trimmed)

    @Test
    fun labels_withSurroundingWhitespaceAreRejectedOnReadAndWrite() {
        val untrimmed = listOf(" GitHub", "GitHub ", "\tGitHub", "GitHub\n", "\u00A0GitHub", "GitHub\u3000", " ", "\t")
        for (label in untrimmed) {
            val bytes = label.toByteArray(Charsets.UTF_8)
            assertInvalid("issuer [$label]", PayloadBuilder(count = 1).record(issuer = bytes).build())
            assertInvalid("account name [$label]", PayloadBuilder(count = 1).record(accountName = bytes).build())
            for (account in listOf(entry(issuer = label), entry(accountName = label))) {
                val error = assertThrows(label, IllegalArgumentException::class.java) { BackupPayload.encode(listOf(account)) }
                assertFalse(error.message.orEmpty().contains("GitHub"))
            }
        }
    }

    @Test
    fun labels_whitespaceIsExactlyTheSetTheSpecNames() {
        // docs/backup-format.md: categories Zs, Zl, Zp, plus U+0009..U+000D and U+001C..U+001F.
        val separators = setOf(Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR)
        for (code in 0..0xFFFF) {
            val c = code.toChar()
            val inSpec = Character.getType(c).toByte() in separators || code in 0x09..0x0D || code in 0x1C..0x1F
            assertEquals("U+%04X".format(code), inSpec, c.isWhitespace())
            assertEquals("U+%04X".format(code), !inSpec, BackupPayload.isCanonicalLabel("${c}x"))
        }
    }

    @Test
    fun labels_innerWhitespaceAndEmptyLabelsAreKept() {
        val accounts = listOf(
            entry(issuer = "Git  Hub", accountName = "a\tb"),
            entry(issuer = "", accountName = ""),
            entry(issuer = "", accountName = "alice"),
            entry(issuer = "GitHub", accountName = ""),
        )
        assertEquals(accounts, decodeValid(BackupPayload.encode(accounts)))
    }

    /** A label as written in an otpauth:// URI and the labels the parser stores (null: it rejects the URI). */
    private class LabelCase(val label: String, val issuer: String?, val accountName: String?)

    private fun percentEncode(text: String): String = buildString {
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val code = byte.toInt() and 0xFF
            val char = code.toChar()
            if (code < 0x80 && (char.isLetterOrDigit() || char == ':')) append(char) else append("%%%02X".format(code))
        }
    }

    @Test
    fun labels_matchTheOtpauthParser() {
        val cases = listOf(
            LabelCase("GitHub:alice", "GitHub", "alice"),
            LabelCase(" GitHub : alice ", "GitHub", "alice"),
            LabelCase("\tGitHub\t:\talice\t", "GitHub", "alice"),
            LabelCase("GitHub:", "GitHub", ""),
            LabelCase(":alice", "", "alice"),
            LabelCase("alice", "", "alice"),
            LabelCase("  alice  ", "", "alice"),
            LabelCase(":", "", ""),
            LabelCase("a:b:c", "a", "b:c"),
            LabelCase(" Ngân hàng : người dùng ", "Ngân hàng", "người dùng"),
            LabelCase("\u00A0例え\u3000:\u00A0🔐 ✓\u00A0", "例え", "🔐 ✓"),
            LabelCase("   ", null, null),
            LabelCase("\t", null, null),
            LabelCase("\u3000\u00A0", null, null),
        )
        for (case in cases) {
            val uri = "otpauth://totp/${percentEncode(case.label)}?secret=$SECRET_A_BASE32"
            val parsed = runCatching { OtpAuthUri.parse(uri) }.getOrNull()
            // The parser behaviour this rule mirrors; if it changes, this test says so.
            assertEquals("[${case.label}] issuer", case.issuer, parsed?.issuer)
            assertEquals("[${case.label}] account name", case.accountName, parsed?.accountName)

            // Every pair of labels the parser stores is written and read back unchanged.
            if (parsed != null) {
                val entries = listOf(BackupEntry(parsed, 0))
                assertEquals("[${case.label}]", entries, decodeValid(BackupPayload.encode(entries)))
            }

            // The same label split like the parser but not trimmed is accepted only if the
            // parser would have stored it unchanged; otherwise the whole payload is rejected.
            val rawIssuer = if (':' in case.label) case.label.substringBefore(':') else ""
            val rawAccountName = case.label.substringAfter(':')
            val payload = PayloadBuilder(count = 1)
                .record(issuer = rawIssuer.toByteArray(Charsets.UTF_8), accountName = rawAccountName.toByteArray(Charsets.UTF_8))
                .build()
            val decoded = BackupPayload.decode(payload)
            if (parsed != null && parsed.issuer == rawIssuer && parsed.accountName == rawAccountName) {
                val account = (decoded as BackupPayload.Decoded.Valid).accounts.single().account
                assertEquals("[${case.label}]", rawIssuer to rawAccountName, account.issuer to account.accountName)
            } else {
                assertSame("[${case.label}]", BackupPayload.Decoded.Invalid, decoded)
            }
        }
    }
}
