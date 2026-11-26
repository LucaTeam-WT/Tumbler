package io.github.lucateam_wt.tumbler.data.crypto

import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * An in-memory Keystore: one AES-256 key per alias. A key can be lost (its entry deleted, as after
 * a Keystore failure) or invalidated (it still exists, but every operation with it fails).
 */
class FakeKeystore {

    private val keys = ConcurrentHashMap<String, SecretKey>()
    private val invalidated: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Keys generated so far, over every alias. */
    val generations = AtomicInteger()

    /**
     * If set, every key lookup throws what it returns, as the Keystore does on a transient error
     * (a busy keystore service or secure element): a [java.security.KeyStoreException], a
     * [java.security.ProviderException] or an [java.io.IOException].
     */
    @Volatile
    var lookupFailure: (() -> Exception)? = null

    operator fun contains(alias: String): Boolean = keys.containsKey(alias)

    fun lose(alias: String) {
        keys.remove(alias)
    }

    fun invalidate(alias: String) {
        invalidated += alias
    }

    /** Undoes [invalidate]: the key works again, as after a Keystore error that went away. */
    fun revalidate(alias: String) {
        invalidated -= alias
    }

    /** Generates a key under [alias] (replacing none: the alias must be empty). */
    @Synchronized
    fun generate(alias: String): SecretKey {
        check(!keys.containsKey(alias)) { "The fake Keystore never replaces a key" }
        generations.incrementAndGet()
        return KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { keys[alias] = it }
    }

    fun provider(alias: String): KeyProvider = object : KeyProvider {
        override fun getOrCreateKey(): SecretKey = synchronized(this@FakeKeystore) { existing(alias) ?: generate(alias) }

        override fun getExistingKey(): SecretKey? = existing(alias)
    }

    private fun existing(alias: String): SecretKey? {
        lookupFailure?.let { throw it() }
        val key = keys[alias] ?: return null
        // A key of another algorithm makes every AES operation fail, as an invalidated Keystore key does.
        return if (alias in invalidated) SecretKeySpec(key.encoded, "HmacSHA256") else key
    }
}

/**
 * Real AES-GCM wrappers over a [FakeKeystore] (the same payload as the app's Keystore wrapper) that
 * count every wrap and unwrap over all aliases. [beforeUnwrap] runs on the unwrapping thread before
 * each unwrap (a gate, for a slow StrongBox); [failUnwrapsWith] makes each unwrap throw.
 */
class CountingWrappers(private val keystore: FakeKeystore) {

    val wraps = AtomicInteger()
    val unwraps = AtomicInteger()
    val aliases: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile
    var beforeUnwrap: () -> Unit = {}

    @Volatile
    var failUnwrapsWith: (() -> Throwable)? = null

    fun wrapperFor(alias: String): DataKeyWrapper {
        aliases += alias
        val delegate = AesGcmDataKeyWrapper(AesGcmSecretCipher(keystore.provider(alias)))
        return object : DataKeyWrapper {
            override fun wrap(dataKey: ByteArray, associatedData: ByteArray): ByteArray {
                wraps.incrementAndGet()
                return delegate.wrap(dataKey, associatedData)
            }

            override fun unwrap(wrapped: ByteArray, associatedData: ByteArray): ByteArray {
                unwraps.incrementAndGet()
                beforeUnwrap()
                failUnwrapsWith?.let { throw it() }
                return delegate.unwrap(wrapped, associatedData)
            }
        }
    }
}

/**
 * A [SecureRandom] that remembers every array it filled, so a test can check that a data key the
 * holder generated was wiped (its 32 bytes are zero) without the holder exposing it.
 */
class RecordingRandom : SecureRandom() {

    private val arrays: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())

    /** The arrays filled so far, in order (the same objects, not copies). */
    val filled: List<ByteArray> get() = synchronized(arrays) { arrays.toList() }

    override fun nextBytes(bytes: ByteArray) {
        super.nextBytes(bytes)
        arrays += bytes
    }
}

/**
 * The `data_keys` table in memory, plus the highest `keyVersion` of the account rows. Like the
 * Room DAO, [insertIfAbsent] never overwrites. [beforeCall] runs in the calling coroutine before
 * every call; [beforeInsert] runs before each insert.
 */
class FakeDataKeyStore : DataKeyStore {

    val records = ConcurrentHashMap<Int, WrappedDataKey>()
    val calls = AtomicInteger()

    @Volatile
    var highestRowVersion = 0

    @Volatile
    var beforeCall: suspend () -> Unit = {}

    @Volatile
    var beforeInsert: (WrappedDataKey) -> Unit = {}

    /**
     * Runs in the calling coroutine after each insert was applied, before the call returns: a
     * caller cancelled here has stored its record (as with a Room call that commits and then
     * resumes into a cancelled caller).
     */
    @Volatile
    var afterInsert: suspend (WrappedDataKey) -> Unit = {}

    override suspend fun get(version: Int): WrappedDataKey? {
        count()
        return records[version]
    }

    override suspend fun all(): List<WrappedDataKey> {
        count()
        return records.values.toList()
    }

    override suspend fun insertIfAbsent(key: WrappedDataKey): Boolean {
        count()
        beforeInsert(key)
        val inserted = records.putIfAbsent(key.version, key) == null
        afterInsert(key)
        return inserted
    }

    override suspend fun highestAccountKeyVersion(): Int {
        count()
        return highestRowVersion
    }

    private suspend fun count() {
        calls.incrementAndGet()
        beforeCall()
    }
}
