package io.github.lucateam_wt.tumbler.data.crypto

import io.github.lucateam_wt.tumbler.domain.crypto.KeyTemporarilyUnavailableException
import io.github.lucateam_wt.tumbler.domain.crypto.KeyUnavailableException
import io.github.lucateam_wt.tumbler.domain.crypto.NewStorageKeyRequiredException
import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipherException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.AEADBadTagException
import kotlin.concurrent.thread

class DataKeyHolderTest {

    private val keystore = FakeKeystore()
    private val wrappers = CountingWrappers(keystore)
    private val store = FakeDataKeyStore()

    // ---- Reading -------------------------------------------------------------------------------

    @Test(timeout = 20_000)
    fun `a read unwraps once and later reads cost no database or Keystore call`() {
        val seeded = seedVersion1()
        val holder = newHolder()
        val key = runBlocking { holder.key(1) }
        assertArrayEquals(seeded, key.bytes())
        assertEquals(1, wrappers.unwraps.get())

        store.calls.set(0)
        repeat(100) { assertSame(key, runBlocking { holder.key(1) }) }
        assertEquals(1, wrappers.unwraps.get())
        assertEquals(0, wrappers.wraps.get())
        assertEquals(0, store.calls.get())
    }

    @Test(timeout = 20_000)
    fun `concurrent first readers share one unwrap`() {
        val seeded = seedVersion1()
        val gate = CountDownLatch(1)
        wrappers.beforeUnwrap = { gate.await() }
        val holder = newHolder()

        val keys = runBlocking {
            val readers = List(16) { async(Dispatchers.Default) { holder.key(1) } }
            awaitCondition { wrappers.unwraps.get() == 1 }
            Thread.sleep(200) // let the other readers reach the shared unwrap
            gate.countDown()
            readers.awaitAll()
        }

        assertEquals(1, wrappers.unwraps.get())
        keys.forEach { assertSame(keys[0], it) }
        assertArrayEquals(seeded, keys[0].bytes())
    }

    @Test(timeout = 20_000)
    fun `a cancelled reader does not abort the shared unwrap`() {
        val seeded = seedVersion1()
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        wrappers.beforeUnwrap = { entered.countDown(); gate.await() }
        val holder = newHolder()

        runBlocking {
            val reader = launch(Dispatchers.Default) { holder.key(1) }
            entered.await()
            reader.cancelAndJoin()
            assertTrue(reader.isCancelled)
            gate.countDown()
            assertArrayEquals(seeded, holder.key(1).bytes())
        }
        assertEquals(1, wrappers.unwraps.get())
    }

    // ---- Key states ----------------------------------------------------------------------------

    @Test(timeout = 20_000)
    fun `state absent - no record is unavailable, creates nothing and is not cached`() {
        val holder = newHolder()
        failsWith<KeyUnavailableException> { holder.key(1) }
        assertEquals(0, wrappers.wraps.get())
        assertEquals(0, wrappers.unwraps.get())
        assertEquals(0, keystore.generations.get())
        assertFalse(BASE in keystore)
        assertTrue(store.records.isEmpty())

        // The record appears (written by another holder): the next read finds it.
        val seeded = seedVersion1()
        assertArrayEquals(seeded, runBlocking { holder.key(1) }.bytes())
    }

    @Test(timeout = 20_000)
    fun `state ready - the record unwraps to the stored key`() {
        val seeded = seedVersion1()
        val key = runBlocking { newHolder().key(1) }
        assertEquals(1, key.version)
        assertFalse(key.isWiped)
        assertArrayEquals(seeded, key.bytes())
    }

    @Test(timeout = 20_000)
    fun `state lost - a missing wrapping key is unavailable, cached and never recreated`() {
        seedVersion1()
        val before = store.records.getValue(1).wrappedKey.copyOf()
        keystore.lose(BASE)
        val holder = newHolder()

        failsWith<KeyUnavailableException> { holder.key(1) }
        failsWith<KeyUnavailableException> { holder.key(1) }
        assertEquals(1, wrappers.unwraps.get())
        assertEquals(0, keystore.generations.get())
        assertFalse(BASE in keystore)
        assertArrayEquals(before, store.records.getValue(1).wrappedKey)
    }

    @Test(timeout = 20_000)
    fun `state temporarily unavailable - a wrapping key that the Keystore refuses to use is retried, not cached`() {
        val seeded = seedVersion1()
        keystore.invalidate(BASE) // every operation fails with an InvalidKeyException
        val holder = newHolder()

        failsWith<KeyTemporarilyUnavailableException> { holder.key(1) }
        failsWith<KeyTemporarilyUnavailableException> { holder.key(1) }
        assertEquals(2, wrappers.unwraps.get())
        assertEquals(0, keystore.generations.get())
        assertEquals(setOf(1), store.records.keys)

        keystore.revalidate(BASE)
        assertArrayEquals(seeded, runBlocking { holder.key(1) }.bytes())
    }

