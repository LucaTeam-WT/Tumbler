package com.phucho0237.lucaauth.backup

import com.phucho0237.lucaauth.backup.BackupFixtures.FAST
import com.phucho0237.lucaauth.backup.BackupFixtures.PASSWORD
import com.phucho0237.lucaauth.backup.BackupFixtures.SECRET_A_BASE32
import com.phucho0237.lucaauth.backup.BackupFixtures.entry
import com.phucho0237.lucaauth.backup.BackupFixtures.flip
import com.phucho0237.lucaauth.backup.BackupFixtures.longSecret
import com.phucho0237.lucaauth.backup.BackupFixtures.nonce
import com.phucho0237.lucaauth.backup.BackupFixtures.read
import com.phucho0237.lucaauth.backup.BackupFixtures.salt
import com.phucho0237.lucaauth.backup.BackupFixtures.seal
import com.phucho0237.lucaauth.backup.BackupFixtures.secretA
import com.phucho0237.lucaauth.backup.BackupFixtures.secretB
import com.phucho0237.lucaauth.backup.BackupFixtures.withByte
import com.phucho0237.lucaauth.backup.BackupReadResult.InvalidPayload
import com.phucho0237.lucaauth.backup.BackupReadResult.NotABackup
import com.phucho0237.lucaauth.backup.BackupReadResult.ParametersOutOfBounds
import com.phucho0237.lucaauth.backup.BackupReadResult.TooLarge
import com.phucho0237.lucaauth.backup.BackupReadResult.UnsupportedVersion
import com.phucho0237.lucaauth.backup.BackupReadResult.WrongPasswordOrDamaged
import com.phucho0237.lucaauth.backup.SpecCodec.PayloadBuilder
import com.phucho0237.lucaauth.domain.model.OtpType
import com.phucho0237.lucaauth.domain.otp.OtpAlgorithm
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import kotlin.random.Random

class BackupFormatTest {

    private val oneAccount = listOf(entry())
    private val file = seal(oneAccount)

    /** Header length of [file], which uses a 32-byte salt. */
    private val headerLength = BackupFormat.headerLength(32)

    /** KDF that must never run. */
    private val noKdf = Kdf { _, _, _, _ -> throw AssertionError("scrypt must not run") }

    /** KDF that returns a fixed wrong key, so a file that reaches it fails authentication. */
    private val zeroKeyKdf = CountingKdf { _, _, _, keyLength -> ByteArray(keyLength) }

    private fun accountsOf(result: BackupReadResult): List<BackupEntry> {
        assertTrue("expected Success, got $result", result is BackupReadResult.Success)
        return (result as BackupReadResult.Success).accounts
    }

    private fun assertRejectedBeforeKdf(description: String, expected: BackupReadResult, bytes: ByteArray) {
        assertSame(description, expected, readBackup(bytes, PASSWORD.toCharArray(), noKdf))
    }

    private val label1024 = "ệ".repeat(341) + "x"

    private val everyKind: List<BackupEntry> = buildList {
        for (algorithm in OtpAlgorithm.entries) for (digits in 6..8) for (period in listOf(30, 60)) {
            add(entry(issuer = "ACME $algorithm", accountName = "d$digits p$period", algorithm = algorithm, digits = digits, period = period))
        }
        for ((index, counter) in listOf(0L, 1L, 1L shl 32, Long.MAX_VALUE).withIndex()) {
            add(entry(issuer = "HOTP", accountName = "c$index", type = OtpType.HOTP, secret = secretB(), counter = counter))
        }
        add(entry(issuer = "Ngân hàng Ví dụ", accountName = "người dùng"))
        add(entry(issuer = "例え", accountName = "🔐 مثال", createdAtMillis = 0))
        add(entry(issuer = label1024, accountName = label1024, secret = longSecret(1024)))
        add(entry(issuer = "", accountName = ""))
    }

    // Round trips

    @Test
    fun roundTrip_everyAccountKind() {
        assertEquals(everyKind, accountsOf(read(seal(everyKind))))
    }

    @Test
    fun roundTrip_eachKindAlone() {
        for (account in everyKind.take(4) + everyKind.takeLast(4)) {
            assertEquals(listOf(account), accountsOf(read(seal(listOf(account)))))
        }
    }

