package io.github.lucateam_wt.tumbler.domain.model

import io.github.lucateam_wt.tumbler.domain.otp.OtpAlgorithm

/**
 * A stored OTP account without its secret, for lists and other places that do not
 * generate codes. Use [io.github.lucateam_wt.tumbler.domain.repository.AccountRepository.getAccount]
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
