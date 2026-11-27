package io.github.lucateam_wt.tumbler.data.repository

import android.database.SQLException
import io.github.lucateam_wt.tumbler.data.crypto.AesGcmSecretCipher
import io.github.lucateam_wt.tumbler.data.crypto.CountingWrappers
import io.github.lucateam_wt.tumbler.data.crypto.DataKey
import io.github.lucateam_wt.tumbler.data.crypto.DataKeyCipher
import io.github.lucateam_wt.tumbler.data.crypto.DataKeyHolder
import io.github.lucateam_wt.tumbler.data.crypto.EnvelopeFormat
import io.github.lucateam_wt.tumbler.data.crypto.FakeKeystore
import io.github.lucateam_wt.tumbler.data.crypto.KeyProvider
import io.github.lucateam_wt.tumbler.data.db.AccountEntity
import io.github.lucateam_wt.tumbler.data.db.DataKeyEntity
import io.github.lucateam_wt.tumbler.domain.crypto.KeyUnavailableException
import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipher
import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipherException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.security.ProviderException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The eager migration of legacy rows (`docs/storage-format.md`, "Migration of existing rows") over
 * the real [RoomDataKeyStore] and compare-and-set semantics of the DAO, a counting fake Keystore for
 * the data key, and a real AES-GCM legacy cipher over a JVM key.
 */
class SecretMigratorTest {

    /** A lock regression in the data-key code would hang instead of failing. */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    /** The legacy Keystore key as a plain JVM key; [lost] simulates its entry being deleted. */
    private class LegacyKeys : KeyProvider {
        private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        @Volatile
        var lost = false

        override fun getOrCreateKey(): SecretKey = key
        override fun getExistingKey(): SecretKey? = if (lost) null else key
    }

    /**
     * The legacy cipher with a failure hook per account id, counting decrypts and keeping every
     * plaintext it returned (the same arrays) so tests can check they were wiped.
     */
    private class LegacyCipher(private val inner: SecretCipher) : SecretCipher {
        val decrypts = AtomicInteger()
        val returned: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())

        @Volatile
        var failure: (id: String) -> SecretCipherException? = { null }

