package io.github.lucateam_wt.tumbler.data.repository

import io.github.lucateam_wt.tumbler.data.crypto.WrappedDataKey
import io.github.lucateam_wt.tumbler.data.db.DataKeyDao
import io.github.lucateam_wt.tumbler.data.db.DataKeyEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomDataKeyStoreTest {

    /** Mirrors `INSERT OR IGNORE`: -1 when the version exists, which is left unchanged. */
    private class FakeDataKeyDao : DataKeyDao {
        val rows = sortedMapOf<Int, DataKeyEntity>()

        override suspend fun get(version: Int): DataKeyEntity? = rows[version]

        override suspend fun getAll(): List<DataKeyEntity> = rows.values.toList()

        override suspend fun insertIfAbsent(entity: DataKeyEntity): Long {
            if (entity.version in rows) return -1
            rows[entity.version] = entity
            return entity.version.toLong()
        }
    }

    private val dataKeys = FakeDataKeyDao()
    private val accounts = FakeAccountDao()
    private val store = RoomDataKeyStore(dataKeys, accounts)
    private val wrapped = ByteArray(62) { it.toByte() }

    @Test
    fun insertStoresEveryFieldAndReadsBack() = runBlocking {
        assertTrue(store.insertIfAbsent(WrappedDataKey(1, "alias", wrapped, 1_234)))

        val read = checkNotNull(store.get(1))
        assertEquals(1, read.version)
        assertEquals("alias", read.kekAlias)
        assertArrayEquals(wrapped, read.wrappedKey)
        assertEquals(1_234L, read.createdAtMillis)
        assertEquals(listOf(1), store.all().map { it.version })
        assertNull(store.get(2))
    }

    @Test
    fun aStoredKeyIsNeverOverwritten() = runBlocking {
        assertTrue(store.insertIfAbsent(WrappedDataKey(1, "alias", wrapped, 1)))
        val first = dataKeys.rows.getValue(1)

        assertFalse(store.insertIfAbsent(WrappedDataKey(1, "other", ByteArray(62), 2)))
        assertSame(first, dataKeys.rows.getValue(1))
    }

    @Test
    fun highestAccountKeyVersionIsZeroWithoutRows() = runBlocking {
        assertEquals(0, store.highestAccountKeyVersion())
    }
}
