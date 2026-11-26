package io.github.lucateam_wt.tumbler.data.crypto

import io.github.lucateam_wt.tumbler.domain.crypto.KeyUnavailableException
import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipherException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
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
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    fun `state unreadable - an invalidated wrapping key fails and is cached`() {
        seedVersion1()
        keystore.invalidate(BASE)
        val holder = newHolder()

        val first = failsWith<SecretCipherException> { holder.key(1) }
        val second = failsWith<SecretCipherException> { holder.key(1) }
        assertFalse(first is KeyUnavailableException)
        assertFalse(second is KeyUnavailableException)
        assertEquals(1, wrappers.unwraps.get())
        assertEquals(0, keystore.generations.get())
    }

    @Test(timeout = 20_000)
    fun `state unreadable - a record that does not authenticate fails and is cached`() {
        seedVersion1()
        val record = store.records.getValue(1)
        store.records[1] = record.withBytes(record.wrappedKey.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() })
        val holder = newHolder()

        assertFalse(failsWith<SecretCipherException> { holder.key(1) } is KeyUnavailableException)
        failsWith<SecretCipherException> { holder.key(1) }
        assertEquals(1, wrappers.unwraps.get())
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
        wrappers.failUnwrapsWith = { SecretCipherException("Decryption failed") }
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
    fun `a new version is above every account row`() {
        store.highestRowVersion = 3
        assertEquals(4, runBlocking { newHolder().prepareWriteKey() })
        assertEquals(setOf(4), store.records.keys)
        assertEquals("${BASE}_k4", store.records.getValue(4).kekAlias)
    }

    @Test(timeout = 20_000)
    fun `a lost wrapping key gets a new version and keeps the old record`() {
        seedVersion1()
        val old = store.records.getValue(1)
        val oldBytes = old.wrappedKey.copyOf()
        keystore.lose(BASE)
        val holder = newHolder()

        assertEquals(2, runBlocking { holder.prepareWriteKey() })
        assertSame(old, store.records.getValue(1))
        assertArrayEquals(oldBytes, store.records.getValue(1).wrappedKey)
        assertEquals("${BASE}_k2", store.records.getValue(2).kekAlias)
        assertEquals(1, keystore.generations.get())
        assertFalse(BASE in keystore)
        // Version 1 stays unreadable; nothing replaced it.
        failsWith<KeyUnavailableException> { holder.key(1) }
    }

    @Test(timeout = 20_000)
    fun `an unreadable newest key blocks writing and creates nothing`() {
        seedVersion1()
        keystore.invalidate(BASE)
        val holder = newHolder()

        val e = failsWith<SecretCipherException> { holder.prepareWriteKey() }
        assertFalse(e is KeyUnavailableException)
        assertEquals(0, wrappers.wraps.get())
        assertEquals(0, keystore.generations.get())
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
        failsWith<SecretCipherException> { newHolder().prepareWriteKey() }
        assertEquals(0, wrappers.wraps.get())
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private fun newHolder(dataKeys: FakeDataKeyStore = store) = DataKeyHolder(
        store = dataKeys,
        wrapperFor = wrappers::wrapperFor,
        aliasBase = BASE,
        random = SecureRandom(),
        keystoreDispatcher = Dispatchers.IO,
        clock = { NOW },
    )

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
