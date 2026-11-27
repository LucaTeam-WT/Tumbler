package io.github.lucateam_wt.tumbler.data.repository

import io.github.lucateam_wt.tumbler.data.crypto.DataKeyCipher
import io.github.lucateam_wt.tumbler.data.crypto.DataKeyHolder
import io.github.lucateam_wt.tumbler.data.crypto.EnvelopeFormat
import io.github.lucateam_wt.tumbler.data.db.AccountEntity
import io.github.lucateam_wt.tumbler.domain.crypto.KeyTemporarilyUnavailableException
import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipherException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Opens a row's stored secret in either format (`docs/storage-format.md`):
 * - `keyVersion` 0: a legacy payload under the legacy Keystore key, opened by [legacy] with the id
 *   as associated data.
 * - `keyVersion` n >= 1: a payload sealed with data key n, opened in software. If data key n cannot
 *   be used for good (no record, wrapping key gone or invalidated, record does not unwrap) and the
 *   row still holds its legacy copy (`legacySecretBlob`, kept for one release after the migration),
 *   that copy is opened with [legacy] instead. A failure that may be transient never falls back.
 *
 * **Outside a transaction**, [opener] resolves what is needed (it may unwrap a data key once per
 * process) and returns a function that does the blocking part on the caller's cipher dispatcher.
 *
 * **Inside a write transaction** no unwrap and no Keystore call may run (a slow StrongBox would
 * hold the database's only connection on a device without WAL). So the caller first calls
 * [prepare] outside the transaction with the rows it will read, and inside it uses
 * [openerInTransaction], which only uses keys already in memory and the plaintexts [prepare] made.
 */
internal class StoredSecretReader(
    private val legacy: LegacySecretReader,
    private val dataKeys: DataKeyHolder,
) {

    /**
     * What is needed to open [row]'s secret, outside any transaction. The returned function blocks
     * (Keystore IPC for a legacy row or the legacy copy) and returns a new plaintext array that the
     * caller owns and must wipe.
     *
     * @throws KeyTemporarilyUnavailableException if the row's data key could not be unwrapped this
     *   time (a Keystore error that may be transient); nothing is cached and calling again retries.
     * @throws SecretCipherException if the row's key version is negative, or its data key cannot be
     *   used and it has no legacy copy.
     */
    suspend fun opener(row: AccountEntity): () -> ByteArray {
        val id = row.id
        val blob = row.secretBlob
        if (row.keyVersion == EnvelopeFormat.LEGACY_KEY_VERSION) return { legacy.decrypt(blob, id) }
        if (row.keyVersion < EnvelopeFormat.LEGACY_KEY_VERSION) throw SecretCipherException("Unsupported key version")
        val key = try {
            dataKeys.key(row.keyVersion)
        } catch (e: KeyTemporarilyUnavailableException) {
            throw e
        } catch (e: SecretCipherException) {
            val copy = row.legacySecretBlob ?: throw e
            return { legacy.decrypt(copy, id) }
        }
        return { DataKeyCipher.open(key, id, blob) }
    }

    /**
     * Step 1 for reads inside a transaction, called **outside** it: unwraps the data keys [rows]
     * name (once per process each) and opens, on [dispatcher], every secret that needs the Keystore
     * (legacy rows, and legacy copies of rows whose data key cannot be used). A row whose data key
     * is ready is not opened here. The result must be closed; closing wipes what it holds.
     */
    suspend fun prepare(rows: List<AccountEntity>, dispatcher: CoroutineDispatcher): Prepared {
        val prepared = Prepared()
        var complete = false
        try {
            val ready = HashMap<Int, Boolean>()
            for (row in rows) {
                val version = row.keyVersion
                if (version >= EnvelopeFormat.FIRST_DATA_KEY_VERSION && ready.getOrPut(version) { keyReady(version) }) continue
                prepared.add(row, prepareOne(row, dispatcher))
            }
            complete = true
            return prepared
        } finally {
            if (!complete) prepared.close()
        }
    }

    /**
     * Step 2, inside the transaction: what is needed to open [row]'s secret, from memory only (keys
     * this holder has, and [prepared]). It makes no Keystore call and never unwraps. The returned
     * function returns a new plaintext array that the caller owns and must wipe.
     *
     * @throws KeyTemporarilyUnavailableException if [row] changed since [prepare] (the caller may
     *   try again: prepare, then a new transaction).
     * @throws SecretCipherException with the failure [prepare] met, if [row] cannot be read.
     */
    fun openerInTransaction(row: AccountEntity, prepared: Prepared): () -> ByteArray {
        val id = row.id
        val blob = row.secretBlob
        if (row.keyVersion >= EnvelopeFormat.FIRST_DATA_KEY_VERSION) {
            val key = dataKeys.readyKey(row.keyVersion)
            if (key != null) return { DataKeyCipher.open(key, id, blob) }
        }
        val entry = prepared.entryFor(row) ?: throw KeyTemporarilyUnavailableException()
        entry.failure?.let { throw it }
        return { prepared.copyOf(entry) }
    }

    private suspend fun keyReady(version: Int): Boolean = try {
        dataKeys.key(version)
        true
    } catch (_: SecretCipherException) {
        false
    }

    /** Opens [row] through the Keystore; a cipher failure is kept, to be thrown in the transaction. */
    private suspend fun prepareOne(row: AccountEntity, dispatcher: CoroutineDispatcher): Prepared.Entry {
        val open = try {
            opener(row)
        } catch (e: SecretCipherException) {
            return Prepared.Entry(row, plaintext = null, failure = e)
        }
        // Set inside the cipher block, so it is wiped even if withContext drops its result.
        var plaintext: ByteArray? = null
        return try {
            withContext(dispatcher) { open().also { plaintext = it } }
            Prepared.Entry(row, plaintext, failure = null)
        } catch (e: SecretCipherException) {
            Prepared.Entry(row, plaintext = null, failure = e)
        } catch (t: Throwable) {
            plaintext?.fill(0)
            throw t
        }
    }

    /**
     * Secrets opened by [prepare] for one transaction, keyed by account id and valid only for the
     * exact row they were read from (key version, payload and legacy copy). [close] wipes them.
     */
    class Prepared internal constructor() : AutoCloseable {

        internal class Entry(row: AccountEntity, val plaintext: ByteArray?, val failure: SecretCipherException?) {
            val keyVersion = row.keyVersion
            val secretBlob: ByteArray = row.secretBlob
            val legacySecretBlob: ByteArray? = row.legacySecretBlob

            fun matches(row: AccountEntity) = row.keyVersion == keyVersion &&
                row.secretBlob.contentEquals(secretBlob) &&
                row.legacySecretBlob.contentEquals(legacySecretBlob)
        }

        private val entries = HashMap<String, Entry>()
        private var closed = false

        internal fun add(row: AccountEntity, entry: Entry) {
            synchronized(entries) {
                if (closed) {
                    entry.plaintext?.fill(0)
                    return
                }
                entries.put(row.id, entry)?.plaintext?.fill(0)
            }
        }

        internal fun entryFor(row: AccountEntity): Entry? =
            synchronized(entries) { check(!closed) { "Prepared secrets are closed" }; entries[row.id] }?.takeIf { it.matches(row) }

        internal fun copyOf(entry: Entry): ByteArray = synchronized(entries) {
            check(!closed) { "Prepared secrets are closed" }
            checkNotNull(entry.plaintext).copyOf()
        }

        /** How many secrets it holds; for tests. */
        internal val size: Int get() = synchronized(entries) { entries.size }

        override fun close() {
            synchronized(entries) {
                closed = true
                for (entry in entries.values) entry.plaintext?.fill(0)
                entries.clear()
            }
        }
    }
}
