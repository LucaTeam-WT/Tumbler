package io.github.lucateam_wt.tumbler.domain.model

import io.github.lucateam_wt.tumbler.domain.otp.Hotp
import io.github.lucateam_wt.tumbler.domain.otp.OtpAlgorithm
import io.github.lucateam_wt.tumbler.domain.otp.Totp

/**
 * An OTP account, as described by an `otpauth://` URI.
 *
 * [toString] never includes the secret, and [equals]/[hashCode] compare the secret
 * by content.
 *
 * @throws IllegalArgumentException if [secret] is empty, [digits] is outside
 *   [Hotp.MIN_DIGITS]..[Hotp.MAX_DIGITS], [period] is not positive or [counter] is
 *   negative. Messages never include the secret.
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

    init {
        require(secret.isNotEmpty()) { "Secret must not be empty" }
        require(digits in Hotp.MIN_DIGITS..Hotp.MAX_DIGITS) {
            "Digits must be in ${Hotp.MIN_DIGITS}..${Hotp.MAX_DIGITS}"
        }
        require(period > 0) { "Period must be positive" }
        require(counter >= 0) { "Counter must not be negative" }
    }

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