    @Test
    fun roundTrip_emptyList() {
        assertEquals(emptyList<BackupEntry>(), accountsOf(read(seal(emptyList()))))
    }

    @Test
    fun roundTrip_maximalBackup() {
        val big = entry(issuer = label1024, accountName = label1024, secret = longSecret(1024))
        val accounts = List(BackupPayload.MAX_ACCOUNTS) { big }
        val bytes = seal(accounts)
        assertTrue(bytes.size <= BackupFormat.MAX_FILE_BYTES)
        assertEquals(accounts, accountsOf(read(bytes)))
    }

    @Test
    fun roundTrip_passwordNormalizedToNfc() {
        val nfc = "mật khẩu đây"
        val nfd = java.text.Normalizer.normalize(nfc, java.text.Normalizer.Form.NFD)
        assertNotEquals(nfc, nfd)
        assertEquals(oneAccount, accountsOf(read(seal(oneAccount, password = nfc), password = nfd)))
        assertEquals(oneAccount, accountsOf(read(seal(oneAccount, password = nfd), password = nfc)))
    }

    @Test
    fun roundTrip_acceptsEverySaltLengthInBounds() {
        for (length in listOf(16, 17, 32, 64)) {
            val bytes = seal(oneAccount, salt = salt(size = length))
            assertEquals(length, bytes[13].toInt())
            assertEquals(oneAccount, accountsOf(read(bytes)))
        }
    }

    // Layout

    @Test
    fun layout_matchesSpec() {
        assertEquals("LUCABKUP", String(file, 0, 8, Charsets.US_ASCII))
        assertEquals(listOf(1, 1, 14, 1, 1, 32), (8..13).map { file[it].toInt() })
        assertArrayEquals(salt(), file.copyOfRange(14, 46))
        assertArrayEquals(nonce(), file.copyOfRange(46, 58))
        assertEquals(58, headerLength)
        val payloadSize = BackupPayload.encode(oneAccount).size
        assertEquals(headerLength + payloadSize + 16, file.size)
    }

    @Test
    fun exportBackup_usesDefaultsAndFreshRandomSaltAndNonce() {
        val first = exportBackup(oneAccount, PASSWORD.toCharArray())
        val second = exportBackup(oneAccount, PASSWORD.toCharArray())

        assertEquals(listOf(16, 8, 2, 32), (10..13).map { first[it].toInt() })
        assertFalse(first.copyOfRange(14, 46).contentEquals(second.copyOfRange(14, 46)))
        assertFalse(first.copyOfRange(46, 58).contentEquals(second.copyOfRange(46, 58)))
        assertFalse(first.copyOfRange(58, first.size).contentEquals(second.copyOfRange(58, second.size)))
        assertEquals(oneAccount, accountsOf(readBackup(first, PASSWORD.toCharArray())))
        assertEquals(oneAccount, accountsOf(readBackup(second, PASSWORD.toCharArray())))
    }

