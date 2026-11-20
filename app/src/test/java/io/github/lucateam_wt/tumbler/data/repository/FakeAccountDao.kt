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

    override fun observeAll(): Flow<List<AccountSummaryRow>> = rows.map { map ->
        map.values.sortedWith(compareBy({ it.createdAt }, { it.id })).map {
            AccountSummaryRow(
                it.id, it.issuer, it.accountName, it.type, it.algorithm, it.digits, it.period, it.counter,
            )
        }
    }

    override suspend fun getById(id: String): AccountEntity? = rows.value[id]

    override suspend fun insert(entity: AccountEntity) = rows.update {
        check(entity.id !in it) { "Duplicate id" }
        it + (entity.id to entity)
    }

    override suspend fun updateCounter(id: String, counter: Long): Int {
        val row = rows.value[id]
        if (row == null || row.type != "HOTP") return 0
        put(row.copyWith(counter = counter))
        return 1
    }

    override suspend fun delete(id: String) = rows.update { it - id }
}

fun AccountEntity.copyWith(
    counter: Long = this.counter,
    secretBlob: ByteArray = this.secretBlob,
) = AccountEntity(id, issuer, accountName, type, algorithm, digits, period, counter, secretBlob, createdAt)
