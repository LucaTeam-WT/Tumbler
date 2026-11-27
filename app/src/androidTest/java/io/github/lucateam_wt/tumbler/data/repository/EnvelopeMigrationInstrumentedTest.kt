package io.github.lucateam_wt.tumbler.data.repository

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lucateam_wt.tumbler.data.crypto.AesGcmSecretCipher
import io.github.lucateam_wt.tumbler.data.crypto.AndroidKeystoreKeyProvider
import io.github.lucateam_wt.tumbler.data.crypto.DataKeyCipher
import io.github.lucateam_wt.tumbler.data.crypto.DataKeyHolder
import io.github.lucateam_wt.tumbler.data.crypto.WriteKeyStatus
import io.github.lucateam_wt.tumbler.data.db.DatabaseMigrations
import io.github.lucateam_wt.tumbler.data.db.TumblerDatabase
import io.github.lucateam_wt.tumbler.domain.crypto.KeyUnavailableException
import io.github.lucateam_wt.tumbler.domain.crypto.NewStorageKeyRequiredException
import io.github.lucateam_wt.tumbler.domain.model.OtpAccount
import io.github.lucateam_wt.tumbler.domain.model.OtpType
import io.github.lucateam_wt.tumbler.domain.otp.Base32
import io.github.lucateam_wt.tumbler.domain.repository.NextHotpCodeResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

/**
 * The data key with the real Android Keystore and real Room transactions, in both journal modes
 * the app can get (WAL, and TRUNCATE on low-RAM devices, where a write transaction holds the only
 * connection): the eager migration of legacy rows, resuming after a kill, the read fallback to
 * the legacy copy, the user-confirmed new key, and (PR A must-do (a)) a data key resolved inside a
 * real `withTransaction` on a row with `keyVersion` >= 1, which must neither deadlock nor fail.
 */
