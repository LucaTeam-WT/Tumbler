package com.phucho0237.lucaauth.domain.model

import com.phucho0237.lucaauth.domain.otp.Base32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
}
