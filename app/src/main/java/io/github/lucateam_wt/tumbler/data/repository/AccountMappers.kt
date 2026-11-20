package io.github.lucateam_wt.tumbler.data.repository

import io.github.lucateam_wt.tumbler.data.db.AccountEntity
import io.github.lucateam_wt.tumbler.data.db.AccountSummaryRow
import io.github.lucateam_wt.tumbler.domain.model.AccountSummary
import io.github.lucateam_wt.tumbler.domain.model.OtpAccount
import io.github.lucateam_wt.tumbler.domain.model.OtpType
import io.github.lucateam_wt.tumbler.domain.otp.OtpAlgorithm

/** Builds the row for [this] account. Enums are stored by name. */
internal fun OtpAccount.toEntity(id: String, secretBlob: ByteArray, createdAt: Long) = AccountEntity(
    id = id,
    issuer = issuer,
    accountName = accountName,
    type = type.name,
    algorithm = algorithm.name,
    digits = digits,
    period = period,
    counter = counter,
    secretBlob = secretBlob,
    createdAt = createdAt,
)

/**
 * Rebuilds the account from [this] row and its decrypted [secret].
 *
 * @throws IllegalArgumentException if the row holds an unknown enum name or values that
 *   [OtpAccount] rejects. Messages never include the secret.
 */
internal fun AccountEntity.toOtpAccount(secret: ByteArray) = OtpAccount(
    issuer = issuer,
    accountName = accountName,
    type = OtpType.valueOf(type),
    secret = secret,
    algorithm = OtpAlgorithm.valueOf(algorithm),
    digits = digits,
    period = period,
    counter = counter,
)

/** @throws IllegalArgumentException if the row holds an unknown enum name. */
internal fun AccountSummaryRow.toSummary() = AccountSummary(
    id = id,
    issuer = issuer,
    accountName = accountName,
    type = OtpType.valueOf(type),
    algorithm = OtpAlgorithm.valueOf(algorithm),
    digits = digits,
    period = period,
    counter = counter,
)
