package io.github.lucateam_wt.tumbler.data.repository

import io.github.lucateam_wt.tumbler.data.crypto.DataKeyStore
import io.github.lucateam_wt.tumbler.data.crypto.WrappedDataKey
import io.github.lucateam_wt.tumbler.data.db.AccountDao
import io.github.lucateam_wt.tumbler.data.db.DataKeyDao
import io.github.lucateam_wt.tumbler.data.db.DataKeyEntity
import io.github.lucateam_wt.tumbler.data.db.TumblerDatabase

/**
 * [DataKeyStore] on the `data_keys` table. Each call is one Room query in the caller's coroutine:
 * inside `withTransaction` it runs in that transaction. A stored key is never overwritten.
 */
class RoomDataKeyStore internal constructor(
    private val dataKeys: DataKeyDao,
    private val accounts: AccountDao,
) : DataKeyStore {

    constructor(database: TumblerDatabase) : this(database.dataKeyDao(), database.accountDao())

    override suspend fun get(version: Int): WrappedDataKey? = dataKeys.get(version)?.toWrapped()

    override suspend fun all(): List<WrappedDataKey> = dataKeys.getAll().map { it.toWrapped() }

    override suspend fun insertIfAbsent(key: WrappedDataKey): Boolean =
        dataKeys.insertIfAbsent(DataKeyEntity(key.version, key.kekAlias, key.wrappedKey, key.createdAtMillis)) != -1L

    override suspend fun highestAccountKeyVersion(): Int = accounts.highestKeyVersion() ?: 0

    private fun DataKeyEntity.toWrapped() = WrappedDataKey(version, kekAlias, wrappedKey, createdAt)
}
