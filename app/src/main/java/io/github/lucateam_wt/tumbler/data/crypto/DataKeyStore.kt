package io.github.lucateam_wt.tumbler.data.crypto

/**
 * One stored data key: its [version], the Keystore alias that wrapped it and the wrapped bytes.
 * Records are only ever inserted, never changed or deleted. Not a data class: [wrappedKey] is an
 * array, and [toString] prints no key material.
 */
class WrappedDataKey(
    val version: Int,
    val kekAlias: String,
    val wrappedKey: ByteArray,
    val createdAtMillis: Long,
) {

    /** True if [other] describes the same stored key (version, alias and bytes). */
    fun sameKeyAs(other: WrappedDataKey): Boolean =
        version == other.version && kekAlias == other.kekAlias && wrappedKey.contentEquals(other.wrappedKey)

    override fun toString(): String = "WrappedDataKey(version=$version, <redacted>)"
}

/**
 * Where [DataKeyHolder] keeps wrapped data keys (the `data_keys` table in the app). Calls run in
 * the caller's coroutine, so inside a database transaction they use that transaction.
 */
interface DataKeyStore {

    /** The record of [version], or `null`. */
    suspend fun get(version: Int): WrappedDataKey?

    /** Every record. */
    suspend fun all(): List<WrappedDataKey>

    /**
     * Stores [key] unless a record with its version exists, which is then left unchanged: a
     * stored key is never overwritten.
     *
     * @return `true` if [key] was stored.
     */
    suspend fun insertIfAbsent(key: WrappedDataKey): Boolean

    /** The highest `keyVersion` of any account row, or 0 if there is none (or only legacy rows). */
    suspend fun highestAccountKeyVersion(): Int
}