    @Test
    fun exportBackup_takesSaltThenNonceFromTheGivenRandom() {
        val produced = mutableListOf<ByteArray>()
        val recording = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                super.nextBytes(bytes)
                produced += bytes.copyOf()
            }
        }
        val bytes = exportBackup(emptyList(), PASSWORD.toCharArray(), recording)
        assertEquals(listOf(32, 12), produced.map { it.size })
        assertArrayEquals(produced[0], bytes.copyOfRange(14, 46))
        assertArrayEquals(produced[1], bytes.copyOfRange(46, 58))
    }

    @Test
    fun functions_doNotModifyCallerArrays() {
        val password = PASSWORD.toCharArray()
        val salt = salt()
        val nonce = nonce()
        val bytes = sealBackup(oneAccount, password, salt, nonce, FAST)
        val copy = bytes.copyOf()
        readBackup(bytes, password)
        readBackup(bytes.withByte(bytes.size - 1, bytes.last().toInt() xor 1), password)
        assertArrayEquals(PASSWORD.toCharArray(), password)
        assertArrayEquals(salt(), salt)
        assertArrayEquals(nonce(), nonce)
        assertArrayEquals(copy, bytes)
        assertArrayEquals(secretA(), oneAccount[0].account.secret)
    }

    // Wrong password

    @Test
    fun wrongPassword_isWrongPasswordOrDamaged() {
        listOf("correct horse battery stapl", "Correct horse battery staple", "", "x", PASSWORD + " ", "\uD83D").forEach {
            assertSame(it, WrongPasswordOrDamaged, read(file, password = it))
        }
    }

    @Test
    fun wrongPasswordAndFlippedCiphertext_areIndistinguishable() {
        val wrong = read(file, password = "wrong password here")
        val flipped = read(file.withByte(headerLength, file[headerLength].toInt() xor 1))
        val flippedTag = read(file.withByte(file.size - 1, file.last().toInt() xor 0x80))
        assertSame(WrongPasswordOrDamaged, wrong)
        assertSame(wrong, flipped)
        assertSame(wrong, flippedTag)
    }

    // Tampering

    /** Expected result of flipping bit [mask] of byte [index] in [file]. */
    private fun expectedAfterFlip(index: Int, mask: Int): BackupReadResult = when (index) {
        in 0..7 -> NotABackup
        8, 9 -> UnsupportedVersion
        // log2N 14 xor 1 = 15 stays in bounds and changes the key; xor 0x80 = 142 does not.
        10 -> if (mask == 1) WrongPasswordOrDamaged else ParametersOutOfBounds
        // r and p: 1 xor 1 = 0 and 1 xor 0x80 = 129 are both out of bounds.
        11, 12 -> ParametersOutOfBounds
        // Salt length 32 xor 1 = 33 moves the nonce; xor 0x80 = 160 is out of bounds.
        13 -> if (mask == 1) WrongPasswordOrDamaged else ParametersOutOfBounds
        else -> WrongPasswordOrDamaged // salt, nonce, ciphertext, tag
    }

    @Test
    fun tamper_everySingleByteFailsWithTheRightError() {
        for (mask in listOf(0x01, 0x80)) {
            for (index in file.indices) {
                val tampered = file.copyOf().also { it[index] = it[index].flip(mask) }
                assertSame("byte $index mask $mask", expectedAfterFlip(index, mask), read(tampered))
            }
        }
    }

    @Test
    fun tamper_eachRegion() {
        val regions = mapOf(
            "magic" to 0,
            "version" to 8,
            "kdf id" to 9,
            "log2 N" to 10,
            "r" to 11,
            "p" to 12,
            "salt length" to 13,
            "salt" to 14,
            "nonce" to 46,
            "ciphertext" to 58,
            "tag" to file.size - 16,
            "last tag byte" to file.size - 1,
        )
        for ((region, index) in regions) {
            val tampered = file.copyOf().also { it[index] = it[index].flip(1) }
            val kdf = CountingKdf()
            val result = read(tampered, kdf = kdf)
            assertSame(region, expectedAfterFlip(index, 1), result)
            assertFalse(region, result is BackupReadResult.Success)
            // Only a change that keeps the header acceptable may reach scrypt.
            assertEquals(region, if (result == WrongPasswordOrDamaged) 1 else 0, kdf.calls)
        }
    }

    @Test
    fun truncation_atEveryLength() {
        for (length in 0 until file.size) {
            val kdf = CountingKdf()
            val result = read(file.copyOf(length), kdf = kdf)
            val expected = if (length < 8) NotABackup else WrongPasswordOrDamaged
            assertSame("length $length", expected, result)
            // Structurally short files never reach scrypt.
            if (length < headerLength + 16) assertEquals("length $length", 0, kdf.calls)
        }
    }

    @Test
    fun trailingBytes_areRejected() {
        val zeros = ByteArray(1000)
        val random = Random(7).nextBytes(1000)
        for (extra in listOf(zeros.copyOf(1), zeros.copyOf(16), zeros, random.copyOf(1), random)) {
            assertSame("${extra.size} extra bytes", WrongPasswordOrDamaged, read(file + extra))
        }
        assertSame(TooLarge, read(file + ByteArray(BackupFormat.MAX_FILE_BYTES)))
    }

    // Size, magic, versions

    @Test
    fun tooLarge_isRejectedBeforeAnythingElse() {
        assertRejectedBeforeKdf("max + 1", TooLarge, file.copyOf(BackupFormat.MAX_FILE_BYTES + 1))
        assertRejectedBeforeKdf("max + 1 random", TooLarge, ByteArray(BackupFormat.MAX_FILE_BYTES + 1))
        // Exactly the maximum is read normally.
        assertSame(WrongPasswordOrDamaged, read(file.copyOf(BackupFormat.MAX_FILE_BYTES)))
    }

    @Test
    fun notABackup_isRejectedBeforeKdf() {
        listOf(
            ByteArray(0),
            "LUCABKU".toByteArray(),
            "lucabkup".toByteArray() + file.copyOfRange(8, file.size),
            "PK\u0003\u0004".toByteArray() + ByteArray(100),
            byteArrayOf(1, 12) + ByteArray(40),
            Random(1).nextBytes(200),
        ).forEachIndexed { index, bytes ->
            assertRejectedBeforeKdf("case $index", NotABackup, bytes)
        }
    }

    @Test
    fun unknownFormatVersion_isRejectedBeforeKdf() {
        for (version in listOf(0, 2, 255)) {
            assertRejectedBeforeKdf("version $version", UnsupportedVersion, file.withByte(8, version))
            assertRejectedBeforeKdf("version $version alone", UnsupportedVersion, file.withByte(8, version).copyOf(9))
        }
    }

    @Test
    fun unknownKdfId_isRejectedBeforeKdf() {
        for (kdfId in listOf(0, 2, 255)) {
            assertRejectedBeforeKdf("kdf $kdfId", UnsupportedVersion, file.withByte(9, kdfId))
            assertRejectedBeforeKdf("kdf $kdfId alone", UnsupportedVersion, file.withByte(9, kdfId).copyOf(10))
        }
    }

    // scrypt bounds

    private fun withParams(log2N: Int, r: Int, p: Int, saltLength: Int = 32): ByteArray =
        file.withByte(10, log2N).withByte(11, r).withByte(12, p).withByte(13, saltLength)

    @Test
    fun parametersOutOfBounds_areRejectedBeforeKdf() {
        val cases = mapOf(
            "N = 2^13" to withParams(13, 1, 1),
            "N = 2^18" to withParams(18, 1, 1),
            "N = 2^0" to withParams(0, 1, 1),
            "log2 N = 62" to withParams(62, 1, 1),
            "log2 N = 63" to withParams(63, 1, 1),
            "log2 N = 255" to withParams(255, 1, 1),
            "r = 0" to withParams(14, 0, 1),
            "r = 17" to withParams(14, 17, 1),
            "r = 255" to withParams(14, 255, 1),
            "p = 0" to withParams(14, 1, 0),
            "p = 5" to withParams(14, 1, 5),
            "p = 255" to withParams(14, 1, 255),
            "memory 144 MiB" to withParams(17, 9, 1),
            "memory 256 MiB" to withParams(17, 16, 1),
            "RFC 7914 r = 1, N = 2^16" to withParams(16, 1, 1),
            "RFC 7914 r = 1, N = 2^17" to withParams(17, 1, 1),
            "salt length 0" to withParams(14, 1, 1, saltLength = 0),
            "salt length 15" to withParams(14, 1, 1, saltLength = 15),
            "salt length 65" to withParams(14, 1, 1, saltLength = 65),
            "salt length 255" to withParams(14, 1, 1, saltLength = 255),
        )
        for ((description, bytes) in cases) {
            assertRejectedBeforeKdf(description, ParametersOutOfBounds, bytes)
        }
    }

    @Test
    fun parametersAtTheBounds_reachKdf() {
        val cases = listOf(
            withParams(14, 1, 1),
            withParams(15, 1, 4),
            withParams(17, 8, 4),
            withParams(16, 16, 4),
            withParams(14, 16, 1),
            withParams(17, 2, 1),
        )
        for (bytes in cases) {
            val kdf = CountingKdf(zeroKeyKdf)
            assertSame(WrongPasswordOrDamaged, read(bytes, kdf = kdf))
            assertEquals(1, kdf.calls)
        }
    }

    @Test
    fun kdfRejectingParameters_isParametersOutOfBounds() {
        val rejecting = Kdf { _, _, _, _ -> throw IllegalArgumentException("rejected") }
        assertSame(ParametersOutOfBounds, read(file, kdf = rejecting))
    }

    @Test
    fun validFile_runsKdfExactlyOnce() {
        val kdf = CountingKdf()
        assertEquals(oneAccount, accountsOf(read(file, kdf = kdf)))
        assertEquals(1, kdf.calls)
    }

    // Payload errors behind valid encryption (written by the independent SpecCodec)

    private fun encryptPayload(payload: ByteArray) =
        SpecCodec.encrypt(payload, PASSWORD.toByteArray(Charsets.UTF_8), salt(), nonce())

    @Test
    fun specWrittenFile_isReadByProductionReader() {
        assertEquals(oneAccount, accountsOf(read(encryptPayload(BackupPayload.encode(oneAccount)))))
    }

    @Test
    fun invalidPayloads_areInvalidPayload() {
        val cases = mapOf(
            "empty" to ByteArray(0),
            "version only" to byteArrayOf(1),
            "truncated record" to PayloadBuilder(count = 1).build(),
            "trailing bytes" to PayloadBuilder(count = 1).record().raw(byteArrayOf(0)).build(),
            "digits 9" to PayloadBuilder(count = 1).record(digits = 9).build(),
            "negative counter" to PayloadBuilder(count = 1).record(type = 2, counter = -1).build(),
            "unknown algorithm" to PayloadBuilder(count = 1).record(algorithm = 9).build(),
            "valid then invalid" to PayloadBuilder(count = 2).record().record(period = 0).build(),
            "bad UTF-8" to PayloadBuilder(count = 1).record(issuer = byteArrayOf(0xC0.toByte(), 0x80.toByte())).build(),
        )
        for ((description, payload) in cases) {
            assertSame(description, InvalidPayload, read(encryptPayload(payload)))
        }
    }

    @Test
    fun unknownPayloadVersion_isUnsupportedVersion() {
        assertSame(UnsupportedVersion, read(encryptPayload(PayloadBuilder(version = 2, count = 0).build())))
    }

    // Never throws

    @Test
    fun fuzz_randomInputsNeverThrowOrSucceed() {
        val random = Random(2026)
        val validPrefix = file.copyOf(14)
        repeat(2_000) { i ->
            val body = random.nextBytes(random.nextInt(0, 200))
            val bytes = if (i % 2 == 0) validPrefix + body else random.nextBytes(random.nextInt(0, 80))
            val result = read(bytes, kdf = zeroKeyKdf)
            assertTrue("case $i", result is BackupReadResult.Failure)
        }
        repeat(500) { i ->
            val bytes = file.copyOf()
            repeat(random.nextInt(1, 4)) { bytes[random.nextInt(bytes.size)] = random.nextInt(256).toByte() }
            if (bytes.contentEquals(file)) return@repeat
            assertTrue("mutation $i", read(bytes, kdf = zeroKeyKdf) is BackupReadResult.Failure)
        }
    }

    // Export input checks

    @Test
    fun export_rejectsWeakOrInvalidPasswords() {
        for (password in listOf("", "short", "abcdefghijk", "abcdefghijkl\uD83D")) {
            val error = assertThrows(password, IllegalArgumentException::class.java) { seal(oneAccount, password = password) }
            if (password.isNotEmpty()) assertFalse(error.message.orEmpty().contains(password))
        }
    }

    @Test
    fun export_rejectsAccountsOutsideTheLimits() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            seal(listOf(entry(), entry(secret = longSecret(1025))))
        }
        assertFalse(error.message.orEmpty().contains(SECRET_A_BASE32))
        assertThrows(IllegalArgumentException::class.java) {
            seal(List(BackupPayload.MAX_ACCOUNTS + 1) { entry() })
        }
    }

    @Test
    fun seal_rejectsBadSaltNonceAndParameters() {
        assertThrows(IllegalArgumentException::class.java) { seal(oneAccount, salt = salt(size = 15)) }
        assertThrows(IllegalArgumentException::class.java) { seal(oneAccount, salt = salt(size = 65)) }
        assertThrows(IllegalArgumentException::class.java) { seal(oneAccount, nonce = ByteArray(16)) }
        assertThrows(IllegalArgumentException::class.java) { seal(oneAccount, params = ScryptParams(13, 8, 1)) }
        assertThrows(IllegalArgumentException::class.java) { seal(oneAccount, params = ScryptParams(16, 1, 1)) }
    }

    @Test
    fun results_neverShowSecretsOrLabels() {
        val text = read(file).toString()
        assertEquals("Success(accounts=1)", text)
        for (failure in listOf(NotABackup, UnsupportedVersion, ParametersOutOfBounds, WrongPasswordOrDamaged, InvalidPayload, TooLarge)) {
            assertFalse(failure.toString().contains(PASSWORD))
        }
    }
}
