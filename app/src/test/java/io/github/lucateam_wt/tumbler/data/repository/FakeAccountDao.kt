package io.github.lucateam_wt.tumbler.data.repository

import io.github.lucateam_wt.tumbler.data.db.AccountDao
import io.github.lucateam_wt.tumbler.data.db.AccountEntity
import io.github.lucateam_wt.tumbler.data.db.AccountSummaryRow
import io.github.lucateam_wt.tumbler.data.db.LegacySecretRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory [AccountDao] that mirrors the Room queries' semantics. [dataKeyVersions] is the set of
 * versions in the `data_keys` table, for the `EXISTS` check of [migrateSecret]; by default there is
 * none, so a test that migrates must wire it.
 */
class FakeAccountDao(private val dataKeyVersions: () -> Set<Int> = { emptySet() }) : AccountDao {

    private val rows = MutableStateFlow<Map<String, AccountEntity>>(emptyMap())

    /** Runs before each [advanceCounter] in the calling coroutine (to hold callers between their read and write). */
    @Volatile
    var beforeAdvance: suspend (id: String) -> Unit = {}

    /** Runs before each [migrateSecret] in the calling coroutine (to change the row meanwhile, or to fail). */
    @Volatile
    var beforeMigrate: suspend (id: String) -> Unit = {}

    private val migrated = AtomicInteger()

    /** How many [migrateSecret] calls wrote a row. */
    val migrations: Int get() = migrated.get()

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
        beforeAdvance(id)
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
        keyVersion: Int,
    ): Int {
        val row = rows.value[id] ?: return 0
        // Like the SQL: createdAt is kept and the legacy copy of the secret is cleared.
        put(
            AccountEntity(
                id, issuer, accountName, type, algorithm, digits, period, counter, secretBlob, row.createdAt,
                keyVersion = keyVersion,
                legacySecretBlob = null,
            ),
        )
        return 1
    }

    override suspend fun delete(id: String) = rows.update { it - id }

    override suspend fun highestKeyVersion(): Int? = rows.value.values.maxOfOrNull { it.keyVersion }

    override suspend fun legacyRows(): List<LegacySecretRow> =
        getAll().filter { it.keyVersion == 0 }.map { LegacySecretRow(it.id, it.secretBlob) }

    override suspend fun migrateSecret(id: String, secretBlob: ByteArray, keyVersion: Int, expectedSecretBlob: ByteArray): Int {
        beforeMigrate(id)
        var updated = 0
        // One atomic compare-and-set on the whole map, like the single SQL UPDATE.
        rows.update { map ->
            val row = map[id]
            val matches = row != null && row.keyVersion == 0 && row.secretBlob.contentEquals(expectedSecretBlob) &&
                keyVersion >= 1 && keyVersion in dataKeyVersions()
            updated = if (matches) 1 else 0
            if (row != null && matches) {
                map + (id to row.copyWith(secretBlob = secretBlob, keyVersion = keyVersion, legacySecretBlob = expectedSecretBlob))
            } else {
                map
            }
        }
        if (updated == 1) migrated.incrementAndGet()
        return updated
    }
}

fun AccountEntity.copyWith(
    counter: Long = this.counter,
    secretBlob: ByteArray = this.secretBlob,
    keyVersion: Int = this.keyVersion,
    legacySecretBlob: ByteArray? = this.legacySecretBlob,
) = AccountEntity(id, issuer, accountName, type, algorithm, digits, period, counter, secretBlob, createdAt, keyVersion, legacySecretBlob)
