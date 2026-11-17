package com.phucho0237.lucaauth.domain.model

/** Kind of one-time password an account produces. */
enum class OtpType {
    /** Time-based (RFC 6238). */
    TOTP,

    /** Counter-based (RFC 4226). */
    HOTP,
}