        @Volatile
        var afterDecrypt: () -> Unit = {}

        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray) = inner.encrypt(plaintext, associatedData)

        override fun decrypt(payload: ByteArray, associatedData: ByteArray): ByteArray {
            decrypts.incrementAndGet()
            failure(String(associatedData, Charsets.UTF_8))?.let { throw it }
            return inner.decrypt(payload, associatedData).also {
                returned += it
                afterDecrypt()
            }
        }
    }

    private val legacyKeys = LegacyKeys()
    private val legacyCipher = LegacyCipher(AesGcmSecretCipher(legacyKeys))
    private val keystore = FakeKeystore()
    private val wrappers = CountingWrappers(keystore)
    private val dataKeyDao = FakeDataKeyDao()
    private val accounts = FakeAccountDao { dataKeyDao.rows.keys.toSet() }
    private val keyStore = RoomDataKeyStore(dataKeyDao, accounts)
    private val holder = newHolder()
    private var nextCreatedAt = 1_000L

    // ---- Converting ----------------------------------------------------------------------------

    @Test
    fun `every legacy row is converted with one new key and one legacy decrypt each`() = runBlocking {
        val ids = (1..8).map { "acc-$it" }
        val before = ids.associateWith { seedLegacy(it, counter = if (it == "acc-3") 5 else 0, type = if (it == "acc-3") "HOTP" else "TOTP") }
        val progress = mutableListOf<Pair<Int, Int>>()

        val result = migrator().run { done, total -> progress += done to total }

        assertEquals(MigrationResult(total = 8, migrated = 8), result)
        assertFalse(result.retryAdvised)
        assertEquals((0..8).map { it to 8 }, progress)
        assertEquals(setOf(1), dataKeyDao.rows.keys)
        assertEquals(1, keystore.generations.get())
        assertEquals(1, wrappers.wraps.get())
        // The check unwrap of the new key; no other Keystore call for the data key.
        assertEquals(1, wrappers.unwraps.get())
        assertEquals(8, legacyCipher.decrypts.get())
        val key = holder.key(1)
        for (id in ids) {
            val old = before.getValue(id)
            val row = accounts.stored(id)
            assertEquals(1, row.keyVersion)
            assertArrayEquals(old.secretBlob, row.legacySecretBlob)
            assertEquals(EnvelopeFormat.SECRET_PAYLOAD_VERSION, row.secretBlob[0])
            assertArrayEquals(SECRET, DataKeyCipher.open(key, id, row.secretBlob))
            // Nothing but the secret columns changed.
            assertEquals(old.counter, row.counter)
            assertEquals(old.type, row.type)
            assertEquals(old.issuer, row.issuer)
            assertEquals(old.accountName, row.accountName)
            assertEquals(old.createdAt, row.createdAt)
        }
        // Bound to the account id: a payload moved to another row does not open there.
        assertThrows(SecretCipherException::class.java) {
            DataKeyCipher.open(key, "acc-2", accounts.stored("acc-1").secretBlob)
        }
        // A fresh nonce for every row.
        assertEquals(8, ids.map { accounts.stored(it).secretBlob.copyOfRange(2, 14).toList() }.toSet().size)
        assertAllWiped()

        // Nothing is left: the next run reads one query and touches nothing.
        val again = migrator().run { _, _ -> error("no progress expected") }
        assertEquals(MigrationResult(total = 0), again)
        assertEquals(8, legacyCipher.decrypts.get())
        assertEquals(1, wrappers.wraps.get())
        assertEquals(1, wrappers.unwraps.get())
    }

    @Test
    fun `no legacy row means no key, no Keystore call and no progress`() = runBlocking {
        assertEquals(MigrationResult(total = 0), migrator().run { _, _ -> error("no progress expected") })
        assertTrue(dataKeyDao.rows.isEmpty())
        assertEquals(0, keystore.generations.get())
        assertEquals(0, wrappers.wraps.get() + wrappers.unwraps.get())
        assertEquals(0, legacyCipher.decrypts.get())
    }

    @Test
    fun `the record of the data key is committed before the first row names it`() = runBlocking {
        seedLegacy("acc-1")
        seedLegacy("acc-2")
        var checked = 0
        accounts.beforeMigrate = {
            assertTrue(1 in dataKeyDao.rows.keys)
            checked++
        }
        assertEquals(2, migrator().run().migrated)
        assertEquals(2, checked)
    }

    @Test
    fun `the compare-and-set refuses a version without a record, version 0 and changed bytes`() = runBlocking {
        val row = seedLegacy("acc-1")
        val sealed = ByteArray(40) { 2 }
        assertEquals(0, accounts.migrateSecret("acc-1", sealed, 1, row.secretBlob))
        dataKeyDao.rows[1] = DataKeyEntity(1, TEST_WRAP_ALIAS, ByteArray(62), 0L)
        assertEquals(0, accounts.migrateSecret("acc-1", sealed, 0, row.secretBlob))
        assertEquals(0, accounts.migrateSecret("acc-1", sealed, 1, row.secretBlob.copyOf().also { it[5] = (it[5] + 1).toByte() }))
        assertEquals(0, accounts.migrateSecret("missing", sealed, 1, row.secretBlob))
        assertArrayEquals(row.secretBlob, accounts.stored("acc-1").secretBlob)
        assertEquals(0, accounts.stored("acc-1").keyVersion)

        assertEquals(1, accounts.migrateSecret("acc-1", sealed, 1, row.secretBlob))
        // Never twice: the row is no longer legacy.
        assertEquals(0, accounts.migrateSecret("acc-1", ByteArray(40) { 3 }, 1, row.secretBlob))
        assertArrayEquals(sealed, accounts.stored("acc-1").secretBlob)
    }

    // ---- Crash safety and resuming -------------------------------------------------------------

    @Test
    fun `a kill between two rows resumes with the rest and leaves converted rows alone`() = runBlocking {
        val ids = (1..5).map { "acc-$it" }
        ids.forEach { seedLegacy(it) }

        lateinit var job: Job
        job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            migrator().run { done, _ -> if (done == 2) job.cancel() }
        }
        job.start()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(listOf(1, 1, 0, 0, 0), ids.map { accounts.stored(it).keyVersion })
        val converted = ids.take(2).map { accounts.stored(it).secretBlob.copyOf() }
        assertAllWiped()

        // The next process: a new holder over the same tables and Keystore.
        val next = newHolder()
        val progress = mutableListOf<Pair<Int, Int>>()
        val result = migrator(next).run { done, total -> progress += done to total }

        assertEquals(MigrationResult(total = 3, migrated = 3), result)
        assertEquals((0..3).map { it to 3 }, progress)
        assertEquals(setOf(1), dataKeyDao.rows.keys)
        assertEquals(1, wrappers.wraps.get())
        assertEquals(1, keystore.generations.get())
        ids.take(2).forEachIndexed { i, id -> assertArrayEquals(converted[i], accounts.stored(id).secretBlob) }
        val key = next.key(1)
        ids.forEach { assertArrayEquals(SECRET, DataKeyCipher.open(key, it, accounts.stored(it).secretBlob)) }
    }

    @Test
    fun `a kill after the key was stored and before the first row reuses that key`() = runBlocking {
        seedLegacy("acc-1")
        seedLegacy("acc-2")
        accounts.beforeMigrate = { throw CancellationException("killed") }
        val job = launch(Dispatchers.Default) { migrator().run() }
        job.join()
        assertEquals(setOf(1), dataKeyDao.rows.keys)
        assertEquals(0, accounts.migrations)

        accounts.beforeMigrate = {}
        val next = newHolder()
        assertEquals(2, migrator(next).run().migrated)
        assertEquals(setOf(1), dataKeyDao.rows.keys)
        assertEquals(1, wrappers.wraps.get())
        assertEquals(1, keystore.generations.get())
    }

    @Test
    fun `a cancellation right after a legacy decrypt still wipes the plaintext`() = runBlocking {
        seedLegacy("acc-1")
        lateinit var job: Job
        legacyCipher.afterDecrypt = { job.cancel() }
        job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) { migrator().run() }
        job.start()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, legacyCipher.returned.size)
        assertAllWiped()
        assertEquals(0, accounts.stored("acc-1").keyVersion)
    }

    // ---- Concurrent writers ---------------------------------------------------------------------

    @Test
    fun `rows changed or deleted meanwhile are skipped and never overwritten`() = runBlocking {
        seedLegacy("acc-hotp", type = "HOTP", counter = 7)
        seedLegacy("acc-deleted")
        seedLegacy("acc-replaced")
        seedLegacy("acc-kept")
        val replacement = ByteArray(40) { 9 }
        accounts.beforeMigrate = { id ->
            when (id) {
                // A "next code" committed between the decrypt and the write: its counter is kept.
                "acc-hotp" -> {
                    val row = accounts.stored(id)
                    assertEquals(1, accounts.advanceCounter(id, row.counter, row.secretBlob))
                }
                "acc-deleted" -> accounts.delete(id)
                "acc-replaced" -> {
                    accounts.replace(id, "New", "bob", "TOTP", "SHA1", 6, 30, 0, replacement, 1)
                }
            }
        }

        val result = migrator().run()

        assertEquals(MigrationResult(total = 4, migrated = 2, changed = 2), result)
        assertEquals(8L, accounts.stored("acc-hotp").counter)
        assertEquals(1, accounts.stored("acc-hotp").keyVersion)
        assertArrayEquals(SECRET, DataKeyCipher.open(holder.key(1), "acc-hotp", accounts.stored("acc-hotp").secretBlob))
        assertNull(accounts.getById("acc-deleted"))
        assertArrayEquals(replacement, accounts.stored("acc-replaced").secretBlob)
        assertEquals("New", accounts.stored("acc-replaced").issuer)
        assertNull(accounts.stored("acc-replaced").legacySecretBlob)
        assertEquals(1, accounts.stored("acc-kept").keyVersion)
        assertAllWiped()
    }

    @Test
    fun `a next code that read the legacy row cannot commit after the row was converted`() = runBlocking {
        val old = seedLegacy("acc-1", type = "HOTP", counter = 3)
        assertEquals(1, migrator().run().migrated)
        // The unchanged HOTP compare-and-set compares the secret bytes, so the stale read loses.
        assertEquals(0, accounts.advanceCounter("acc-1", 3, old.secretBlob))
        assertEquals(3L, accounts.stored("acc-1").counter)
        assertEquals(1, accounts.advanceCounter("acc-1", 3, accounts.stored("acc-1").secretBlob))
    }

    @Test
    fun `two migrators at once convert every row once and share one key`() = runBlocking {
        val ids = (1..20).map { "acc-$it" }
        ids.forEach { seedLegacy(it) }
        val start = CompletableDeferred<Unit>()
        // Both have taken their snapshot of all 20 rows before either writes one: each first
        // write of "acc-1" waits until the other migrator has reached it too.
        val arrivals = AtomicInteger()
        val bothSnapshotted = CompletableDeferred<Unit>()
        accounts.beforeMigrate = { id ->
            if (id == "acc-1") {
                if (arrivals.incrementAndGet() == 2) bothSnapshotted.complete(Unit)
                bothSnapshotted.await()
            }
        }
        // Two holders: the worst case, as if two processes raced (the app has one per process).
        val holders = listOf(holder, newHolder())
        val results = holders.map { h ->
            async(Dispatchers.Default) {
                start.await()
                migrator(h).run()
            }
        }
        start.complete(Unit)
        val (a, b) = results.awaitAll()

        assertEquals(20, a.migrated + b.migrated)
        assertEquals(40, a.migrated + a.changed + b.migrated + b.changed)
        assertEquals(setOf(1), dataKeyDao.rows.keys)
        assertEquals(20, accounts.migrations)
        val key = holder.key(1)
        ids.forEach {
            assertEquals(1, accounts.stored(it).keyVersion)
            assertArrayEquals(SECRET, DataKeyCipher.open(key, it, accounts.stored(it).secretBlob))
        }
        assertAllWiped()
    }

    // ---- Failures ------------------------------------------------------------------------------

    @Test
    fun `an unreadable legacy row is left as it is, counted, and does not make a run loop`() = runBlocking {
        seedLegacy("acc-1")
        val broken = seedLegacy("acc-2").secretBlob.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        accounts.put(accounts.stored("acc-2").copyWith(secretBlob = broken))
        seedLegacy("acc-3")
        val progress = mutableListOf<Pair<Int, Int>>()

        val result = migrator().run { done, total -> progress += done to total }

        assertEquals(MigrationResult(total = 3, migrated = 2, unreadable = 1), result)
        assertFalse(result.retryAdvised)
        assertEquals((0..3).map { it to 3 }, progress)
        assertArrayEquals(broken, accounts.stored("acc-2").secretBlob)
        assertEquals(0, accounts.stored("acc-2").keyVersion)
        assertNull(accounts.stored("acc-2").legacySecretBlob)

        // The next run tries it once more and stops.
        assertEquals(MigrationResult(total = 1, unreadable = 1), migrator().run())
        assertEquals(4, legacyCipher.decrypts.get())
    }

    @Test
    fun `a transient legacy failure is left for the next run and never counted unreadable`() = runBlocking {
        seedLegacy("acc-1")
        seedLegacy("acc-2")
        legacyCipher.failure = { id -> if (id == "acc-2") SecretCipherException("Decryption failed", ProviderException("busy")) else null }

        val result = migrator().run()
        assertEquals(MigrationResult(total = 2, migrated = 1, retryLater = 1), result)
        assertTrue(result.retryAdvised)
        assertEquals(0, accounts.stored("acc-2").keyVersion)

        legacyCipher.failure = { null }
        assertEquals(MigrationResult(total = 1, migrated = 1), migrator().run())
        assertEquals(1, accounts.stored("acc-2").keyVersion)
    }

    @Test
    fun `a payload that does not authenticate is definite even behind other causes`() = runBlocking {
        seedLegacy("acc-1")
        legacyCipher.failure = { SecretCipherException("Decryption failed", ProviderException("wrapped", AEADBadTagException("Tag mismatch"))) }
        assertEquals(MigrationResult(total = 1, unreadable = 1), migrator().run())
    }

    @Test
    fun `a lost legacy key stops the run and touches no row`() = runBlocking {
        val ids = (1..3).map { "acc-$it" }
        val before = ids.map { seedLegacy(it).secretBlob }
        legacyKeys.lost = true
        val progress = mutableListOf<Pair<Int, Int>>()

        val result = migrator().run { done, total -> progress += done to total }

        assertEquals(MigrationResult(total = 3, unreadable = 3, stop = MigrationStop.LEGACY_KEY_GONE), result)
        assertFalse(result.retryAdvised)
        assertEquals(listOf(0 to 3, 3 to 3), progress)
        assertEquals(1, legacyCipher.decrypts.get())
        ids.forEachIndexed { i, id -> assertArrayEquals(before[i], accounts.stored(id).secretBlob) }
        assertEquals(0, accounts.migrations)
    }

    @Test
    fun `a legacy key lost in the middle keeps the rows converted so far`() = runBlocking {
        val ids = (1..4).map { "acc-$it" }
        ids.forEach { seedLegacy(it) }
        legacyCipher.failure = { id -> if (id >= "acc-3") KeyUnavailableException() else null }

        val result = migrator().run()

        assertEquals(MigrationResult(total = 4, migrated = 2, unreadable = 2, stop = MigrationStop.LEGACY_KEY_GONE), result)
        assertEquals(listOf(1, 1, 0, 0), ids.map { accounts.stored(it).keyVersion })
    }

    @Test
    fun `a sealed secret that does not open to the same bytes stops the run and writes nothing`() = runBlocking {
        seedLegacy("acc-1")
        seedLegacy("acc-2")
        val wrong = ByteArray(SECRET.size) { 1 }
        val result = migrator(openSealed = { _, _, _ -> wrong }).run()

        assertEquals(MigrationResult(total = 2, stop = MigrationStop.CHECK_FAILED), result)
        assertTrue(result.retryAdvised)
        assertEquals(0, accounts.migrations)
        assertEquals(listOf(0, 0), listOf("acc-1", "acc-2").map { accounts.stored(it).keyVersion })
        assertTrue(wrong.all { it == 0.toByte() })
        assertAllWiped()

        val failing = migrator(openSealed = { _, _, _ -> throw SecretCipherException("Decryption failed") }).run()
        assertEquals(MigrationStop.CHECK_FAILED, failing.stop)
        assertEquals(0, accounts.migrations)
    }

    @Test
    fun `a database error stops the run and the next run converts the rest`() = runBlocking {
        val ids = (1..3).map { "acc-$it" }
        ids.forEach { seedLegacy(it) }
        accounts.beforeMigrate = { id -> if (id == "acc-2") throw SQLException("disk I/O error") }

        val result = migrator().run()
        assertEquals(MigrationResult(total = 3, migrated = 1, stop = MigrationStop.STORAGE_FAILED), result)
        assertTrue(result.retryAdvised)
        assertEquals(listOf(1, 0, 0), ids.map { accounts.stored(it).keyVersion })
        assertAllWiped()

        accounts.beforeMigrate = {}
        assertEquals(MigrationResult(total = 2, migrated = 2), migrator().run())
    }

    // ---- No usable data key --------------------------------------------------------------------

    @Test
    fun `rows that name a missing key version stop the run before any key or decrypt`() = runBlocking {
        seedLegacy("acc-1")
        accounts.put(seedLegacy("acc-orphan").copyWith(keyVersion = 2))

        val result = migrator().run()

        assertEquals(MigrationResult(total = 1, stop = MigrationStop.NEW_KEY_REQUIRED), result)
        assertFalse(result.retryAdvised)
        assertTrue(dataKeyDao.rows.isEmpty())
        assertEquals(0, keystore.generations.get())
        assertEquals(0, legacyCipher.decrypts.get())
        assertEquals(0, accounts.stored("acc-1").keyVersion)
    }

    @Test
    fun `a lost wrapping key blocks the run until a new key is started, which then takes the rows`() = runBlocking {
        seedLegacy("acc-1")
        // An earlier process made version 1; its wrapping key is gone since.
        newHolder().prepareWriteKey()
        keystore.lose(TEST_WRAP_ALIAS)
        val fresh = newHolder()

        assertEquals(MigrationResult(total = 1, stop = MigrationStop.NEW_KEY_REQUIRED), migrator(fresh).run())
        assertEquals(setOf(1), dataKeyDao.rows.keys)
        assertEquals(0, legacyCipher.decrypts.get())

        assertEquals(2, fresh.startNewKey())
        assertEquals(MigrationResult(total = 1, migrated = 1), migrator(fresh).run())
        assertEquals(2, accounts.stored("acc-1").keyVersion)
        assertArrayEquals(SECRET, DataKeyCipher.open(fresh.key(2), "acc-1", accounts.stored("acc-1").secretBlob))
    }

    @Test
    fun `a transient Keystore failure for the data key converts nothing and creates nothing`() = runBlocking {
        seedLegacy("acc-1")
        newHolder().prepareWriteKey()
        val generations = keystore.generations.get()
        keystore.lookupFailure = { ProviderException("busy") }
        val fresh = newHolder()

        val result = migrator(fresh).run()

        assertEquals(MigrationResult(total = 1, stop = MigrationStop.KEY_TEMPORARILY_UNAVAILABLE), result)
        assertTrue(result.retryAdvised)
        assertEquals(setOf(1), dataKeyDao.rows.keys)
        assertEquals(generations, keystore.generations.get())
        assertEquals(0, legacyCipher.decrypts.get())

        keystore.lookupFailure = null
        assertEquals(MigrationResult(total = 1, migrated = 1), migrator(fresh).run())
        assertEquals(1, accounts.stored("acc-1").keyVersion)
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private fun newHolder() = DataKeyHolder(keyStore, wrappers::wrapperFor, aliasBase = TEST_WRAP_ALIAS)

    private fun migrator(
        dataKeys: DataKeyHolder = holder,
        openSealed: (DataKey, String, ByteArray) -> ByteArray = DataKeyCipher::open,
    ) = SecretMigrator(
        dao = accounts,
        legacy = legacySecretReader(legacyCipher),
        dataKeys = dataKeys,
        dispatcher = Dispatchers.IO,
        openSealed = openSealed,
    )

    /** Stores a legacy row of [id] as the app before the migration wrote it; returns it. */
    private fun seedLegacy(id: String, type: String = "TOTP", counter: Long = 0): AccountEntity {
        val blob = legacyCipher.encrypt(SECRET, accountAssociatedData(id))
        val row = AccountEntity(
            id, "Example", "alice", type, "SHA1", 6, 30, counter, blob, nextCreatedAt++,
            keyVersion = 0,
            legacySecretBlob = null,
        )
        accounts.put(row)
        return row
    }

    private fun assertAllWiped() {
        synchronized(legacyCipher.returned) {
            legacyCipher.returned.forEach { plaintext -> assertTrue(plaintext.all { it == 0.toByte() }) }
        }
    }

    private companion object {
        /** RFC 4226 / RFC 6238 test secret. */
        val SECRET = "12345678901234567890".toByteArray(Charsets.US_ASCII)
    }
}
