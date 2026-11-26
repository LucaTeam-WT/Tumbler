package io.github.lucateam_wt.tumbler.data.repository

import android.database.SQLException
import io.github.lucateam_wt.tumbler.data.crypto.AesGcmSecretCipher
import io.github.lucateam_wt.tumbler.data.crypto.CountingWrappers
import io.github.lucateam_wt.tumbler.data.crypto.DataKeyHolder
import io.github.lucateam_wt.tumbler.data.crypto.EnvelopeFormat
import io.github.lucateam_wt.tumbler.data.crypto.FakeKeystore
import io.github.lucateam_wt.tumbler.data.crypto.KeyProvider
import io.github.lucateam_wt.tumbler.data.db.AccountEntity
import io.github.lucateam_wt.tumbler.domain.repository.StorageState
import io.github.lucateam_wt.tumbler.domain.repository.WriteAccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.security.ProviderException
import java.util.Collections
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The startup gate over the real [SecretMigrator], [RoomDataKeyStore] and compare-and-set DAO
 * semantics, with the counting fake Keystore: what each storage state leads to, that a key is
 * never made without the migration or a confirmed new-key action, and the Keystore cost of a start.
 */
class MigratingStorageGateTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private class JvmKey : KeyProvider {
        private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun getOrCreateKey(): SecretKey = key
        override fun getExistingKey(): SecretKey = key
    }

    private val legacyCipher = AesGcmSecretCipher(JvmKey())
    private val keystore = FakeKeystore()
    private val wrappers = CountingWrappers(keystore)
    private val dataKeyDao = FakeDataKeyDao()
    private val accounts = FakeAccountDao { dataKeyDao.rows.keys.toSet() }
    private val scopes = Collections.synchronizedList(mutableListOf<CoroutineScope>())
    private var nextCreatedAt = 1_000L

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    /** A gate as one process start builds it: its own holder (nothing unwrapped yet) and scope. */
    private fun newProcess(): Pair<MigratingStorageGate, DataKeyHolder> {
        val holder = DataKeyHolder(RoomDataKeyStore(dataKeyDao, accounts), wrappers::wrapperFor, aliasBase = TEST_WRAP_ALIAS)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val migrator = SecretMigrator(accounts, legacySecretReader(legacyCipher), holder, Dispatchers.IO)
        return MigratingStorageGate(migrator, holder, scope) to holder
    }

    private suspend fun MigratingStorageGate.readyAfter(generation: Int): StorageState.Ready =
        withTimeout(10_000) { state.first { it is StorageState.Ready && it.generation > generation } as StorageState.Ready }

    private fun seedLegacy(id: String): AccountEntity {
        val row = AccountEntity(
            id, "Example", "alice", "TOTP", "SHA1", 6, 30, 0,
            legacyCipher.encrypt(SECRET, accountAssociatedData(id)), nextCreatedAt++,
            keyVersion = 0,
            legacySecretBlob = null,
        )
        accounts.put(row)
        return row
    }

    @Test
    fun `a fresh install is ready at once and makes no key`() = runBlocking {
        val (gate, _) = newProcess()

        gate.awaitReady()

        assertEquals(StorageState.Ready(WriteAccess.AVAILABLE, retryAdvised = false, generation = 1), gate.state.value)
        assertTrue(dataKeyDao.rows.isEmpty())
        assertEquals(0, keystore.generations.get())
        assertEquals(0, wrappers.unwraps.get())
    }

    @Test
    fun `legacy rows are converted with N of M progress before the gate is ready`() = runBlocking {
        val ids = (1..5).map { "acc-$it" }.onEach { seedLegacy(it) }
        val (gate, _) = newProcess()
        val seen = Collections.synchronizedList(mutableListOf<StorageState>())
        // The state while each row is being written: the rows before it are counted as done.
        accounts.beforeMigrate = { seen += gate.state.value }

        gate.start()
        val ready = gate.readyAfter(0)

        assertEquals(StorageState.Ready(WriteAccess.AVAILABLE, retryAdvised = false, generation = 1), ready)
        assertEquals((0 until 5).map { StorageState.Preparing(it, 5) }, seen)
        assertEquals(setOf(1), dataKeyDao.rows.keys)
        ids.forEach { assertEquals(1, accounts.stored(it).keyVersion) }
    }

    @Test
    fun `awaitReady waits for the migration and a cancelled waiter does not stop it`() = runBlocking {
        seedLegacy("acc-1")
        seedLegacy("acc-2")
        val (gate, _) = newProcess()
        val release = CompletableDeferred<Unit>()
        accounts.beforeMigrate = { release.await() }

        val waiter = async(Dispatchers.Default) { gate.awaitReady() }
        val leaving = launch(Dispatchers.Default) { gate.awaitReady() }
        withTimeout(10_000) { gate.state.first { it is StorageState.Preparing && it.total == 2 } }
        assertFalse(waiter.isCompleted)
        leaving.cancelAndJoin() // a screen went away
        release.complete(Unit)
        waiter.await()

        assertEquals(2, accounts.migrations)
        assertEquals(1, (gate.state.value as StorageState.Ready).generation)
    }

    @Test
    fun `start runs once however often it is called`() = runBlocking {
        seedLegacy("acc-1")
        val (gate, _) = newProcess()

        repeat(5) { gate.start() }
        gate.awaitReady()
        repeat(5) { gate.start() }
        gate.awaitReady()

        assertEquals(1, (gate.state.value as StorageState.Ready).generation)
        assertEquals(1, accounts.migrations)
    }

    @Test
    fun `a cold start after the migration costs one unwrap and makes nothing`() = runBlocking {
        (1..8).forEach { seedLegacy("acc-$it") }
        newProcess().first.awaitReady()
        val generations = keystore.generations.get()
        val wraps = wrappers.wraps.get()
        val unwraps = wrappers.unwraps.get()

        val (gate, _) = newProcess()
        gate.awaitReady()

        assertEquals(StorageState.Ready(WriteAccess.AVAILABLE, retryAdvised = false, generation = 1), gate.state.value)
        assertEquals(unwraps + 1, wrappers.unwraps.get())
        assertEquals(generations, keystore.generations.get())
        assertEquals(wraps, wrappers.wraps.get())
        assertEquals(8, accounts.migrations) // none on the second start
    }

    @Test
    fun `a lost wrapping key needs a confirmed new key, which keeps the old rows and the old record`() = runBlocking {
        seedLegacy("acc-1")
        newProcess().first.awaitReady()
        val oldRecord = dataKeyDao.rows.getValue(1)
        val oldRow = accounts.stored("acc-1")
        keystore.lose(EnvelopeFormat.wrapAlias(TEST_WRAP_ALIAS, 1))

        val (gate, _) = newProcess()
        gate.awaitReady()
        assertEquals(StorageState.Ready(WriteAccess.NEW_KEY_REQUIRED, retryAdvised = false, generation = 1), gate.state.value)
        assertEquals(setOf(1), dataKeyDao.rows.keys) // nothing was made on its own

        assertTrue(gate.startNewKey())

        assertEquals(StorageState.Ready(WriteAccess.AVAILABLE, retryAdvised = false, generation = 2), gate.state.value)
        assertEquals(setOf(1, 2), dataKeyDao.rows.keys)
        assertArrayEquals(oldRecord.wrappedKey, dataKeyDao.rows.getValue(1).wrappedKey)
        assertEquals(1, accounts.stored("acc-1").keyVersion)
        assertArrayEquals(oldRow.secretBlob, accounts.stored("acc-1").secretBlob)
        assertArrayEquals(oldRow.legacySecretBlob, accounts.stored("acc-1").legacySecretBlob)
    }

    @Test
    fun `rows that name a key that is not stored need a new key with a higher version`() = runBlocking {
        accounts.put(
            AccountEntity("orphan", "Example", "alice", "TOTP", "SHA1", 6, 30, 0, byteArrayOf(2, 12), 1, keyVersion = 3, legacySecretBlob = null),
        )
        val (gate, _) = newProcess()

        gate.awaitReady()
        assertEquals(WriteAccess.NEW_KEY_REQUIRED, (gate.state.value as StorageState.Ready).writeAccess)
        assertTrue(dataKeyDao.rows.isEmpty())

        assertTrue(gate.startNewKey())
        assertEquals(setOf(4), dataKeyDao.rows.keys) // a version number is never used twice
        assertEquals(WriteAccess.AVAILABLE, (gate.state.value as StorageState.Ready).writeAccess)
    }

    @Test
    fun `a transient Keystore error advises a retry, makes no key, and the retry recovers`() = runBlocking {
        seedLegacy("acc-1")
        newProcess().first.awaitReady()
        wrappers.failUnwrapsWith = { ProviderException("keystore busy") }

        val (gate, _) = newProcess()
        gate.awaitReady()
        assertEquals(
            StorageState.Ready(WriteAccess.TEMPORARILY_UNAVAILABLE, retryAdvised = true, generation = 1),
            gate.state.value,
        )

        // Even a confirmed new-key action makes nothing while the key may only be busy.
        assertFalse(gate.startNewKey())
        assertEquals(setOf(1), dataKeyDao.rows.keys)
        assertEquals(StorageState.Ready(WriteAccess.TEMPORARILY_UNAVAILABLE, retryAdvised = true, generation = 2), gate.state.value)

        wrappers.failUnwrapsWith = null
        gate.retry()
        assertEquals(StorageState.Ready(WriteAccess.AVAILABLE, retryAdvised = false, generation = 3), gate.readyAfter(2))
        assertEquals(setOf(1), dataKeyDao.rows.keys)
    }

    @Test
    fun `a transient error during the migration leaves the rows for a retry`() = runBlocking {
        seedLegacy("acc-1")
        seedLegacy("acc-2")
        keystore.lookupFailure = { ProviderException("keystore busy") }
        val (gate, _) = newProcess()

        gate.awaitReady()
        val ready = gate.state.value as StorageState.Ready
        assertTrue(ready.retryAdvised)
        // No key was stored, so a later add may still make the first one.
        assertEquals(WriteAccess.AVAILABLE, ready.writeAccess)
        assertTrue(dataKeyDao.rows.isEmpty())
        assertEquals(0, accounts.stored("acc-1").keyVersion)

        keystore.lookupFailure = null
        gate.retry()
        assertEquals(StorageState.Ready(WriteAccess.AVAILABLE, retryAdvised = false, generation = 2), gate.readyAfter(1))
        assertEquals(1, accounts.stored("acc-1").keyVersion)
        assertEquals(1, accounts.stored("acc-2").keyVersion)
    }

    @Test
    fun `an unexpected failure ends in a ready state that advises a retry`() = runBlocking {
        seedLegacy("acc-1")
        accounts.beforeMigrate = { throw IllegalStateException("unexpected") }
        val (gate, _) = newProcess()

        gate.awaitReady()

        assertTrue((gate.state.value as StorageState.Ready).retryAdvised)
        accounts.beforeMigrate = { throw SQLException("disk I/O error") }
        gate.retry()
        assertTrue(gate.readyAfter(1).retryAdvised)
        assertEquals(0, accounts.stored("acc-1").keyVersion)
    }

    @Test
    fun `retry shows Preparing at once so a waiting backup does not go on with the old outcome`() = runBlocking {
        val (gate, _) = newProcess()
        gate.awaitReady()
        val release = CompletableDeferred<Unit>()
        seedLegacy("acc-1")
        accounts.beforeMigrate = { release.await() }

        gate.retry()
        assertTrue(gate.state.value is StorageState.Preparing)
        val backup = async(Dispatchers.Default) { gate.awaitReady() }
        withTimeout(10_000) { gate.state.first { it is StorageState.Preparing && it.total == 1 } }
        assertFalse(backup.isCompleted)
        release.complete(Unit)
        backup.await()
        assertEquals(1, accounts.stored("acc-1").keyVersion)
    }

    private companion object {
        val SECRET = "12345678901234567890".toByteArray(Charsets.US_ASCII)
    }
}
