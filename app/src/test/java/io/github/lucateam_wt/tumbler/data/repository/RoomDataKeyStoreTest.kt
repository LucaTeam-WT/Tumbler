package io.github.lucateam_wt.tumbler.data.repository

import io.github.lucateam_wt.tumbler.data.crypto.WrappedDataKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class RoomDataKeyStoreTest {

    /** A lock regression in the data-key code would hang instead of failing. */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

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
