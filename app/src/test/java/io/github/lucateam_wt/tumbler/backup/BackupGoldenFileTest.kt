package io.github.lucateam_wt.tumbler.backup

import io.github.lucateam_wt.tumbler.backup.BackupFixtures.secretA
import io.github.lucateam_wt.tumbler.backup.BackupFixtures.secretB
import io.github.lucateam_wt.tumbler.domain.model.OtpAccount
import io.github.lucateam_wt.tumbler.domain.model.OtpType
import io.github.lucateam_wt.tumbler.domain.otp.OtpAlgorithm
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.text.Normalizer
import javax.crypto.AEADBadTagException

/**
 * The committed file `backup/golden-v1.bin` is a format version 1 backup made with the
 * inputs below. If any test here fails, the format changed: bump the format (or payload)
 * version, add a new golden file, keep this one readable, and update
 * `docs/backup-format.md` (see CLAUDE.md).
 *
 * This file is pure ASCII: every non-ASCII character is a `\u` escape, so no editor or
 * tool can change a literal's Unicode normalization form without changing the escape.
 */
class BackupGoldenFileTest {

    companion object {
        const val RESOURCE = "backup/golden-v1.bin"

        /** SHA-256 of the committed golden file: it must never be edited or regenerated. */
        const val GOLDEN_SHA256 = "f180df0f108e69c6d2327bc87c05a8df6ce40860486b55b0394a52c33d3a5a98"
        const val GOLDEN_SIZE = 334

        /** Vietnamese "Mat khau thu nghiem" ("test password"), in NFC (4 precomposed letters). */
        const val PASSWORD = "M\u1EADt kh\u1EA9u th\u1EED nghi\u1EC7m"

        /** 0x00, 0x01, ..., 0x1f. */
        val SALT = ByteArray(32) { it.toByte() }

        /** 0xa0, 0xa1, ..., 0xab. */
        val NONCE = ByteArray(12) { (0xA0 + it).toByte() }

        internal val PARAMS = ScryptParams.DEFAULT

        internal fun accounts(): List<BackupEntry> = listOf(
            BackupEntry(
                OtpAccount("ACME", "alice@example.com", OtpType.TOTP, secretA(), OtpAlgorithm.SHA1, 6, 30, 0),
                1_700_000_000_000,
            ),
            BackupEntry(
                OtpAccount(
                    "Ng\u00E2n h\u00E0ng V\u00ED d\u1EE5",
                    "bob@example.com",
                    OtpType.TOTP,
                    secretB(),
                    OtpAlgorithm.SHA256,
                    8,
                    60,
                    0,
                ),
                1_700_000_060_000,
            ),
            BackupEntry(
                OtpAccount("", "Example \uD83D\uDD10 \u2713", OtpType.HOTP, secretB(), OtpAlgorithm.SHA512, 7, 30, 4_294_967_296),
                1_700_000_120_000,
            ),
            BackupEntry(
                OtpAccount("Example", "", OtpType.TOTP, secretA(), OtpAlgorithm.SHA1, 6, 30, 0),
                1_700_000_180_000,
            ),
        )
    }

    private val golden: ByteArray = checkNotNull(javaClass.classLoader?.getResourceAsStream(RESOURCE)) {
        "Missing test resource $RESOURCE"
    }.use { it.readBytes() }

    @Test
    fun golden_fileIsTheCommittedOne() {
        assertEquals(GOLDEN_SIZE, golden.size)
        val digest = MessageDigest.getInstance("SHA-256").digest(golden)
        assertEquals(GOLDEN_SHA256, digest.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun golden_literalsAreNfc() {
        assertTrue(Normalizer.isNormalized(PASSWORD, Normalizer.Form.NFC))
        assertEquals(19, PASSWORD.length)
        for (entry in accounts()) {
            for (label in listOf(entry.account.issuer, entry.account.accountName)) {
                assertTrue(label, Normalizer.isNormalized(label, Normalizer.Form.NFC))
            }
        }
    }

    @Test
    fun golden_decryptsToTheExpectedAccounts() {
        val result = readBackup(golden, PASSWORD.toCharArray())
        assertTrue("expected Success, got $result", result is BackupReadResult.Success)
        assertEquals(accounts(), (result as BackupReadResult.Success).accounts)
    }

    @Test
    fun golden_decryptsWithTheNfdFormOfThePassword() {
        val nfd = Normalizer.normalize(PASSWORD, Normalizer.Form.NFD)
        val result = readBackup(golden, nfd.toCharArray())
        assertEquals(accounts(), (result as BackupReadResult.Success).accounts)
    }

    @Test
    fun golden_isReproducedByteForByte() {
        val sealed = sealBackup(accounts(), PASSWORD.toCharArray(), SALT.copyOf(), NONCE.copyOf(), PARAMS)
        assertArrayEquals(golden, sealed)
    }

    @Test
    fun golden_headerMatchesSpec() {
        val header = SpecCodec.header(golden)
        assertEquals(listOf(16, 8, 2), listOf(header.log2N, header.r, header.p))
        assertArrayEquals(SALT, header.salt)
        assertArrayEquals(NONCE, header.nonce)
        assertEquals(58, header.length)
    }

    @Test
    fun golden_isReadableByTheIndependentSpecReader() {
        val passwordUtf8 = Normalizer.normalize(PASSWORD, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)
        val records = SpecCodec.decodePayload(SpecCodec.decrypt(golden, passwordUtf8))
        val expected = accounts()
        assertEquals(expected.size, records.size)
        for ((record, entry) in records.zip(expected)) {
            val account = entry.account
            assertEquals(if (account.type == OtpType.TOTP) 1 else 2, record.type)
            val algorithmCode = mapOf(OtpAlgorithm.SHA1 to 1, OtpAlgorithm.SHA256 to 2, OtpAlgorithm.SHA512 to 3)
            assertEquals(algorithmCode.getValue(account.algorithm), record.algorithm)
            assertEquals(account.digits, record.digits)
            assertEquals(account.period, record.period)
            assertEquals(account.counter, record.counter)
            assertEquals(entry.createdAtMillis, record.createdAt)
            assertEquals(account.issuer, record.issuer)
            assertEquals(account.accountName, record.accountName)
            assertArrayEquals(account.secret, record.secret)
        }
    }

    @Test
    fun golden_headerIsAuthenticated() {
        val passwordUtf8 = Normalizer.normalize(PASSWORD, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)
        // Positive control: the same unmodified bytes and the same password decrypt when the
        // header is passed as AAD, so the failure below can only come from the missing AAD.
        val records = SpecCodec.decodePayload(SpecCodec.decrypt(golden, passwordUtf8))
        assertEquals(accounts().size, records.size)
        assertThrows(AEADBadTagException::class.java) {
            SpecCodec.decrypt(golden, passwordUtf8, authenticateHeader = false)
        }
    }
}
