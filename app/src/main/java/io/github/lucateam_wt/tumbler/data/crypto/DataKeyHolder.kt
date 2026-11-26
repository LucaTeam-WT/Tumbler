package io.github.lucateam_wt.tumbler.data.crypto

import io.github.lucateam_wt.tumbler.domain.crypto.KeyUnavailableException
import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipherException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The data keys of this process (`docs/storage-format.md`, "Key states"). One instance per
 * process, shared by every reader and writer, so each data key is unwrapped at most once.
 *
 * **Reading** ([key]) never creates a key. The first caller for a version reads its record from
 * [store] in its own coroutine, then waits for one shared unwrap that runs in this holder's scope.
 * Concurrent first callers share it, and a caller that is cancelled while it waits does not abort
 * it: the key is kept for the next caller. A failed unwrap (the wrapping key is gone, the record is
 * malformed or does not authenticate) is cached and reported to every later caller without
 * another Keystore call, until [clearFailures]. Other failures (running out of memory, for
 * example) are not cached.
 *
 * **Writing** has two steps. [prepareWriteKey], outside any database transaction, returns the
 * version to seal with and creates a data key when there is none, or when the newest one's
 * wrapping key is gone. It never creates one while the newest key exists but does not unwrap.
 * [writeKey], inside the transaction that writes the rows, re-reads that version's record in that
 * transaction and refuses to seal if it is not there, so a row is never committed without the
 * record of its key.
 *
 * **Deadlock freedom:** the holder's own scope does Keystore work only, never database work, and
 * no lock of the holder is held across a [store] call. So a caller that holds a write transaction
 * (on a device without WAL, the only connection) can wait for an unwrap without blocking it.
 *
 * **Never overwritten:** a new key always gets a version above every stored record and every
 * account row, and its own Keystore alias ([EnvelopeFormat.wrapAlias]); it is stored with
 * [DataKeyStore.insertIfAbsent]. When a different record already holds that version, ours is
 * wiped and the stored one is used.
 *
 * [wipe] zeroes every key once no operation uses it; the next use unwraps again.
 *
 * @param wrapperFor the wrapper for a Keystore alias.
 * @param aliasBase the alias of version 1; later versions add `_k<version>` (tests use their own).
 * @param keystoreDispatcher where unwraps and new keys run (they block on the Keystore).
 */
