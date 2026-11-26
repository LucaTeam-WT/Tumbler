package io.github.lucateam_wt.tumbler.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Row of the `accounts` table.
 *
 * [type] and [algorithm] hold enum names (never ordinals), so reordering an enum cannot
 * change stored data. [secretBlob] is the encrypted secret, in the format that [keyVersion]
 * names (`docs/storage-format.md`) and bound to [id]; the plaintext secret is never stored.
 *
 * [keyVersion] and [legacySecretBlob] have no Kotlin defaults, so every place that builds a
 * row states which format its secret is in.
 *
 * Not a data class: the generated `equals` and `toString` would treat [secretBlob] by
 * reference and print it.
 */
@Entity(tableName = "accounts")
class AccountEntity(
    @PrimaryKey val id: String,
    val issuer: String,
    val accountName: String,
    val type: String,
    val algorithm: String,
    val digits: Int,
    val period: Int,
    val counter: Long,
    val secretBlob: ByteArray,
    /** Epoch milliseconds; defines list order together with [id]. */
    val createdAt: Long,
    /**
     * The key [secretBlob] is sealed with: 0 for a legacy payload under the Keystore key (the
     * [io.github.lucateam_wt.tumbler.data.crypto.AesGcmSecretCipher] format, [id] as associated
     * data), n >= 1 for a payload sealed with data key n. Added in schema version 2; rows that
     * existed before became 0. The app writes only 0 so far.
     */
    @ColumnInfo(defaultValue = "0")
    val keyVersion: Int,
    /**
     * The legacy payload of a row whose secret was resealed with a data key, kept for one release
     * so the old Keystore key can still read it; otherwise `null`. Nothing reseals rows yet, so it
     * is always `null` today. Replacing the row clears it and deleting the row deletes it.
     */
    val legacySecretBlob: ByteArray?,
)