@RunWith(AndroidJUnit4::class)
class EnvelopeMigrationInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "lucaauth_test_key_envelope"
    private val wrapAlias = "${alias}_wrap"
    private val databaseName = "lucaauth-envelope-test.db"
    private val databases = mutableListOf<TumblerDatabase>()

    private val totp = OtpAccount("Example", "alice", OtpType.TOTP, Base32.decode("JBSWY3DPEHPK3PXP"))

    /** RFC 4226 appendix D: secret "12345678901234567890", counter 0 -> 755224, 1 -> 287082. */
    private val hotp = OtpAccount("Bank", "bob", OtpType.HOTP, "12345678901234567890".toByteArray(Charsets.US_ASCII))

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun legacyCipher() = AesGcmSecretCipher(AndroidKeystoreKeyProvider(alias))

    /** The app's database file, as [TumblerDatabase.open] builds it, in [mode]. */
    private fun database(mode: RoomDatabase.JournalMode): TumblerDatabase =
        Room.databaseBuilder(context, TumblerDatabase::class.java, databaseName)
            .addMigrations(*DatabaseMigrations.ALL)
            .setJournalMode(mode)
            .build()
            .also { databases += it }

    /** A new process's holder: nothing unwrapped yet. */
    private fun holder(database: TumblerDatabase): DataKeyHolder = testDataKeyHolder(database, wrapAlias)

    private fun migrator(database: TumblerDatabase, dataKeys: DataKeyHolder) =
        SecretMigrator(database.accountDao(), legacySecretReader(legacyCipher()), dataKeys)

    /** Stores [account] as row [id] in the legacy format, as the app did before the data key. */
    private suspend fun seedLegacy(database: TumblerDatabase, id: String, account: OtpAccount, createdAt: Long) {
        val blob = legacyCipher().encrypt(account.secret, accountAssociatedData(id))
        database.accountDao().insert(account.toEntity(id, blob, createdAt))
    }

    @Before
    fun setUp() {
        deleteTestKeys(alias)
        context.deleteDatabase(databaseName)
    }

    @After
    fun tearDown() {
        databases.forEach { it.close() }
        deleteTestKeys(alias)
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrationConvertsEveryLegacyRow_wal() = migrationConvertsEveryLegacyRow(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)

    @Test
    fun migrationConvertsEveryLegacyRow_truncate() = migrationConvertsEveryLegacyRow(RoomDatabase.JournalMode.TRUNCATE)

    private fun migrationConvertsEveryLegacyRow(mode: RoomDatabase.JournalMode) = runBlocking {
        val database = database(mode)
        val ids = (0 until 8).map { "acc-$it" }
        ids.forEachIndexed { i, id -> seedLegacy(database, id, if (i == 3) hotp else totp, i.toLong()) }
        val before = database.accountDao().getAll().associate { it.id to it.secretBlob }
        val progress = mutableListOf<Pair<Int, Int>>()

        val result = withTimeout(TIMEOUT_MILLIS) { migrator(database, holder(database)).run { d, t -> progress += d to t } }

        assertEquals(MigrationResult(total = 8, migrated = 8), result)
        assertEquals((0..8).map { it to 8 }, progress)
        val rows = database.accountDao().getAll()
        assertTrue(rows.all { it.keyVersion == 1 && it.secretBlob[0].toInt() == 2 })
        rows.forEach { assertArrayEquals(before.getValue(it.id), it.legacySecretBlob) }
        assertEquals(listOf(1), database.dataKeyDao().getAll().map { it.version })
        assertTrue(keyStore().containsAlias(wrapAlias))
        // The legacy key stays for one release.
        assertTrue(keyStore().containsAlias(alias))

        // A new process reads every row with one unwrap and takes HOTP codes on the migrated row.
        val next = RoomAccountRepository(database, legacyCipher(), holder(database))
        ids.forEachIndexed { i, id -> assertEquals(if (i == 3) hotp else totp, next.getAccount(id)) }
        assertEquals(NextHotpCodeResult.Code("755224", 1), next.nextHotpCode("acc-3"))
        assertEquals(MigrationResult(total = 0), migrator(database, holder(database)).run())
    }

    /**
     * The real SQL of the conversion's compare-and-set (`AccountDao.migrateSecret`), clause by
     * clause: it applies only to a legacy row that still holds the expected payload, for a key
     * version >= 1 whose record is stored, and changes nothing but the secret columns.
     */
    @Test
    fun theConversionUpdateRefusesEveryRowItMustNotChange() = runBlocking {
        val database = database(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        val dao = database.accountDao()
        seedLegacy(database, "acc-1", hotp, 1)
        val old = checkNotNull(dao.getById("acc-1")).secretBlob
        val sealed = byteArrayOf(2, 12, 1, 2, 3)
        val other = old.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }

        // No record of version 1 yet.
        assertEquals(0, dao.migrateSecret("acc-1", sealed, 1, old))
        assertEquals(1, withTimeout(TIMEOUT_MILLIS) { holder(database).prepareWriteKey() })

        assertEquals(0, dao.migrateSecret("acc-1", sealed, 1, other)) // payload changed meanwhile
        assertEquals(0, dao.migrateSecret("acc-1", sealed, 2, old)) // no record of version 2
        assertEquals(0, dao.migrateSecret("acc-1", sealed, 0, old)) // not a data key version
        assertEquals(0, dao.migrateSecret("missing", sealed, 1, old)) // row deleted
        assertArrayEquals(old, checkNotNull(dao.getById("acc-1")).secretBlob)
        assertEquals(0, checkNotNull(dao.getById("acc-1")).keyVersion)

        // A "next code" committed before the conversion: the counter moved, the payload did not.
        assertEquals(1, dao.advanceCounter("acc-1", 0, old))
        assertEquals(1, dao.migrateSecret("acc-1", sealed, 1, old))
        val row = checkNotNull(dao.getById("acc-1"))
        assertEquals(1, row.keyVersion)
        assertArrayEquals(sealed, row.secretBlob)
        assertArrayEquals(old, row.legacySecretBlob)
        assertEquals(1L, row.counter)
        assertEquals(hotp.issuer, row.issuer)

        // Already converted: a second run (or a second migrator) changes nothing.
        assertEquals(0, dao.migrateSecret("acc-1", byteArrayOf(2, 12, 9), 1, old))
        assertArrayEquals(sealed, checkNotNull(dao.getById("acc-1")).secretBlob)
        // And the unchanged HOTP compare-and-set refuses the stale payload.
        assertEquals(0, dao.advanceCounter("acc-1", 1, old))
    }

    @Test
    fun aKillMidMigrationResumesWithTheSameKey() = runBlocking {
        var database = database(RoomDatabase.JournalMode.TRUNCATE)
        val ids = (0 until 6).map { "acc-$it" }
        ids.forEachIndexed { i, id -> seedLegacy(database, id, totp, i.toLong()) }
        lateinit var job: Job
        job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            migrator(database, holder(database)).run { done, _ -> if (done == 2) job.cancel() }
        }
        job.start()
        job.join()
        assertEquals(listOf(1, 1, 0, 0, 0, 0), database.accountDao().getAll().map { it.keyVersion })
        val firstKey = database.dataKeyDao().getAll().single()
        database.close()

        // The next process.
        database = database(RoomDatabase.JournalMode.TRUNCATE)
        val result = withTimeout(TIMEOUT_MILLIS) { migrator(database, holder(database)).run() }

        assertEquals(MigrationResult(total = 4, migrated = 4), result)
        val stored = database.dataKeyDao().getAll().single()
        assertEquals(firstKey.version, stored.version)
        assertArrayEquals(firstKey.wrappedKey, stored.wrappedKey)
        val repository = RoomAccountRepository(database, legacyCipher(), holder(database))
        ids.forEach { assertEquals(totp, repository.getAccount(it)) }
    }

    @Test
    fun aKeyResolvedInsideARealTransaction_wal() = aKeyResolvedInsideARealTransaction(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)

    @Test
    fun aKeyResolvedInsideARealTransaction_truncate() = aKeyResolvedInsideARealTransaction(RoomDatabase.JournalMode.TRUNCATE)

    /** PR A must-do (a), plus every app path that touches a data key around a transaction. */
    private fun aKeyResolvedInsideARealTransaction(mode: RoomDatabase.JournalMode) = runBlocking<Unit> {
        val database = database(mode)
        val id = RoomAccountRepository(database, legacyCipher(), holder(database)).add(hotp)
        assertEquals(1, database.accountDao().getById(id)?.keyVersion)

        withTimeout(TIMEOUT_MILLIS) {
            // A cold holder resolving the key of a keyVersion 1 row inside withTransaction: its record
            // is read in the transaction and the unwrap runs in the holder's scope, with no database
            // access, so the transaction's connection is never needed by it.
            val cold = holder(database)
            val opened = database.withTransaction {
                val row = checkNotNull(database.accountDao().getById(id))
                DataKeyCipher.open(cold.key(row.keyVersion), id, row.secretBlob)
            }
            assertArrayEquals(hotp.secret, opened)
            opened.fill(0)
            assertArrayEquals(hotp.secret, database.withTransaction { DataKeyCipher.open(cold.writeKey(1), id, checkNotNull(database.accountDao().getById(id)).secretBlob) })

            // The app's paths, each from a new process.
            val repository = RoomAccountRepository(database, legacyCipher(), holder(database))
            assertEquals(NextHotpCodeResult.Code("755224", 1), repository.nextHotpCode(id))
            val added = repository.add(totp)
            val store = RoomAccountBackupStore(database, legacyCipher(), holder(database))
            val inserted = store.writeTransaction {
                replace(added, totp.copy(issuer = "Replaced"))
                insert(hotp, 9).also { readAll() }
            }
            assertEquals(3, store.readAll().size)
            assertEquals(listOf(1, 1, 1), database.accountDao().getAll().map { it.keyVersion })
            assertEquals(hotp, RoomAccountRepository(database, legacyCipher(), holder(database)).getAccount(inserted))
        }
    }

    @Test
    fun aRowWhoseWrappingKeyIsGoneIsReadFromItsLegacyCopy() = runBlocking {
        val database = database(RoomDatabase.JournalMode.TRUNCATE)
        seedLegacy(database, "acc-0", hotp, 0)
        assertEquals(1, migrator(database, holder(database)).run().migrated)
        keyStore().deleteEntry(wrapAlias)

        val repository = RoomAccountRepository(database, legacyCipher(), holder(database))
        assertEquals(hotp, repository.getAccount("acc-0"))
        assertEquals(NextHotpCodeResult.Code("755224", 1), repository.nextHotpCode("acc-0"))
        assertEquals(1, database.accountDao().getById("acc-0")?.keyVersion)
        assertFalse(keyStore().containsAlias(wrapAlias))
    }

    @Test
    fun aLostWrappingKeyNeedsAConfirmedNewKeyAndKeepsTheOldRows() = runBlocking {
        val database = database(RoomDatabase.JournalMode.TRUNCATE)
        val old = RoomAccountRepository(database, legacyCipher(), holder(database)).add(totp)
        val oldRecord = database.dataKeyDao().getAll().single()
        keyStore().deleteEntry(wrapAlias)

        val dataKeys = holder(database)
        assertEquals(WriteKeyStatus.NEW_KEY_REQUIRED, dataKeys.writeKeyStatus())
        val repository = RoomAccountRepository(database, legacyCipher(), dataKeys)
        assertThrows(NewStorageKeyRequiredException::class.java) { runBlocking { repository.add(hotp) } }
        assertThrows(KeyUnavailableException::class.java) { runBlocking { repository.getAccount(old) } }

        assertEquals(2, dataKeys.startNewKey())
        assertTrue(keyStore().containsAlias("${wrapAlias}_k2"))
        val added = repository.add(hotp)
        assertEquals(2, database.accountDao().getById(added)?.keyVersion)
        assertEquals(hotp, repository.getAccount(added))
        // The old row and its key record are kept exactly as they were.
        assertEquals(1, database.accountDao().getById(old)?.keyVersion)
        val records = database.dataKeyDao().getAll()
        assertEquals(listOf(1, 2), records.map { it.version })
        assertArrayEquals(oldRecord.wrappedKey, records.first().wrappedKey)
    }

    private companion object {
        /** A deadlock fails the test instead of hanging the run. */
        const val TIMEOUT_MILLIS = 30_000L
    }
}
