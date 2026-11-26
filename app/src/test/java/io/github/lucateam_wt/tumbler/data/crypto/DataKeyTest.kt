package io.github.lucateam_wt.tumbler.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class DataKeyTest {

    @Test(timeout = 10_000)
    fun `wipe waits for an operation that uses the key`() {
        val original = ByteArray(32) { (it + 1).toByte() }
        val key = DataKey(1, original.copyOf())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var seenAfterWipeRequested: ByteArray? = null

        val user = thread {
            key.use { bytes ->
                entered.countDown()
                release.await()
                seenAfterWipeRequested = bytes.copyOf()
                Unit
            }
        }
        entered.await()
        val wiper = thread { key.wipe() }
        awaitBlocked(wiper)
        assertFalse(key.isWiped)
        release.countDown()
        user.join()
        wiper.join()

        // The operation saw the whole key although wipe() was called while it ran.
        assertArrayEquals(original, seenAfterWipeRequested)
        assertTrue(key.isWiped)
        assertNull(key.use { it.copyOf() })
    }

    @Test
    fun `wipe zeroes the key bytes`() {
        val bytes = ByteArray(32) { 7 }
        val key = DataKey(1, bytes)
        key.wipe()
        assertTrue(bytes.all { it == 0.toByte() })
        // Wiping twice is harmless.
        key.wipe()
    }

    @Test
    fun `wipe from inside the key's own use is refused and leaves the key intact`() {
        val key = DataKey(1, ByteArray(32) { 3 })
        assertThrows(IllegalStateException::class.java) { key.use { key.wipe() } }
        assertFalse(key.isWiped)
        assertEquals(3.toByte(), key.use { it[0] })
    }

    @Test
    fun `toString prints no key material`() {
        assertEquals("DataKey(version=4, <redacted>)", DataKey(4, ByteArray(32) { 0x41 }).toString())
        assertEquals(
            "WrappedDataKey(version=2, <redacted>)",
            WrappedDataKey(2, "alias", ByteArray(62) { 0x41 }, 0L).toString(),
        )
    }

    private fun awaitBlocked(t: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (t.state != Thread.State.WAITING && t.state != Thread.State.TIMED_WAITING) {
            check(System.nanoTime() < deadline) { "Thread never blocked" }
            Thread.onSpinWait()
        }
    }
}
