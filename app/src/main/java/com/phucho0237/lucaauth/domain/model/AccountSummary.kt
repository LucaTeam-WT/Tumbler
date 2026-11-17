package com.phucho0237.lucaauth.domain.model

import com.phucho0237.lucaauth.domain.otp.OtpAlgorithm

/**
 * A stored OTP account without its secret, for lists and other places that do not
 * generate codes. Use [com.phucho0237.lucaauth.domain.repository.AccountRepository.getAccount]
 * to load the secret.
 *
 * @property id stable identifier assigned when the account was stored.
 */
data class AccountSummary(
    val id: String,
    val issuer: String,
    val accountName: String,
    val type: OtpType,
    val algorithm: OtpAlgorithm,
    val digits: Int,
    val period: Int,
    val counter: Long,
)
