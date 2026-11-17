package com.phucho0237.lucaauth.domain.otp

/**
 * HMAC algorithm used to derive OTP values.
 *
 * @property macName the JCA [javax.crypto.Mac] algorithm name.
 */
enum class OtpAlgorithm(val macName: String) {
    SHA1("HmacSHA1"),
    SHA256("HmacSHA256"),
    SHA512("HmacSHA512"),
}
