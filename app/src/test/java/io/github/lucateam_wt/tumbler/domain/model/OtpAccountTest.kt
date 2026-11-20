package io.github.lucateam_wt.tumbler.domain.model

import io.github.lucateam_wt.tumbler.domain.otp.Base32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpAccountTest {

    private val secretText = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
    private val secret = Base32.decode(secretText)

    private fun account(secret: ByteArray = this.secret) =
        OtpAccount(issuer = "ACME", accountName = "alice", type = OtpType.TOTP, secret = secret)

    @Test
    fun toString_redactsSecret() {
        val text = account().toString()
        assertTrue(text.contains("secret=<redacted>"))
        assertFalse(text.contains(secretText))
        assertFalse(text.contains(String(secret, Charsets.US_ASCII)))
        assertFalse(text.contains(secret.contentToString()))
        assertFalse(text.contains(secret.toString()))
    }

    @Test
    fun equals_comparesSecretByContent() {
        val a = account(secret.copyOf())
        val b = account(secret.copyOf())
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun equals_detectsDifferentSecret() {
        val other = secret.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertNotEquals(account(), account(other))
    }

    @Test
    fun equals_detectsDifferentFields() {
        assertNotEquals(account(), account().copy(counter = 1))
        assertNotEquals(account(), account().copy(type = OtpType.HOTP))
    }

    private fun assertRejected(description: String, create: () -> OtpAccount) {
        val error = assertThrows(description, IllegalArgumentException::class.java) { create() }
        val message = error.message.orEmpty()
        assertFalse("$description leaks secret", message.contains(secretText))
        assertFalse("$description leaks secret", message.contains(String(secret, Charsets.US_ASCII)))
    }

    @Test
    fun init_acceptsBoundaryValues() {
        account().copy(digits = 6)
        account().copy(digits = 8, period = 1, counter = 0)
    }

    @Test
    fun init_rejectsEmptySecret() {
        assertRejected("empty secret") { account(ByteArray(0)) }
    }

    @Test
    fun init_rejectsDigitsOutOfRange() {
        assertRejected("digits 5") { account().copy(digits = 5) }
        assertRejected("digits 9") { account().copy(digits = 9) }
    }

    @Test
    fun init_rejectsNonPositivePeriod() {
        assertRejected("period 0") { account().copy(period = 0) }
        assertRejected("period -30") { account().copy(period = -30) }
    }

    @Test
    fun init_rejectsNegativeCounter() {
        assertRejected("counter -1") { account().copy(type = OtpType.HOTP, counter = -1) }
    }
}
