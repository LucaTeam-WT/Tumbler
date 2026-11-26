package io.github.lucateam_wt.tumbler.data.crypto

import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * One data key in memory: [version] and its 32 bytes, which this object owns (they are not
 * copied). Every operation runs inside [use], under the read side of a lock; [wipe] takes the
 * write side, so it waits for running operations and never zeroes a key that is in use.
 *
 * Kotlin's `ReentrantReadWriteLock.write` releases the calling thread's read locks first, which
 * would let a wipe from inside [use] zero the key under its own operation; this class takes the
 * write lock directly and refuses that case instead.
 *
 * Copies made by the JCA (`SecretKeySpec`, the cipher's key schedule) cannot be wiped.
 * [toString] prints no key material.
 */
class DataKey internal constructor(val version: Int, bytes: ByteArray) {

    private val key: ByteArray = bytes
    private val lock = ReentrantReadWriteLock()

    @Volatile
    private var wiped = false

    init {
        require(version >= EnvelopeFormat.FIRST_DATA_KEY_VERSION) { "Data key versions start at 1" }
        require(bytes.size == EnvelopeFormat.DATA_KEY_BYTES) { "A data key has 32 bytes" }
    }

    /** True once [wipe] has run; the key can then no longer be used. */
    val isWiped: Boolean get() = wiped

    /**
     * Runs [block] with the raw key and returns its result, or returns `null` without running it
     * if the key was wiped. [block] must not keep the array or call [wipe].
     */
    internal fun <R : Any> use(block: (ByteArray) -> R): R? {
        val read = lock.readLock()
        read.lock()
        try {
            return if (wiped) null else block(key)
        } finally {
            read.unlock()
        }
    }

    /**
     * Fills the key with zeros once no operation uses it. Later [use] calls return `null`.
     *
     * @throws IllegalStateException if called from inside [use] on the same thread.
     */
    internal fun wipe() {
        check(lock.readHoldCount == 0) { "A data key cannot be wiped from inside its own use" }
        val write = lock.writeLock()
        write.lock()
        try {
            key.fill(0)
            wiped = true
        } finally {
            write.unlock()
        }
    }

    override fun toString(): String = "DataKey(version=$version, <redacted>)"
}