class DataKeyHolder(
    private val store: DataKeyStore,
    private val wrapperFor: (alias: String) -> DataKeyWrapper,
    private val aliasBase: String = EnvelopeFormat.WRAP_KEY_ALIAS,
    private val random: SecureRandom = SecureRandom(),
    keystoreDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val scope = CoroutineScope(SupervisorJob() + keystoreDispatcher)

    private val lock = Any()

    /** Unwrapped (or unwrapping, or failed) keys by version. Guarded by [lock]. */
    private val unwrapped = HashMap<Int, Slot>()

    /**
     * New keys being made, or made but not yet stored, by version. Guarded by [lock].
     *
     * Entries of both maps are only ever removed, never put back, so work that ends after its
     * entry was removed (by [wipe] or a newer record) sees that by identity and wipes its result.
     */
    private val created = HashMap<Int, Pending>()

    private class Slot(val record: WrappedDataKey) {
        lateinit var deferred: Deferred<DataKey>

        /** The key once unwrapped while this slot was current; written under [lock]. */
        @Volatile
        var key: DataKey? = null

        /** The unwrap failed with a cipher error, which stays cached. */
        @Volatile
        var failed = false
    }

    private class Creation(val record: WrappedDataKey, val key: DataKey)

    private class Pending(val version: Int) {
        lateinit var deferred: Deferred<Creation>

        /** The new key while it is still pending; written under [lock]. */
        @Volatile
        var result: Creation? = null
    }

    /**
     * The data key [version], for reading. Never creates a key. After the first successful call it
     * costs no database or Keystore call.
     *
     * @throws KeyUnavailableException if there is no record of [version] or its wrapping key is gone.
     * @throws SecretCipherException if [version] is below 1, or its record is malformed or does not
     *   unwrap.
     */
    suspend fun key(version: Int): DataKey {
        if (version < EnvelopeFormat.FIRST_DATA_KEY_VERSION) throw SecretCipherException("Unsupported key version")
        repeat(MAX_ATTEMPTS) {
            val slot = synchronized(lock) { unwrapped[version] } ?: run {
                val record = store.get(version) ?: throw KeyUnavailableException()
                if (record.version != version) throw SecretCipherException("Invalid wrapped data key")
                slotFor(record)
            }
            val key = await(slot)
            if (!key.isWiped) return key
            forget(slot)
        }
        throw SecretCipherException("Data key unavailable")
    }

    /**
     * Write path, step 1: the version to seal new secrets with. Call it **outside** any database
     * transaction: a key created inside one that rolls back would leave no record.
     *
     * - No record: creates version `highest account keyVersion + 1` (1 on a new install).
     * - The newest record unwraps: returns its version.
     * - The newest record's wrapping key is gone: creates the next version under its own alias; the
     *   old record stays and its rows stay unreadable.
     * - The newest record exists but does not unwrap: throws, and creates nothing.
     *
     * @throws SecretCipherException if no key can be used or made.
     */
    suspend fun prepareWriteKey(): Int {
        repeat(MAX_ATTEMPTS) {
            val newest = store.all().maxByOrNull { it.version }
            val highestUsed = if (newest == null) {
                store.highestAccountKeyVersion()
            } else {
                try {
                    keyFor(newest)
                    return newest.version
                } catch (_: KeyUnavailableException) {
                    // Its wrapping key is gone: make the next version, keeping this record.
                }
                maxOf(newest.version, store.highestAccountKeyVersion())
            }
            if (highestUsed == Int.MAX_VALUE) throw SecretCipherException("No data key version left")
            val target = highestUsed.coerceAtLeast(EnvelopeFormat.LEGACY_KEY_VERSION) + 1
            if (storeNewKey(target)) return target
        }
        throw SecretCipherException("Data key unavailable")
    }

    /**
     * Write path, step 2: the key to seal with [version], called **inside** the transaction that
     * writes the rows. It reads [version]'s record in that transaction and uses a cached key only
     * if it came from that same record. Never creates a key.
     *
     * @throws KeyUnavailableException if the transaction cannot see a record of [version].
     * @throws SecretCipherException if the record does not unwrap.
     */
    suspend fun writeKey(version: Int): DataKey {
        if (version < EnvelopeFormat.FIRST_DATA_KEY_VERSION) throw SecretCipherException("Unsupported key version")
        val record = store.get(version) ?: throw KeyUnavailableException()
        if (record.version != version) throw SecretCipherException("Invalid wrapped data key")
        return keyFor(record)
    }

    /** Forgets cached unwrap failures, so the next call for those versions tries again. */
    fun clearFailures() {
        synchronized(lock) { unwrapped.values.removeAll { it.failed } }
    }

    /**
     * Zeroes every data key this holder has, each once no operation uses it, and forgets them; work
     * still running wipes its result when it ends. The next use unwraps again. Do not call it from
     * inside an operation that uses a key.
     */
    fun wipe() {
        val slots: List<Slot>
        val pending: List<Pending>
        synchronized(lock) {
            slots = unwrapped.values.toList()
            unwrapped.clear()
            pending = created.values.toList()
            created.clear()
        }
        slots.forEach(::release)
        pending.forEach { it.result?.key?.wipe() }
    }

    private suspend fun keyFor(record: WrappedDataKey): DataKey {
        repeat(MAX_ATTEMPTS) {
            val slot = slotFor(record)
            val key = await(slot)
            if (!key.isWiped) return key
            forget(slot)
        }
        throw SecretCipherException("Data key unavailable")
    }

    /** The slot for [record]: the current one if it came from the same record, otherwise a new unwrap. */
    private fun slotFor(record: WrappedDataKey): Slot {
        val slot: Slot
        val replaced: Slot?
        synchronized(lock) {
            val existing = unwrapped[record.version]
            if (existing != null && existing.record.sameKeyAs(record)) return existing
            val adopted = created[record.version]?.result?.takeIf { it.record.sameKeyAs(record) }
            slot = Slot(record)
            if (adopted != null) {
                // Made and stored by this process but not installed yet (its caller was cancelled).
                created.remove(record.version)
                slot.deferred = CompletableDeferred(adopted.key)
                slot.key = adopted.key
            } else {
                slot.deferred = scope.async(start = CoroutineStart.LAZY) { unwrap(slot) }
            }
            replaced = existing
            unwrapped[record.version] = slot
        }
        replaced?.let(::release)
        slot.deferred.start()
        return slot
    }

    /** Runs in [scope]: Keystore work only, no database access. */
    private fun unwrap(slot: Slot): DataKey {
        val record = slot.record
        try {
            if (!isWellFormed(record)) throw SecretCipherException("Invalid wrapped data key")
            val raw = wrapperFor(record.kekAlias)
                .unwrap(record.wrappedKey, EnvelopeFormat.wrapAssociatedData(record.version, record.kekAlias))
            if (raw.size != EnvelopeFormat.DATA_KEY_BYTES) {
                raw.fill(0)
                throw SecretCipherException("Invalid data key")
            }
            val key = DataKey(record.version, raw)
            val current = synchronized(lock) {
                (unwrapped[record.version] === slot).also { if (it) slot.key = key }
            }
            // Wiped or replaced while unwrapping: never kept. Callers see a wiped key and look again.
            if (!current) key.wipe()
            return key
        } catch (e: SecretCipherException) {
            slot.failed = true
            throw e
        } catch (t: Throwable) {
            // Not a property of the stored key (for example out of memory): not cached.
            forget(slot)
            throw t
        }
    }

    /** Makes (once, shared by concurrent callers) and stores a new key for [version]; false if another key holds it. */
    private suspend fun storeNewKey(version: Int): Boolean {
        val pending = synchronized(lock) {
            created[version] ?: Pending(version).also { made ->
                made.deferred = scope.async(start = CoroutineStart.LAZY) { create(made) }
                created[version] = made
            }
        }
        pending.deferred.start()
        val creation = pending.deferred.await()
        val stored = if (store.insertIfAbsent(creation.record)) creation.record else store.get(version)
        if (stored != null && stored.sameKeyAs(creation.record)) {
            install(creation)
            return true
        }
        // Another key holds this version, or the record cannot be read back: ours was never stored.
        synchronized(lock) { if (created[version]?.result === creation) created.remove(version) }
        creation.key.wipe()
        return false
    }

    /**
     * Runs in [scope]: Keystore work only. Generates the key, wraps it (creating the wrapping key if
     * its alias has none, for example after an interrupted first generation that left one), and
     * checks that the wrapped key unwraps to the same bytes before anything is stored.
     */
    private fun create(pending: Pending): Creation {
        val version = pending.version
        val alias = EnvelopeFormat.wrapAlias(aliasBase, version)
        val associatedData = EnvelopeFormat.wrapAssociatedData(version, alias)
        val raw = ByteArray(EnvelopeFormat.DATA_KEY_BYTES).also(random::nextBytes)
        var owned = true
        try {
            val wrapper = wrapperFor(alias)
            val wrapped = wrapper.wrap(raw, associatedData)
            if (!EnvelopeFormat.isWellFormedWrappedKey(wrapped)) throw SecretCipherException("Invalid wrapped data key")
            val check = wrapper.unwrap(wrapped, associatedData)
            try {
                if (!MessageDigest.isEqual(check, raw)) throw SecretCipherException("Data key check failed")
            } finally {
                check.fill(0)
            }
            val creation = Creation(WrappedDataKey(version, alias, wrapped, clock()), DataKey(version, raw))
            owned = false
            val current = synchronized(lock) {
                (created[version] === pending).also { if (it) pending.result = creation }
            }
            if (!current) creation.key.wipe()
            return creation
        } catch (t: Throwable) {
            // A failed creation is not cached: the next write tries again.
            synchronized(lock) { if (created[version] === pending) created.remove(version) }
            throw t
        } finally {
            if (owned) raw.fill(0)
        }
    }

    /** Makes a stored new key the current one for its version. */
    private fun install(creation: Creation) {
        val version = creation.record.version
        var superfluous: DataKey? = null
        var replaced: Slot? = null
        synchronized(lock) {
            if (created[version]?.result === creation) created.remove(version)
            val existing = unwrapped[version]
            when {
                // A concurrent caller sharing this creation installed it already.
                existing != null && existing.key === creation.key -> Unit
                // The same record was unwrapped meanwhile: keep that slot, drop this copy.
                existing != null && !existing.failed && existing.record.sameKeyAs(creation.record) ->
                    superfluous = creation.key
                // wipe() ran meanwhile: nothing to install; the next use unwraps the record.
                creation.key.isWiped -> Unit
                else -> {
                    val slot = Slot(creation.record)
                    slot.deferred = CompletableDeferred(creation.key)
                    slot.key = creation.key
                    unwrapped[version] = slot
                    replaced = existing
                }
            }
        }
        superfluous?.wipe()
        replaced?.let(::release)
    }

    private fun isWellFormed(record: WrappedDataKey): Boolean =
        record.version >= EnvelopeFormat.FIRST_DATA_KEY_VERSION &&
            record.kekAlias == EnvelopeFormat.wrapAlias(aliasBase, record.version) &&
            EnvelopeFormat.isWellFormedWrappedKey(record.wrappedKey)

    private suspend fun await(slot: Slot): DataKey = try {
        slot.deferred.await()
    } catch (_: KeyUnavailableException) {
        throw KeyUnavailableException()
    } catch (e: SecretCipherException) {
        throw SecretCipherException("Data key unavailable", e)
    }

    /** Drops [slot] if it is still current, and wipes its key. */
    private fun forget(slot: Slot) {
        synchronized(lock) {
            if (unwrapped[slot.record.version] === slot) unwrapped.remove(slot.record.version)
        }
        release(slot)
    }

    /** Wipes [slot]'s key if it has one; a slot still unwrapping wipes its own result (it is no longer current). */
    private fun release(slot: Slot) {
        slot.key?.wipe()
    }

    private companion object {
        /** Retries after a key was wiped or replaced under the caller, or a creation lost a race. */
        const val MAX_ATTEMPTS = 3
    }
}
