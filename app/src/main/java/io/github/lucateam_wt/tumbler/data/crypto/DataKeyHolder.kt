package io.github.lucateam_wt.tumbler.data.crypto

import io.github.lucateam_wt.tumbler.domain.crypto.KeyTemporarilyUnavailableException
import io.github.lucateam_wt.tumbler.domain.crypto.KeyUnavailableException
import io.github.lucateam_wt.tumbler.domain.crypto.NewStorageKeyRequiredException
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
import javax.crypto.AEADBadTagException

/**
 * The data keys of this process (`docs/storage-format.md`, "Key states"). One instance per
 * process, shared by every reader and writer, so a data key is normally unwrapped once per process.
 *
 * **Reading** ([key]) never creates a key. The first caller for a version reads its record from
 * [store] in its own coroutine, then waits for one shared unwrap that runs in this holder's scope.
 * Concurrent first callers share it, and a caller that is cancelled while it waits does not abort
 * it: the key is kept for the next caller.
 *
 * **Failures.** A definite failure of an unwrap is cached and reported to every later caller
 * without another Keystore call, until [clearFailures] or [wipe]: the record is malformed, the
 * unwrapped key has the wrong length, the wrapping key is gone ([KeyUnavailableException]), or the
 * record does not authenticate (an [AEADBadTagException] cause). Any other cipher failure is a
 * Keystore or secure-hardware error that may be transient: every caller already waiting on that
 * unwrap gets [KeyTemporarilyUnavailableException], nothing is cached, and the next call starts a
 * new unwrap (still one at a time per version). Failures that are not cipher errors (running out
 * of memory, for example) are not cached either.
 *
 * **Writing** has two steps. [prepareWriteKey], outside any database transaction, returns the
 * version to seal with, unwrapping it if needed. It creates a data key only on a fresh install (no
 * record, and no account names a data key). If the newest key cannot be used for good (its
 * wrapping key is gone or invalidated, its record does not unwrap, or accounts name a version
 * without a record) it throws [NewStorageKeyRequiredException] and creates nothing: only
 * [startNewKey], which the app calls after the user confirmed it, makes the next version. A
 * failure that may be transient never leads to a new key. [writeKey], inside the transaction that
 * writes the rows, re-reads that version's record in that transaction and returns the key only if
 * it is already unwrapped from that same record, so a row is never committed without the record of
 * its key, and nothing is unwrapped inside a transaction.
 *
 * **Inside a transaction** callers use only [writeKey] and [readyKey], which never unwrap and never
 * call the Keystore: keys are resolved before the transaction opens ([prepareWriteKey], [key]).
 *
 * **Deadlock freedom:** the holder's own scope does Keystore work only, never database work, and
 * no lock of the holder is held across a [store] call. So a caller that holds a write transaction
 * (on a device without WAL, the only connection) can wait for an unwrap without blocking it.
 *
 * **Never overwritten:** a new key always gets a version above every stored record and every
 * account row, and its own Keystore alias ([EnvelopeFormat.wrapAlias]); it is stored with
 * [DataKeyStore.insertIfAbsent]. When a different record already holds that version, ours is
 * wiped and the stored one is used. No new key is made for a version this holder already has a
 * key for.
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
     * Entries of both maps are only ever removed, never put back (except by [wipe], for a key it
     * was not allowed to zero), so work that ends after its entry was removed (by [wipe] or a newer
     * record) sees that by identity and wipes its result.
     */
    private val created = HashMap<Int, Pending>()

    /** Keys that [wipe] could not zero and could not keep in [unwrapped]; the next [wipe] zeroes them. Guarded by [lock]. */
    private val unwiped = ArrayList<DataKey>()

    private class Slot(val record: WrappedDataKey) {
        lateinit var deferred: Deferred<DataKey>

        /** The key once unwrapped while this slot was current; written under [lock]. */
        @Volatile
        var key: DataKey? = null

        /** The unwrap failed definitely, which stays cached. */
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
     * @throws KeyTemporarilyUnavailableException if the Keystore failed in a way that may be
     *   transient; nothing is cached and the next call tries again.
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
     * The data key [version] if this holder has already unwrapped it, otherwise `null`. Makes no
     * database or Keystore call and never starts an unwrap, so it is safe inside a transaction.
     */
    fun readyKey(version: Int): DataKey? =
        synchronized(lock) { unwrapped[version]?.key }?.takeUnless { it.isWiped }

    /**
     * Write path, step 1: the version to seal new secrets with, unwrapped and ready for [writeKey].
     * Call it **outside** any database transaction: a key created inside one that rolls back would
     * leave no record, and an unwrap would run under the transaction.
     *
     * - No record and no account names a data key (a fresh install): creates version 1.
     * - The newest record unwraps: returns its version.
     * - The newest key cannot be used for good (wrapping key gone or invalidated, record does not
     *   unwrap), or accounts name a version but no record exists: throws
     *   [NewStorageKeyRequiredException] and creates nothing.
     *
     * @throws NewStorageKeyRequiredException as above; [startNewKey] is the only way out.
     * @throws KeyTemporarilyUnavailableException if the newest key, or the first one, could not be
     *   used or made this time because of a Keystore error that may be transient; calling again
     *   retries. Nothing is created because of it.
     * @throws SecretCipherException if no key can be used or made for another reason.
     */
    suspend fun prepareWriteKey(): Int {
        repeat(MAX_ATTEMPTS) {
            val newest = store.all().maxByOrNull { it.version }
            if (newest != null) {
                val failure = definiteFailureOf(newest) ?: return newest.version
                throw NewStorageKeyRequiredException(failure)
            }
            if (store.highestAccountKeyVersion() > EnvelopeFormat.LEGACY_KEY_VERSION) {
                // Rows name a data key whose record is gone: never guess, never reuse their number.
                throw NewStorageKeyRequiredException()
            }
            if (storeNewKey(EnvelopeFormat.FIRST_DATA_KEY_VERSION)) return EnvelopeFormat.FIRST_DATA_KEY_VERSION
        }
        throw SecretCipherException("Data key unavailable")
    }

    /**
     * Starts a new storage key: the explicit, user-confirmed way out of
     * [NewStorageKeyRequiredException]. Call it outside any database transaction.
     *
     * It checks the newest key again first. If that key unwraps, it returns its version and creates
     * nothing (a second tap, or the key came back); if it fails in a way that may be transient, it
     * throws and creates nothing. Only when the newest key cannot be used for good, or rows name a
     * version without a record, does it create the next version: one above every record and every
     * account row, under its own alias, so no version number or alias is ever reused. Old records
     * and the rows sealed with them are left exactly as they are.
     *
     * @return the version new secrets are sealed with from now on.
     * @throws KeyTemporarilyUnavailableException if the Keystore failed in a way that may be transient.
     * @throws SecretCipherException if no key can be made.
     */
    suspend fun startNewKey(): Int {
        repeat(MAX_ATTEMPTS) {
            val newest = store.all().maxByOrNull { it.version }
            if (newest != null && definiteFailureOf(newest) == null) return newest.version
            val highestUsed = maxOf(newest?.version ?: 0, store.highestAccountKeyVersion())
            if (highestUsed == Int.MAX_VALUE) throw SecretCipherException("No data key version left")
            val target = highestUsed.coerceAtLeast(EnvelopeFormat.LEGACY_KEY_VERSION) + 1
            if (storeNewKey(target)) return target
        }
        throw SecretCipherException("Data key unavailable")
    }

    /**
     * Whether new secrets can be sealed now, without creating anything: unwraps the newest key (once
     * per process, as [prepareWriteKey] would) but never makes a key. Call it outside any transaction.
     */
    suspend fun writeKeyStatus(): WriteKeyStatus {
        val newest = store.all().maxByOrNull { it.version }
            ?: return if (store.highestAccountKeyVersion() > EnvelopeFormat.LEGACY_KEY_VERSION) {
                WriteKeyStatus.NEW_KEY_REQUIRED
            } else {
                WriteKeyStatus.NOT_CREATED
            }
        return try {
            if (definiteFailureOf(newest) == null) WriteKeyStatus.READY else WriteKeyStatus.NEW_KEY_REQUIRED
        } catch (_: SecretCipherException) {
            WriteKeyStatus.TEMPORARILY_UNAVAILABLE
        }
    }

    /**
     * Write path, step 2: the key to seal with [version], called **inside** the transaction that
     * writes the rows. It reads [version]'s record in that transaction and returns the key only if
     * this holder has already unwrapped it from that same record ([prepareWriteKey] did). It never
     * unwraps and never creates a key.
     *
     * @throws KeyUnavailableException if the transaction cannot see a record of [version].
     * @throws KeyTemporarilyUnavailableException if the key is not unwrapped from that record in
     *   memory (call [prepareWriteKey] again, outside the transaction).
     * @throws SecretCipherException if [version] is below 1 or the record is not of [version].
     */
    suspend fun writeKey(version: Int): DataKey {
        if (version < EnvelopeFormat.FIRST_DATA_KEY_VERSION) throw SecretCipherException("Unsupported key version")
        val record = store.get(version) ?: throw KeyUnavailableException()
        if (record.version != version) throw SecretCipherException("Invalid wrapped data key")
        val key = synchronized(lock) { unwrapped[version]?.takeIf { it.record.sameKeyAs(record) }?.key }
        if (key == null || key.isWiped) throw KeyTemporarilyUnavailableException()
        return key
    }

    /**
     * Forgets cached definite unwrap failures, so the next call for those versions tries again.
     * Transient failures are never cached. Makes no database or Keystore call, so a reload of the
     * account list can call it every time.
     */
    fun clearFailures() {
        synchronized(lock) { unwrapped.values.removeAll { it.failed } }
    }

    /**
     * Zeroes every data key this holder has, each once no operation uses it, and forgets them; work
     * still running wipes its result when it ends. The next use unwraps again. Cached failures are
     * forgotten too.
     *
     * Do not call it from inside an operation that uses a key: that key cannot be zeroed under its
     * own use, so it is kept (and still served) for a later [wipe], every other key and pending new
     * key is still zeroed, and the first such failure ([IllegalStateException]) is thrown at the end.
     */
    fun wipe() {
        val slots: List<Slot>
        val pending: List<Pending>
        val leftover: List<DataKey>
        synchronized(lock) {
            slots = unwrapped.values.toList()
            unwrapped.clear()
            pending = created.values.toList()
            created.clear()
            leftover = unwiped.toList()
            unwiped.clear()
        }
        var failure: Throwable? = null
        val kept = ArrayList<Slot>(0)
        for (slot in slots) {
            try {
                release(slot)
            } catch (t: Throwable) {
                kept += slot
                if (failure == null) failure = t else failure.addSuppressed(t)
            }
        }
        val refused = ArrayList<DataKey>(0)
        for (key in pending.mapNotNull { it.result?.key } + leftover) {
            try {
                key.wipe()
            } catch (t: Throwable) {
                refused += key
                if (failure == null) failure = t else failure.addSuppressed(t)
            }
        }
        if (kept.isNotEmpty() || refused.isNotEmpty()) {
            synchronized(lock) {
                for (slot in kept) {
                    val key = slot.key
                    if (unwrapped.putIfAbsent(slot.record.version, slot) != null && key != null) unwiped += key
                }
                unwiped += refused
            }
        }
        failure?.let { throw it }
    }

    /**
     * `null` if [record]'s key unwraps (it is then ready), or the definite failure that makes it
     * unusable for good: its wrapping key is gone, or its unwrap failed in a way that stays cached
     * (malformed, wrong length, does not authenticate). Failures that may be transient, and any
     * other failure, are thrown.
     */
    private suspend fun definiteFailureOf(record: WrappedDataKey): SecretCipherException? {
        try {
            keyFor(record)
            return null
        } catch (e: KeyTemporarilyUnavailableException) {
            throw e
        } catch (e: SecretCipherException) {
            val cached = synchronized(lock) {
                unwrapped[record.version]?.let { it.failed && it.record.sameKeyAs(record) } == true
            }
            if (e is KeyUnavailableException || cached) return e
            throw e
        }
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
            val associatedData = EnvelopeFormat.wrapAssociatedData(record.version, record.kekAlias)
            val wrapper = wrapperFor(record.kekAlias)
            val raw = keystoreCall { wrapper.unwrap(record.wrappedKey, associatedData) }
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
        } catch (e: KeyTemporarilyUnavailableException) {
            // May be transient: not cached. Callers already waiting share this failure; the next
            // call finds no slot and unwraps again.
            forget(slot)
            throw e
        } catch (e: SecretCipherException) {
            // Definite (malformed, wrong length, wrapping key gone, does not authenticate): cached.
            slot.failed = true
            throw e
        } catch (t: Throwable) {
            // Not a property of the stored key (for example out of memory): not cached.
            forget(slot)
            throw t
        }
    }

    /**
     * Makes (once, shared by concurrent callers) and stores a new key for [version]; false if another
     * key holds it or the caller must read the table again.
     *
     * If this holder already has a slot for [version] (another caller made or read that version
     * after this caller read the table, for example while this caller's read ran before the other's
     * insert committed), no key is made: see [isStored].
     */
    private suspend fun storeNewKey(version: Int): Boolean {
        var installed: Slot? = null
        val pending = synchronized(lock) {
            val slot = unwrapped[version]
            if (slot != null) {
                installed = slot
                null
            } else {
                created[version] ?: Pending(version).also { made ->
                    made.deferred = scope.async(start = CoroutineStart.LAZY) { create(made) }
                    created[version] = made
                }
            }
        }
        if (pending == null) return isStored(checkNotNull(installed))
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
     * Whether [slot]'s version is stored with a usable key. A slot exists only for a record that was
     * read from, or stored in, [store], so its version is taken and no new key is made for it. True
     * if the stored record is the slot's and the slot has not failed. A failed slot gives false, so
     * the caller reads the table again and reports that key's failure. A slot whose record is gone
     * (its transaction rolled back) or differs is dropped and its key wiped, so it is never used.
     */
    private suspend fun isStored(slot: Slot): Boolean {
        val stored = store.get(slot.record.version)
        if (stored != null && stored.sameKeyAs(slot.record)) return !slot.failed
        forget(slot)
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
            val wrapped = keystoreCall { wrapper.wrap(raw, associatedData) }
            if (!EnvelopeFormat.isWellFormedWrappedKey(wrapped)) throw SecretCipherException("Invalid wrapped data key")
            val check = keystoreCall { wrapper.unwrap(wrapped, associatedData) }
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
                // The same record was unwrapped meanwhile: keep that slot, drop this copy. (Reached
                // only after created[version] was cleared without adopting this creation, that is
                // after wipe(), so the copy is already zeroed; kept as a guard.)
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
    } catch (e: KeyTemporarilyUnavailableException) {
        throw KeyTemporarilyUnavailableException(e)
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

        /**
         * Runs one wrapper call and sorts its failure. A missing wrapping key
         * ([KeyUnavailableException]) and a key that does not authenticate (an [AEADBadTagException]
         * cause) are definite and pass through. Any other cipher failure is a Keystore or
         * secure-hardware error that may be transient ([java.security.ProviderException],
         * [java.security.KeyStoreException], [java.io.IOException], an
         * [java.security.InvalidKeyException] and the like, which [AesGcmSecretCipher] reports as
         * "Decryption failed" or "Encryption failed") and becomes [KeyTemporarilyUnavailableException].
         */
        inline fun <T> keystoreCall(block: () -> T): T = try {
            block()
        } catch (e: KeyUnavailableException) {
            throw e
        } catch (e: KeyTemporarilyUnavailableException) {
            throw e
        } catch (e: SecretCipherException) {
            throw if (KeystoreErrors.isAuthenticationFailure(e)) e else KeyTemporarilyUnavailableException(e)
        }
    }
}
