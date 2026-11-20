package com.phucho0237.lucaauth.backup

import com.phucho0237.lucaauth.backup.BackupFixtures.entry
import com.phucho0237.lucaauth.backup.BackupFixtures.secretB
import com.phucho0237.lucaauth.domain.model.OtpType
import com.phucho0237.lucaauth.domain.otp.OtpAlgorithm
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Feeds the payload decoder (the code that runs after successful decryption) with seeded
 * random and mutated inputs. It must never throw, must return only its closed results, and
 * every account it accepts must satisfy the payload rules.
 */
class BackupPayloadFuzzTest {

    private val base = BackupPayload.encode(
        listOf(
            entry(),
            entry(
                issuer = "Ng\u00E2n h\u00E0ng",
                accountName = "ng\u01B0\u1EDDi d\u00F9ng",
                type = OtpType.HOTP,
                secret = secretB(),
                algorithm = OtpAlgorithm.SHA512,
                digits = 8,
                counter = 5,
            ),
            entry(issuer = "", accountName = "\uD83D\uDD10 \u2713", algorithm = OtpAlgorithm.SHA256, period = 60),
        ),
    )

    private val u16Boundaries = intArrayOf(0, 1, 2, 1023, 1024, 1025, 0x7FFF, 0x8000, 0xFFFF)
    private val i32Boundaries = intArrayOf(-1, 0, 1, 2, 3, 4, 999, 1000, 1001, Int.MAX_VALUE, Int.MIN_VALUE)

    private fun mutate(random: Random): ByteArray = base.copyOf().also { bytes ->
        repeat(random.nextInt(1, 9)) { bytes[random.nextInt(bytes.size)] = random.nextInt(256).toByte() }
    }

    private fun truncateOrExtend(random: Random): ByteArray =
        if (random.nextBoolean()) {
            base.copyOf(random.nextInt(0, base.size))
        } else {
            base + random.nextBytes(random.nextInt(1, 64))
        }

    /** Writes a boundary value over a random position: hits the count and the u16 lengths. */
    private fun boundaryValue(random: Random): ByteArray = base.copyOf().also { bytes ->
        if (random.nextInt(4) == 0) {
            val count = i32Boundaries[random.nextInt(i32Boundaries.size)]
            for (i in 0 until 4) bytes[1 + i] = (count ushr (24 - 8 * i)).toByte()
        } else {
            val at = random.nextInt(bytes.size - 1)
            val value = u16Boundaries[random.nextInt(u16Boundaries.size)]
            bytes[at] = (value ushr 8).toByte()
            bytes[at + 1] = value.toByte()
        }
    }

    private fun assertWellFormed(entry: BackupEntry) {
        val account = entry.account
        for (label in listOf(account.issuer, account.accountName)) {
            assertTrue(BackupPayload.isCanonicalLabel(label))
            assertTrue(label.toByteArray(Charsets.UTF_8).size <= BackupPayload.MAX_LABEL_BYTES)
        }
        assertTrue(account.secret.size in 1..BackupPayload.MAX_SECRET_BYTES)
        assertTrue(account.digits in 6..8)
        assertTrue(account.period >= 1 && account.counter >= 0 && entry.createdAtMillis >= 0)
    }

    @Test(timeout = 60_000)
    fun decode_neverThrowsAndReturnsOnlyClosedResults() {
        val random = Random(20_261_119)
        var valid = 0
        var invalid = 0
        var unsupported = 0
        repeat(INPUTS) { i ->
            val input = when (i % 5) {
                0 -> mutate(random)
                1 -> truncateOrExtend(random)
                2 -> boundaryValue(random)
                3 -> byteArrayOf(1) + random.nextBytes(random.nextInt(0, 400))
                else -> random.nextBytes(random.nextInt(0, 64))
            }
            when (val result = BackupPayload.decode(input)) {
                is BackupPayload.Decoded.Valid -> {
                    valid++
                    result.accounts.forEach(::assertWellFormed)
                }
                BackupPayload.Decoded.Invalid -> invalid++
                BackupPayload.Decoded.UnsupportedVersion -> unsupported++
            }
        }
        // All three results were reached, so the inputs got past the first checks.
        assertTrue("valid=$valid invalid=$invalid unsupported=$unsupported", valid > 0 && invalid > 0 && unsupported > 0)
    }

    private companion object {
        const val INPUTS = 50_000
    }
}