    @Test(timeout = 20_000)
    fun `state unreadable - a record that does not authenticate fails and is cached`() {
        seedVersion1()
        val record = store.records.getValue(1)
        store.records[1] = record.withBytes(record.wrappedKey.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() })
        val holder = newHolder()

        val e = failsWith<SecretCipherException> { holder.key(1) }
        assertFalse(e is KeyUnavailableException)
        assertFalse(e is KeyTemporarilyUnavailableException)
        failsWith<SecretCipherException> { holder.key(1) }
        assertEquals(1, wrappers.unwraps.get())
    }

    @Test(timeout = 20_000)
    fun `a Keystore error during an unwrap is not cached and the next read succeeds`() {
        val seeded = seedVersion1()
        val holder = newHolder()
        val transient = listOf<() -> Exception>(
            { KeyStoreException("busy") },
            { ProviderException("busy") },
            { IOException("busy") },
        )
        for ((attempt, failure) in transient.withIndex()) {
            keystore.lookupFailure = failure
            val e = failsWith<KeyTemporarilyUnavailableException> { holder.key(1) }
            assertEquals("Encryption key temporarily unavailable", e.message)
            assertEquals(attempt + 1, wrappers.unwraps.get())
        }

        keystore.lookupFailure = null
        assertArrayEquals(seeded, runBlocking { holder.key(1) }.bytes())
        assertEquals(4, wrappers.unwraps.get())
        assertEquals(0, keystore.generations.get())
    }

    @Test(timeout = 20_000)
    fun `a definite unwrap failure stays cached until clearFailures`() {
        seedVersion1()
        wrappers.failUnwrapsWith = { SecretCipherException("Decryption failed", AEADBadTagException("Tag mismatch")) }
        val holder = newHolder()

        repeat(3) {
            val e = failsWith<SecretCipherException> { holder.key(1) }
            assertFalse(e is KeyTemporarilyUnavailableException)
        }
        failsWith<SecretCipherException> { holder.writeKey(1) }
        assertEquals(1, wrappers.unwraps.get())

        holder.clearFailures()
        failsWith<SecretCipherException> { holder.key(1) }
        assertEquals(2, wrappers.unwraps.get())
    }

    @Test(timeout = 20_000)
    fun `concurrent readers share one unwrap that fails with a Keystore error, and the next read tries again`() {
        val seeded = seedVersion1()
        val gate = CountDownLatch(1)
        wrappers.beforeUnwrap = { gate.await() }
        keystore.lookupFailure = { ProviderException("busy") }
        val holder = newHolder()

        val failures = runBlocking {
            val readers = List(16) {
                async(Dispatchers.Default) { runCatching { holder.key(1) }.exceptionOrNull() }
            }
            awaitCondition { wrappers.unwraps.get() == 1 }
            Thread.sleep(200) // let the other readers reach the shared unwrap
            gate.countDown()
            readers.awaitAll()
        }

        assertEquals(1, wrappers.unwraps.get())
        failures.forEach { assertTrue(it.toString(), it is KeyTemporarilyUnavailableException) }
        keystore.lookupFailure = null
        assertArrayEquals(seeded, runBlocking { holder.key(1) }.bytes())
        assertEquals(2, wrappers.unwraps.get())
    }

    @Test(timeout = 20_000)
    fun `state malformed - a record of the wrong shape is refused without a Keystore call`() {
        seedVersion1()
        val good = store.records.getValue(1)
        val variants = listOf(
            good.withBytes(good.wrappedKey.copyOf(61)),
            good.withBytes(good.wrappedKey.copyOf(63)),
            good.withBytes(ByteArray(0)),
            good.withBytes(good.wrappedKey.copyOf().also { it[0] = 2 }),
            good.withBytes(good.wrappedKey.copyOf().also { it[1] = 11 }),
            WrappedDataKey(1, "other_alias", good.wrappedKey, good.createdAtMillis),
            WrappedDataKey(1, "${BASE}_k1", good.wrappedKey, good.createdAtMillis),
            WrappedDataKey(2, "${BASE}_k2", good.wrappedKey, good.createdAtMillis),
        )
        for (variant in variants) {
            store.records[1] = variant
            val e = failsWith<SecretCipherException> { newHolder().key(1) }
            assertFalse(e is KeyUnavailableException)
        }
        assertEquals(0, wrappers.unwraps.get())
    }

    @Test(timeout = 20_000)
    fun `a wrapped key opens only for its own version and alias`() {
        val wrapper = wrappers.wrapperFor(BASE)
        val wrapped = wrapper.wrap(ByteArray(32) { 1 }, EnvelopeFormat.wrapAssociatedData(1, BASE))

        assertEquals(32, wrapper.unwrap(wrapped, EnvelopeFormat.wrapAssociatedData(1, BASE)).size)
        assertThrows(SecretCipherException::class.java) { wrapper.unwrap(wrapped, EnvelopeFormat.wrapAssociatedData(2, BASE)) }
        assertThrows(SecretCipherException::class.java) { wrapper.unwrap(wrapped, EnvelopeFormat.wrapAssociatedData(1, "${BASE}_k2")) }
        assertThrows(SecretCipherException::class.java) { wrapper.unwrap(wrapped, EnvelopeFormat.secretAssociatedData(1, BASE)) }
    }

    @Test(timeout = 20_000)
    fun `state wiped - the next read unwraps again`() {
        val seeded = seedVersion1()
        val holder = newHolder()
        val first = runBlocking { holder.key(1) }
        holder.wipe()
        assertTrue(first.isWiped)

        val second = runBlocking { holder.key(1) }
        assertNotSame(first, second)
        assertArrayEquals(seeded, second.bytes())
        assertEquals(2, wrappers.unwraps.get())
    }

    @Test(timeout = 20_000)
    fun `clearFailures lets a cached cipher failure try again`() {
        val seeded = seedVersion1()
        wrappers.failUnwrapsWith = { SecretCipherException("Decryption failed", AEADBadTagException("Tag mismatch")) }
        val holder = newHolder()
        failsWith<SecretCipherException> { holder.key(1) }
        wrappers.failUnwrapsWith = null
        failsWith<SecretCipherException> { holder.key(1) }
        assertEquals(1, wrappers.unwraps.get())

        holder.clearFailures()
        assertArrayEquals(seeded, runBlocking { holder.key(1) }.bytes())
        assertEquals(2, wrappers.unwraps.get())
    }

    @Test(timeout = 20_000)
    fun `a failure that is not a cipher error is not cached`() {
        val seeded = seedVersion1()
        wrappers.failUnwrapsWith = { IllegalStateException("transient") }
        val holder = newHolder()
        failsWith<IllegalStateException> { holder.key(1) }
        wrappers.failUnwrapsWith = null
        assertArrayEquals(seeded, runBlocking { holder.key(1) }.bytes())
        assertEquals(2, wrappers.unwraps.get())
    }

    @Test(timeout = 20_000)
    fun `versions below 1 are refused before any database call`() {
        val holder = newHolder()
        for (version in listOf(0, -1, Int.MIN_VALUE)) {
            failsWith<SecretCipherException> { holder.key(version) }
            failsWith<SecretCipherException> { holder.writeKey(version) }
        }
        assertEquals(0, store.calls.get())
    }

    // ---- Wipe ----------------------------------------------------------------------------------

    @Test(timeout = 20_000)
    fun `wipe never zeroes a key while an operation uses it`() {
        val seeded = seedVersion1()
        val holder = newHolder()
        val key = runBlocking { holder.key(1) }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var seen: ByteArray? = null

        val user = thread {
            key.use { bytes ->
                entered.countDown()
                release.await()
                seen = bytes.copyOf()
                Unit
            }
        }
        entered.await()
        val wiper = thread { holder.wipe() }
        awaitBlocked(wiper)
        assertFalse(key.isWiped)
        release.countDown()
        user.join()
        wiper.join()

        assertArrayEquals(seeded, seen)
        assertTrue(key.isWiped)
    }

    @Test(timeout = 20_000)
    fun `an unwrap that finishes after wipe is not kept`() {
        val seeded = seedVersion1()
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        wrappers.beforeUnwrap = {
            if (wrappers.unwraps.get() == 1) {
                entered.countDown()
                gate.await()
            }
        }
        val holder = newHolder()

        val key = runBlocking {
            val reader = async(Dispatchers.Default) { holder.key(1) }
            entered.await()
            holder.wipe()
            gate.countDown()
            reader.await()
        }

        // The stale unwrap was wiped; the reader looked again and got a fresh key.
        assertFalse(key.isWiped)
        assertArrayEquals(seeded, key.bytes())
        assertEquals(2, wrappers.unwraps.get())
    }

    // ---- Writing -------------------------------------------------------------------------------

    @Test(timeout = 20_000)
    fun `the first write key is version 1 under the base alias`() {
        val holder = newHolder()
        assertEquals(1, runBlocking { holder.prepareWriteKey() })

        val record = store.records.getValue(1)
        assertEquals(BASE, record.kekAlias)
        assertEquals(62, record.wrappedKey.size)
        assertEquals(NOW, record.createdAtMillis)
        assertEquals(1, wrappers.wraps.get())
        assertEquals(1, wrappers.unwraps.get()) // the check that the wrapped key unwraps
        assertEquals(1, keystore.generations.get())

        val key = runBlocking { holder.writeKey(1) }
        assertEquals(1, runBlocking { holder.prepareWriteKey() })
        assertSame(key, runBlocking { holder.key(1) })
        assertEquals(1, wrappers.wraps.get())
        assertEquals(1, wrappers.unwraps.get())

        // Another process unwraps the same key from the record.
        assertArrayEquals(key.bytes(), runBlocking { newHolder().key(1) }.bytes())
    }

    @Test(timeout = 20_000)
    fun `rows that name a version without a record need a new key, which goes above every row`() {
        store.highestRowVersion = 3
        val holder = newHolder()

        // Orphaned rows: writing is refused and nothing is created automatically.
        failsWith<NewStorageKeyRequiredException> { holder.prepareWriteKey() }
        assertEquals(WriteKeyStatus.NEW_KEY_REQUIRED, runBlocking { holder.writeKeyStatus() })
        assertTrue(store.records.isEmpty())
        assertEquals(0, wrappers.wraps.get())

        assertEquals(4, runBlocking { holder.startNewKey() })
        assertEquals(setOf(4), store.records.keys)
        assertEquals("${BASE}_k4", store.records.getValue(4).kekAlias)
        assertEquals(4, runBlocking { holder.prepareWriteKey() })
    }

    @Test(timeout = 20_000)
    fun `a lost wrapping key refuses writing until a new key is started, which keeps the old record`() {
        seedVersion1()
        val old = store.records.getValue(1)
        val oldBytes = old.wrappedKey.copyOf()
        keystore.lose(BASE)
        val holder = newHolder()

        val refused = failsWith<NewStorageKeyRequiredException> { holder.prepareWriteKey() }
        assertTrue(refused.cause is KeyUnavailableException)
        assertEquals(WriteKeyStatus.NEW_KEY_REQUIRED, runBlocking { holder.writeKeyStatus() })
        assertEquals(setOf(1), store.records.keys)
        assertEquals(0, keystore.generations.get())
        assertEquals(0, wrappers.wraps.get())

        assertEquals(2, runBlocking { holder.startNewKey() })
        assertSame(old, store.records.getValue(1))
        assertArrayEquals(oldBytes, store.records.getValue(1).wrappedKey)
        assertEquals("${BASE}_k2", store.records.getValue(2).kekAlias)
        assertEquals(1, keystore.generations.get())
        assertFalse(BASE in keystore)
        assertEquals(2, runBlocking { holder.prepareWriteKey() })
        assertEquals(WriteKeyStatus.READY, runBlocking { holder.writeKeyStatus() })
        // A second start finds the new key usable and makes nothing.
        assertEquals(2, runBlocking { holder.startNewKey() })
        assertEquals(1, wrappers.wraps.get())
        // Version 1 stays unreadable; nothing replaced it.
        failsWith<KeyUnavailableException> { holder.key(1) }
    }

    @Test(timeout = 20_000)
    fun `an unreadable newest key blocks writing until a new key is started`() {
        seedVersion1()
        val record = store.records.getValue(1)
        store.records[1] = record.withBytes(record.wrappedKey.copyOf().also { it[30] = (it[30].toInt() xor 1).toByte() })
        val holder = newHolder()

        repeat(2) {
            val e = failsWith<NewStorageKeyRequiredException> { holder.prepareWriteKey() }
            assertFalse(e.cause is KeyTemporarilyUnavailableException)
        }
        assertEquals(1, wrappers.unwraps.get())
        assertEquals(0, wrappers.wraps.get())
        assertEquals(0, keystore.generations.get())
        assertEquals(setOf(1), store.records.keys)

        assertEquals(2, runBlocking { holder.startNewKey() })
        assertEquals(setOf(1, 2), store.records.keys)
        assertEquals(2, runBlocking { holder.prepareWriteKey() })
    }

    @Test(timeout = 20_000)
    fun `the write status never creates a key`() {
        val holder = newHolder()
        assertEquals(WriteKeyStatus.NOT_CREATED, runBlocking { holder.writeKeyStatus() })
        assertTrue(store.records.isEmpty())
        assertEquals(0, keystore.generations.get())

        assertEquals(1, runBlocking { holder.prepareWriteKey() })
        resetCounters()
        assertEquals(WriteKeyStatus.READY, runBlocking { newHolder().writeKeyStatus() })
        assertEquals(1, wrappers.unwraps.get())
        assertEquals(0, wrappers.wraps.get())

        keystore.lookupFailure = { ProviderException("busy") }
        assertEquals(WriteKeyStatus.TEMPORARILY_UNAVAILABLE, runBlocking { newHolder().writeKeyStatus() })
        assertEquals(setOf(1), store.records.keys)
        assertEquals(0, keystore.generations.get())
    }

    @Test(timeout = 20_000)
    fun `readyKey makes no call and never unwraps`() {
        seedVersion1()
        val holder = newHolder()
        assertEquals(null, holder.readyKey(1))
        assertEquals(0, wrappers.unwraps.get())
        assertEquals(0, store.calls.get())

        val key = runBlocking { holder.key(1) }
        store.calls.set(0)
        assertSame(key, holder.readyKey(1))
        assertEquals(null, holder.readyKey(2))
        assertEquals(1, wrappers.unwraps.get())
        assertEquals(0, store.calls.get())
    }

    @Test(timeout = 20_000)
    fun `a Keystore error on the newest key makes writing fail with a retryable error and creates nothing`() {
        seedVersion1()
        val holder = newHolder()
        for (failure in listOf<() -> Exception>({ KeyStoreException("busy") }, { ProviderException("busy") })) {
            keystore.lookupFailure = failure
            failsWith<KeyTemporarilyUnavailableException> { holder.prepareWriteKey() }
        }
        keystore.invalidate(BASE)
        keystore.lookupFailure = null
        failsWith<KeyTemporarilyUnavailableException> { holder.prepareWriteKey() }
        // Never a new key because of a failure that may be transient, not even when asked.
        failsWith<KeyTemporarilyUnavailableException> { holder.startNewKey() }
        assertEquals(WriteKeyStatus.TEMPORARILY_UNAVAILABLE, runBlocking { holder.writeKeyStatus() })
        assertEquals(0, wrappers.wraps.get())
        assertEquals(0, keystore.generations.get())
        assertEquals(setOf(1), store.records.keys)

        // The error went away: the existing key is used, still nothing is created.
        keystore.revalidate(BASE)
        assertEquals(1, runBlocking { holder.prepareWriteKey() })
        assertEquals(0, wrappers.wraps.get())
        assertEquals(setOf(1), store.records.keys)
    }

    @Test(timeout = 20_000)
    fun `a Keystore error while making a new key is retryable and not cached`() {
        keystore.lookupFailure = { ProviderException("busy") }
        val holder = newHolder()
        failsWith<KeyTemporarilyUnavailableException> { holder.prepareWriteKey() }
        assertTrue(store.records.isEmpty())

        keystore.lookupFailure = null
        assertEquals(1, runBlocking { holder.prepareWriteKey() })
        assertEquals(setOf(1), store.records.keys)
    }

    @Test(timeout = 20_000)
    fun `concurrent first writers share one new key`() {
        val gate = CountDownLatch(1)
        wrappers.beforeUnwrap = { gate.await() }
        val holder = newHolder()

        val versions = runBlocking {
            val writers = List(8) { async(Dispatchers.Default) { holder.prepareWriteKey() } }
            awaitCondition { wrappers.unwraps.get() == 1 }
            Thread.sleep(200)
            gate.countDown()
            writers.awaitAll()
        }

        assertEquals(List(8) { 1 }, versions)
        assertEquals(1, wrappers.wraps.get())
        assertEquals(1, keystore.generations.get())
        assertEquals(setOf(1), store.records.keys)
    }

    @Test(timeout = 20_000)
    fun `a cancelled writer leaves its new key for the next writer`() {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        wrappers.beforeUnwrap = { entered.countDown(); gate.await() }
        val holder = newHolder()

        runBlocking {
            val writer = launch(Dispatchers.Default) { holder.prepareWriteKey() }
            entered.await()
            writer.cancelAndJoin()
            gate.countDown()
            assertEquals(1, holder.prepareWriteKey())
        }
        assertEquals(1, wrappers.wraps.get())
        assertEquals(1, wrappers.unwraps.get())
        assertEquals(setOf(1), store.records.keys)
    }

    @Test(timeout = 20_000)
    fun `a key whose record is gone is never used for writing`() {
        val holder = newHolder()
        assertEquals(1, runBlocking { holder.prepareWriteKey() })
        val old = runBlocking { holder.writeKey(1) }
        val oldBytes = old.bytes()

        // The transaction that stored the record rolled back.
        store.records.remove(1)
        failsWith<KeyUnavailableException> { holder.writeKey(1) }
        assertEquals(1, wrappers.wraps.get())

        // The next write makes and stores a new key for version 1; the stale one is wiped.
        assertEquals(1, runBlocking { holder.prepareWriteKey() })
        val fresh = runBlocking { holder.writeKey(1) }
        assertFalse(oldBytes.contentEquals(fresh.bytes()))
        assertTrue(old.isWiped)
        assertEquals(2, wrappers.wraps.get())
    }

    @Test(timeout = 20_000)
    fun `writeKey re-reads the record instead of trusting the cache`() {
        val holder = newHolder()
        runBlocking { holder.prepareWriteKey() }
        val record = store.records.getValue(1)
        store.records[1] = record.withBytes(record.wrappedKey.copyOf().also { it[30] = (it[30].toInt() xor 1).toByte() })

        failsWith<SecretCipherException> { holder.writeKey(1) }
    }

    @Test(timeout = 20_000)
    fun `a stored key is never overwritten`() {
        // Another writer stores its own version 1 between our check and our insert.
        val otherStore = FakeDataKeyStore()
        val other = newHolder(otherStore)
        runBlocking { other.prepareWriteKey() }
        val theirs = otherStore.records.getValue(1)
        store.beforeInsert = { store.records.putIfAbsent(1, theirs) }
        resetCounters()

        val holder = newHolder()
        assertEquals(1, runBlocking { holder.prepareWriteKey() })
        assertSame(theirs, store.records.getValue(1))
        assertArrayEquals(runBlocking { other.writeKey(1) }.bytes(), runBlocking { holder.writeKey(1) }.bytes())
    }

    @Test(timeout = 20_000)
    fun `a conflicting record that does not unwrap stops writing and stays`() {
        val conflicting = WrappedDataKey(1, BASE, ByteArray(62).also { it[0] = 1; it[1] = 12 }, 0L)
        store.beforeInsert = { store.records.putIfAbsent(1, conflicting) }
        val holder = newHolder()

        failsWith<SecretCipherException> { holder.prepareWriteKey() }
        assertSame(conflicting, store.records.getValue(1))
    }

    @Test(timeout = 20_000)
    fun `a failed creation is not cached and reuses the wrapping key it left`() {
        wrappers.failUnwrapsWith = { SecretCipherException("Decryption failed") }
        val holder = newHolder()
        failsWith<SecretCipherException> { holder.prepareWriteKey() }
        assertTrue(store.records.isEmpty())

        wrappers.failUnwrapsWith = null
        assertEquals(1, runBlocking { holder.prepareWriteKey() })
        assertEquals(2, wrappers.wraps.get())
        assertEquals(1, keystore.generations.get())
    }

    @Test(timeout = 20_000)
    fun `writing needs the record of its version and never creates one`() {
        val holder = newHolder()
        failsWith<KeyUnavailableException> { holder.writeKey(5) }
        assertEquals(0, wrappers.wraps.get())
        assertEquals(0, keystore.generations.get())
    }

    @Test(timeout = 20_000)
    fun `the version number cannot overflow`() {
        store.highestRowVersion = Int.MAX_VALUE
        failsWith<NewStorageKeyRequiredException> { newHolder().prepareWriteKey() }
        assertEquals("No data key version left", failsWith<SecretCipherException> { newHolder().startNewKey() }.message)
        assertEquals(0, wrappers.wraps.get())
    }

    @Test(timeout = 20_000)
    fun `a lost wrapping key gets a version above orphaned rows, never their number`() {
        seedVersion1()
        keystore.lose(BASE)
        // Rows name versions 2 to 5, whose records are gone (a restored database, for example).
        store.highestRowVersion = 5
        val holder = newHolder()

        failsWith<NewStorageKeyRequiredException> { holder.prepareWriteKey() }
        assertEquals(6, runBlocking { holder.startNewKey() })
        assertEquals(setOf(1, 6), store.records.keys)
        assertEquals("${BASE}_k6", store.records.getValue(6).kekAlias)
        assertFalse("${BASE}_k5" in keystore)
    }

    @Test(timeout = 20_000)
    fun `a writer that read the table before another writer's insert committed makes no second key`() {
        val random = RecordingRandom()
        val holder = newHolder(random = random)
        val bReadTheTable = CompletableDeferred<Unit>()
        val aDone = CompletableDeferred<Unit>()
        val bCalls = AtomicInteger()
        store.beforeCall = {
            // B's first call (the table read) has returned nothing; hold B before its second one
            // until A has created, stored and installed version 1.
            if (currentCoroutineContext()[CoroutineName]?.name == "B" && bCalls.incrementAndGet() == 2) {
                bReadTheTable.complete(Unit)
                aDone.await()
            }
        }

        val (a, b) = runBlocking {
            val b = async(Dispatchers.Default + CoroutineName("B")) { holder.prepareWriteKey() }
            bReadTheTable.await()
            val a = holder.prepareWriteKey()
            aDone.complete(Unit)
            a to b.await()
        }

        assertEquals(1, a)
        assertEquals(1, b)
        assertEquals(1, random.filled.size) // one data key generated
        assertEquals(1, wrappers.wraps.get())
        assertEquals(setOf(1), store.records.keys)
        store.beforeCall = {}
        assertSame(runBlocking { holder.key(1) }, runBlocking { holder.writeKey(1) })
    }

    @Test(timeout = 20_000)
    fun `a writer that read the table before an unreadable record appeared makes no key and reports it`() {
        val holder = newHolder()
        val bReadTheTable = CompletableDeferred<Unit>()
        val readerDone = CompletableDeferred<Unit>()
        val bCalls = AtomicInteger()
        store.beforeCall = {
            if (currentCoroutineContext()[CoroutineName]?.name == "B" && bCalls.incrementAndGet() == 2) {
                bReadTheTable.complete(Unit)
                readerDone.await()
            }
        }

        val failure = runBlocking {
            val b = async(Dispatchers.Default + CoroutineName("B")) { runCatching { holder.prepareWriteKey() } }
            bReadTheTable.await()
            // Another process stores version 1, whose record does not authenticate here; a reader
            // of this process caches that failure before B goes on.
            store.records[1] = WrappedDataKey(1, BASE, wellFormedGarbage(), NOW)
            keystore.provider(BASE).getOrCreateKey()
            failsWith<SecretCipherException> { holder.key(1) }
            readerDone.complete(Unit)
            b.await().exceptionOrNull()
        }

        assertTrue(failure.toString(), failure is SecretCipherException)
        assertFalse(failure is KeyUnavailableException || failure is KeyTemporarilyUnavailableException)
        assertEquals(0, wrappers.wraps.get())
        assertEquals(1, wrappers.unwraps.get())
        assertArrayEquals(wellFormedGarbage(), store.records.getValue(1).wrappedKey)
        assertEquals(setOf(1), store.records.keys)
    }

    @Test(timeout = 20_000)
    fun `a key whose creator was cancelled after its insert is adopted without another unwrap`() {
        val random = RecordingRandom()
        val holder = newHolder(random = random)
        val inserted = CompletableDeferred<Unit>()
        store.afterInsert = {
            inserted.complete(Unit)
            awaitCancellation()
        }
        runBlocking {
            val writer = launch(Dispatchers.Default) { holder.prepareWriteKey() }
            inserted.await()
            writer.cancelAndJoin()
        }
        store.afterInsert = {}
        assertEquals(setOf(1), store.records.keys)
        assertEquals(1, wrappers.unwraps.get()) // the check of the new key

        val key = runBlocking { holder.key(1) }
        val made = random.filled.single()
        assertTrue(key.use { it === made }!!) // the very key that was made, not an unwrapped copy
        assertEquals(1, wrappers.unwraps.get())
        assertSame(key, runBlocking { holder.writeKey(1) })
        assertEquals(1, runBlocking { holder.prepareWriteKey() })
        assertEquals(1, wrappers.wraps.get())
    }

    @Test(timeout = 20_000)
    fun `a stored new key whose copy was wiped yields to the slot unwrapped from its record meanwhile`() {
        val random = RecordingRandom()
        val holder = newHolder(random = random)
        val inserted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        store.afterInsert = {
            inserted.complete(Unit)
            release.await()
        }

        runBlocking {
            val writer = async(Dispatchers.Default) { holder.prepareWriteKey() }
            inserted.await()
            holder.wipe() // zeroes the stored, not yet installed new key
            assertTrue(random.filled.single().all { it == 0.toByte() })
            val reader = holder.key(1) // unwraps the stored record
            assertEquals(2, wrappers.unwraps.get())

            store.afterInsert = {}
            release.complete(Unit)
            assertEquals(1, writer.await())

            // The writer's install kept the reader's slot and installed nothing over it.
            assertSame(reader, holder.key(1))
            assertSame(reader, holder.writeKey(1))
            assertFalse(reader.isWiped)
        }
        assertEquals(2, wrappers.unwraps.get())
    }

    @Test(timeout = 20_000)
    fun `wipe during a creation leaves no unwiped key`() {
        val random = RecordingRandom()
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        wrappers.beforeUnwrap = {
            if (wrappers.unwraps.get() == 1) {
                entered.countDown()
                gate.await()
            }
        }
        val holder = newHolder(random = random)

        val version = runBlocking {
            val writer = async(Dispatchers.Default) { holder.prepareWriteKey() }
            entered.await() // the new key is made and wrapped; its check is waiting
            holder.wipe()
            gate.countDown()
            writer.await()
        }

        assertEquals(1, version)
        assertTrue(random.filled.single().all { it == 0.toByte() })
        // The stored record is intact: the next use unwraps it.
        val key = runBlocking { holder.key(1) }
        assertFalse(key.isWiped)
        assertEquals(2, wrappers.unwraps.get())
        assertArrayEquals(runBlocking { newHolder().key(1) }.bytes(), key.bytes())
    }

    @Test(timeout = 20_000)
    fun `wipe from inside a key's use still wipes every other key and pending new key, and keeps that one`() {
        val v1 = seedRecord(1)
        val v2 = seedRecord(2)
        seedRecord(3)
        keystore.lose("${BASE}_k3")
        val random = RecordingRandom()
        val holder = newHolder(random = random)
        val k1 = runBlocking { holder.key(1) }
        val k2 = runBlocking { holder.key(2) }
        assertArrayEquals(v1, k1.bytes())
        assertArrayEquals(v2, k2.bytes())

        // A writer makes version 4 (version 3's wrapping key is gone) and is held after its insert.
        val inserted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        store.afterInsert = {
            inserted.complete(Unit)
            release.await()
        }
        runBlocking {
            val writer = async(Dispatchers.Default) { holder.startNewKey() }
            inserted.await()

            val refused = k1.use { assertThrows(IllegalStateException::class.java) { holder.wipe() } }
            assertTrue(refused is IllegalStateException)
            assertTrue(k2.isWiped)
            assertTrue(random.filled.single().all { it == 0.toByte() }) // the pending version 4 key
            assertFalse(k1.isWiped)
            // The key in use is kept, so it is not lost: it is still served and a later wipe zeroes it.
            assertSame(k1, holder.key(1))

            store.afterInsert = {}
            release.complete(Unit)
            assertEquals(4, writer.await())
        }
        holder.wipe()
        assertTrue(k1.isWiped)
    }

    @Test(timeout = 20_000)
    fun `a read gives up after three unwraps that were each wiped under it`() {
        val seeded = seedVersion1()
        lateinit var holder: DataKeyHolder
        wrappers.beforeUnwrap = { holder.wipe() }
        holder = newHolder()

        val e = failsWith<SecretCipherException> { holder.key(1) }
        assertEquals("Data key unavailable", e.message)
        assertFalse(e is KeyUnavailableException || e is KeyTemporarilyUnavailableException)
        assertEquals(3, wrappers.unwraps.get())

        // Not cached.
        wrappers.beforeUnwrap = {}
        assertArrayEquals(seeded, runBlocking { holder.key(1) }.bytes())
    }

    @Test(timeout = 20_000)
    fun `writeKey never unwraps, it only hands out a key that is ready`() {
        val seeded = seedVersion1()
        val holder = newHolder()

        // Not unwrapped in this process yet: a retryable failure, and no Keystore call.
        failsWith<KeyTemporarilyUnavailableException> { holder.writeKey(1) }
        assertEquals(0, wrappers.unwraps.get())

        val ready = runBlocking { holder.key(1) }
        assertSame(ready, runBlocking { holder.writeKey(1) })
        assertArrayEquals(seeded, ready.bytes())
        assertEquals(1, wrappers.unwraps.get())

        // A wiped key is never handed out either.
        holder.wipe()
        failsWith<KeyTemporarilyUnavailableException> { holder.writeKey(1) }
        assertEquals(1, wrappers.unwraps.get())
    }

    @Test(timeout = 20_000)
    fun `startNewKey gives up after three versions that each lost a race`() {
        // Every insert finds a record of its version already there, whose wrapping key is then gone,
        // so each attempt moves on to the next version.
        store.beforeInsert = { ours ->
            store.records.putIfAbsent(ours.version, WrappedDataKey(ours.version, ours.kekAlias, wellFormedGarbage(), 0L))
            keystore.lose(ours.kekAlias)
        }
        val holder = newHolder()

        val e = failsWith<SecretCipherException> { holder.startNewKey() }
        assertEquals("Data key unavailable", e.message)
        assertEquals(3, wrappers.wraps.get())
        assertEquals(setOf(1, 2, 3), store.records.keys)
        // None of ours was stored; the records that were there are unchanged.
        store.records.values.forEach { assertArrayEquals(wellFormedGarbage(), it.wrappedKey) }
        // The ordinary write path never moves on to another version by itself.
        failsWith<NewStorageKeyRequiredException> { holder.prepareWriteKey() }
        assertEquals(3, wrappers.wraps.get())
    }

    @Test(timeout = 20_000)
    fun `the first write after a lost race with an unusable record refuses instead of making version 2`() {
        store.beforeInsert = { ours ->
            store.records.putIfAbsent(ours.version, WrappedDataKey(ours.version, ours.kekAlias, wellFormedGarbage(), 0L))
            keystore.lose(ours.kekAlias)
        }
        failsWith<NewStorageKeyRequiredException> { newHolder().prepareWriteKey() }
        assertEquals(1, wrappers.wraps.get())
        assertEquals(setOf(1), store.records.keys)
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private fun newHolder(dataKeys: FakeDataKeyStore = store, random: SecureRandom = SecureRandom()) = DataKeyHolder(
        store = dataKeys,
        wrapperFor = wrappers::wrapperFor,
        aliasBase = BASE,
        random = random,
        keystoreDispatcher = Dispatchers.IO,
        clock = { NOW },
    )

    /** Stores a record of [version] wrapped under its derived alias, as an earlier process would; returns the key. */
    private fun seedRecord(version: Int): ByteArray {
        val alias = EnvelopeFormat.wrapAlias(BASE, version)
        val raw = ByteArray(32).also(SecureRandom()::nextBytes)
        val wrapped = wrappers.wrapperFor(alias).wrap(raw, EnvelopeFormat.wrapAssociatedData(version, alias))
        store.records[version] = WrappedDataKey(version, alias, wrapped, NOW)
        resetCounters()
        return raw
    }

    /** 62 bytes with the header of a wrapped key, which no wrapping key authenticates. */
    private fun wellFormedGarbage() = ByteArray(62).also { it[0] = 1; it[1] = 12 }

    /** Stores data key version 1 as an earlier process would have; returns its bytes. */
    private fun seedVersion1(): ByteArray {
        val bytes = runBlocking {
            val earlier = newHolder()
            check(earlier.prepareWriteKey() == 1)
            earlier.writeKey(1).bytes()
        }
        resetCounters()
        return bytes
    }

    private fun resetCounters() {
        wrappers.wraps.set(0)
        wrappers.unwraps.set(0)
        store.calls.set(0)
        keystore.generations.set(0)
    }

    private fun DataKey.bytes(): ByteArray = use { it.copyOf() } ?: error("wiped")

    private fun WrappedDataKey.withBytes(bytes: ByteArray) = WrappedDataKey(version, kekAlias, bytes, createdAtMillis)

    private inline fun <reified T : Throwable> failsWith(noinline block: suspend () -> Unit): T =
        assertThrows(T::class.java) { runBlocking { block() } }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Condition never became true" }
            Thread.sleep(1)
        }
    }

    private fun awaitBlocked(t: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (t.state != Thread.State.WAITING && t.state != Thread.State.TIMED_WAITING) {
            check(System.nanoTime() < deadline) { "Thread never blocked" }
            Thread.onSpinWait()
        }
    }

    private companion object {
        const val BASE = "test_wrap_key"
        const val NOW = 1_700_000_000_000L
    }
}
