package com.phucho0237.lucaauth.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Row of the `accounts` table.
 *
 * [type] and [algorithm] hold enum names (never ordinals), so reordering an enum cannot
 * change stored data. [secretBlob] is the [com.phucho0237.lucaauth.domain.crypto.SecretCipher]
 * payload, encrypted with [id] as associated data; the plaintext secret is never stored.
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
)
