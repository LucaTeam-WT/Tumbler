package io.github.lucateam_wt.tumbler.data.repository

import androidx.annotation.VisibleForTesting
import io.github.lucateam_wt.tumbler.data.crypto.EnvelopeFormat
import io.github.lucateam_wt.tumbler.data.crypto.SealedSecret
import io.github.lucateam_wt.tumbler.data.db.AccountEntity
import io.github.lucateam_wt.tumbler.data.db.AccountSummaryRow
import io.github.lucateam_wt.tumbler.domain.model.AccountSummary
import io.github.lucateam_wt.tumbler.domain.model.OtpAccount
import io.github.lucateam_wt.tumbler.domain.model.OtpType
import io.github.lucateam_wt.tumbler.domain.otp.OtpAlgorithm

/**
 * The associated data that binds an account's encrypted secret to its row: the id as UTF-8.
 * [RoomAccountRepository] and [RoomAccountBackupStore] both use it, so each reads what the
 * other wrote.
 */
internal fun accountAssociatedData(id: String): ByteArray = id.toByteArray(Charsets.UTF_8)

/**
 * Builds the row for [this] account, whose [secretBlob] is a legacy payload under the Keystore
 * key (`keyVersion` 0). Enums are stored by name. A new row has no legacy copy of its secret.
 * The app no longer writes legacy rows; only tests build them (as an earlier version stored them).
 */
@VisibleForTesting
internal fun OtpAccount.toEntity(id: String, secretBlob: ByteArray, createdAt: Long) =
    toEntity(id, secretBlob, EnvelopeFormat.LEGACY_KEY_VERSION, createdAt)

/**
 * Builds the row for [this] account with a secret sealed by a data key: `keyVersion` is the
 * version [sealed] was bound to, so the row cannot name another key. Enums are stored by name. A
 * new row has no legacy copy of its secret.
 */
internal fun OtpAccount.toEntity(id: String, sealed: SealedSecret, createdAt: Long) =
    toEntity(id, sealed.blob, sealed.keyVersion, createdAt)

private fun OtpAccount.toEntity(id: String, secretBlob: ByteArray, keyVersion: Int, createdAt: Long) = AccountEntity(
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
    keyVersion = keyVersion,
    legacySecretBlob = null,
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
