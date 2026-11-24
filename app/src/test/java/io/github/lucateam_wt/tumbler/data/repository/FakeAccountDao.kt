package io.github.lucateam_wt.tumbler.data.repository

import io.github.lucateam_wt.tumbler.data.db.AccountDao
import io.github.lucateam_wt.tumbler.data.db.AccountEntity
import io.github.lucateam_wt.tumbler.data.db.AccountSummaryRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** In-memory [AccountDao] that mirrors the Room queries' semantics. */
class FakeAccountDao : AccountDao {

    private val rows = MutableStateFlow<Map<String, AccountEntity>>(emptyMap())

    fun stored(id: String): AccountEntity = checkNotNull(rows.value[id])

    /** Overwrites a row directly, like an attacker or a corrupted file could. */
    fun put(entity: AccountEntity) = rows.update { it + (entity.id to entity) }

    /** Every row as it is now, for a test transaction to restore on rollback. */
    fun snapshot(): Map<String, AccountEntity> = rows.value

    fun restore(snapshot: Map<String, AccountEntity>) {
        rows.value = snapshot
    }

    val size: Int get() = rows.value.size

    override fun observeAll(): Flow<List<AccountSummaryRow>> = rows.map { map ->
        map.values.sortedWith(compareBy({ it.createdAt }, { it.id })).map {
            AccountSummaryRow(
                it.id, it.issuer, it.accountName, it.type, it.algorithm, it.digits, it.period, it.counter,
            )
        }
    }

    override suspend fun getById(id: String): AccountEntity? = rows.value[id]

    override suspend fun getAll(): List<AccountEntity> =
        rows.value.values.sortedWith(compareBy({ it.createdAt }, { it.id }))

    override suspend fun insert(entity: AccountEntity) = rows.update {
        check(entity.id !in it) { "Duplicate id" }
        it + (entity.id to entity)
    }

    override suspend fun advanceCounter(id: String, expectedCounter: Long, expectedSecretBlob: ByteArray): Int {
        var updated = 0
        // One atomic compare-and-set on the whole map, like the single SQL UPDATE.
        rows.update { map ->
            val row = map[id]
            val matches = row != null && row.type == "HOTP" && row.counter == expectedCounter &&
                row.secretBlob.contentEquals(expectedSecretBlob) && row.counter < Long.MAX_VALUE
            updated = if (matches) 1 else 0
            if (row != null && matches) map + (id to row.copyWith(counter = row.counter + 1)) else map
        }
        return updated
    }

    override suspend fun replace(
        id: String,
        issuer: String,
        accountName: String,
        type: String,
        algorithm: String,
        digits: Int,
        period: Int,
        counter: Long,
        secretBlob: ByteArray,
    ): Int {
        val row = rows.value[id] ?: return 0
        put(AccountEntity(id, issuer, accountName, type, algorithm, digits, period, counter, secretBlob, row.createdAt))
        return 1
    }

    override suspend fun delete(id: String) = rows.update { it - id }
}

fun AccountEntity.copyWith(
    counter: Long = this.counter,
    secretBlob: ByteArray = this.secretBlob,
) = AccountEntity(id, issuer, accountName, type, algorithm, digits, period, counter, secretBlob, createdAt)
