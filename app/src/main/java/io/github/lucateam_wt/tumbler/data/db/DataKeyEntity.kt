package io.github.lucateam_wt.tumbler.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Row of the `data_keys` table: data key [version], wrapped by the Keystore key [kekAlias]
 * (`docs/storage-format.md`). [wrappedKey] is the 62-byte wrapped key; the plaintext key is
 * never stored. Rows are only inserted, never updated or deleted.
 *
 * Not a data class: the generated `equals` and `toString` would treat [wrappedKey] by reference
 * and print it.
 */
@Entity(tableName = "data_keys")
class DataKeyEntity(
    @PrimaryKey val version: Int,
    val kekAlias: String,
    val wrappedKey: ByteArray,
    /** Epoch milliseconds. */
    val createdAt: Long,
)
