package com.phucho0237.lucaauth.data.repository

import com.phucho0237.lucaauth.data.db.AccountSummaryRow
import com.phucho0237.lucaauth.domain.model.AccountSummary
import com.phucho0237.lucaauth.domain.model.OtpAccount
import com.phucho0237.lucaauth.domain.model.OtpType
import com.phucho0237.lucaauth.domain.otp.OtpAlgorithm
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AccountMappersTest {

    private val secret = "12345678901234567890".toByteArray()
    private val blob = byteArrayOf(1, 2, 3)

    @Test
    fun toEntity_copiesEveryFieldAndStoresEnumsByName() {
        val account = OtpAccount("Bank", "bob", OtpType.HOTP, secret, OtpAlgorithm.SHA512, 7, 45, 9)

        val entity = account.toEntity("id-1", blob, 123L)

        assertEquals("id-1", entity.id)
        assertEquals("Bank", entity.issuer)
        assertEquals("bob", entity.accountName)
        assertEquals("HOTP", entity.type)
        assertEquals("SHA512", entity.algorithm)
        assertEquals(7, entity.digits)
        assertEquals(45, entity.period)
        assertEquals(9L, entity.counter)
        assertArrayEquals(blob, entity.secretBlob)
        assertEquals(123L, entity.createdAt)
        assertEquals(account, entity.toOtpAccount(secret.copyOf()))
    }

    @Test
    fun everyEnumValue_roundTripsByName() {
        for (type in OtpType.entries) {
            for (algorithm in OtpAlgorithm.entries) {
                val account = OtpAccount("I", "A", type, secret, algorithm)
                val entity = account.toEntity("id", blob, 0L)

                assertEquals(type.name, entity.type)
                assertEquals(algorithm.name, entity.algorithm)
                assertEquals(account, entity.toOtpAccount(secret.copyOf()))
            }
        }
    }

    @Test
    fun toSummary_copiesEveryField() {
        val row = AccountSummaryRow("id-2", "Example", "alice", "TOTP", "SHA256", 8, 60, 3)

        assertEquals(
            AccountSummary("id-2", "Example", "alice", OtpType.TOTP, OtpAlgorithm.SHA256, 8, 60, 3),
            row.toSummary(),
        )
    }

    @Test
    fun unknownEnumName_throws() {
        val badType = AccountSummaryRow("id", "I", "A", "UNKNOWN", "SHA1", 6, 30, 0)
        val badAlgorithm = AccountSummaryRow("id", "I", "A", "TOTP", "MD5", 6, 30, 0)

        assertThrows(IllegalArgumentException::class.java) { badType.toSummary() }
        assertThrows(IllegalArgumentException::class.java) { badAlgorithm.toSummary() }
    }
}
