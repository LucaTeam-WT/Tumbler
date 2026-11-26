package io.github.lucateam_wt.tumbler.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** The SQL of the version 2 columns and of `data_keys`, against a real (in-memory) Room database. */
@RunWith(AndroidJUnit4::class)
class SchemaVersion2InstrumentedTest {

    private val database = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        TumblerDatabase::class.java,
    ).build()
    private val accounts = database.accountDao()
    private val dataKeys = database.dataKeyDao()

    @After
    fun close() {
        database.close()
    }

    @Test
    fun aStoredDataKeyIsNeverOverwritten() = runBlocking {
        val first = ByteArray(62) { 1 }
        assertEquals(1L, dataKeys.insertIfAbsent(DataKeyEntity(1, "alias-a", first, 10)))
        assertEquals(-1L, dataKeys.insertIfAbsent(DataKeyEntity(1, "alias-b", ByteArray(62) { 2 }, 20)))

        val stored = checkNotNull(dataKeys.get(1))
        assertEquals("alias-a", stored.kekAlias)
        assertArrayEquals(first, stored.wrappedKey)
        assertEquals(10L, stored.createdAt)
        assertEquals(listOf(1), dataKeys.getAll().map { it.version })
    }

    @Test
    fun replaceSetsTheKeyVersionAndClearsTheLegacySecretBlob() = runBlocking {
        accounts.insert(row("id-1", keyVersion = 3, legacy = ByteArray(46) { 9 }))
        assertEquals(3, accounts.highestKeyVersion())

        val newBlob = ByteArray(46) { 5 }
        assertEquals(1, accounts.replace("id-1", "New", "carol", "TOTP", "SHA1", 6, 30, 0, newBlob, keyVersion = 0))

        val replaced = checkNotNull(accounts.getById("id-1"))
        assertArrayEquals(newBlob, replaced.secretBlob)
        assertEquals(0, replaced.keyVersion)
        assertNull(replaced.legacySecretBlob)
        assertEquals(1_000L, replaced.createdAt)
    }

    @Test
    fun deleteRemovesTheLegacySecretBlobWithTheRow() = runBlocking {
        accounts.insert(row("id-1", keyVersion = 1, legacy = ByteArray(46) { 9 }))
        accounts.delete("id-1")

        assertNull(accounts.getById("id-1"))
        database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM accounts WHERE legacySecretBlob IS NOT NULL").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
        assertNull(accounts.highestKeyVersion())
    }

    private fun row(id: String, keyVersion: Int, legacy: ByteArray?) = AccountEntity(
        id = id,
        issuer = "Example",
        accountName = "alice",
        type = "TOTP",
        algorithm = "SHA1",
        digits = 6,
        period = 30,
        counter = 0,
        secretBlob = ByteArray(46) { 7 },
        createdAt = 1_000,
        keyVersion = keyVersion,
        legacySecretBlob = legacy,
    )
}
