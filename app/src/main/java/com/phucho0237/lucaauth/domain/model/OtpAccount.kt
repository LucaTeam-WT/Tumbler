package com.phucho0237.lucaauth.domain.model

import com.phucho0237.lucaauth.domain.otp.Hotp
import com.phucho0237.lucaauth.domain.otp.OtpAlgorithm
import com.phucho0237.lucaauth.domain.otp.Totp

/**
 * An OTP account, as described by an `otpauth://` URI.
 *
 * [toString] never includes the secret, and [equals]/[hashCode] compare the secret
 * by content.
 *
 * @property issuer provider name; empty if unknown.
 * @property accountName user or account identifier.
 * @property secret raw shared secret (already Base32-decoded).
 * @property period time step in seconds; only meaningful for [OtpType.TOTP].
 * @property counter moving factor; only meaningful for [OtpType.HOTP].
 */
data class OtpAccount(
    val issuer: String,
    val accountName: String,
    val type: OtpType,
    val secret: ByteArray,
    val algorithm: OtpAlgorithm = OtpAlgorithm.SHA1,
    val digits: Int = Hotp.DEFAULT_DIGITS,
    val period: Int = Totp.DEFAULT_PERIOD_SECONDS,
    val counter: Long = 0L,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OtpAccount) return false
        return issuer == other.issuer &&
            accountName == other.accountName &&
            type == other.type &&
            secret.contentEquals(other.secret) &&
            algorithm == other.algorithm &&
            digits == other.digits &&
            period == other.period &&
            counter == other.counter
    }

    override fun hashCode(): Int {
        var result = issuer.hashCode()
        result = 31 * result + accountName.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + secret.contentHashCode()
        result = 31 * result + algorithm.hashCode()
        result = 31 * result + digits
        result = 31 * result + period
        result = 31 * result + counter.hashCode()
        return result
    }

    override fun toString(): String =
        "OtpAccount(issuer=$issuer, accountName=$accountName, type=$type, " +
            "secret=<redacted>, algorithm=$algorithm, digits=$digits, " +
            "period=$period, counter=$counter)"
}
