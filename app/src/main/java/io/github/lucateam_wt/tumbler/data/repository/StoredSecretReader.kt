package io.github.lucateam_wt.tumbler.data.repository

import io.github.lucateam_wt.tumbler.data.crypto.DataKeyCipher
import io.github.lucateam_wt.tumbler.data.crypto.DataKeyHolder
import io.github.lucateam_wt.tumbler.data.crypto.EnvelopeFormat
import io.github.lucateam_wt.tumbler.data.db.AccountEntity
import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipher
import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipherException

/**
 * Opens a row's stored secret in either format (`docs/storage-format.md`):
 * - `keyVersion` 0: a legacy payload under the Keystore key, opened by [legacy] with the id as
 *   associated data, exactly as before schema version 2. It never touches [dataKeys].
 * - `keyVersion` n >= 1: a payload sealed with data key n, opened in software.
 *
 * Opening has two steps so that each runs in the right place. [opener] runs in the caller's
 * coroutine: inside a database transaction it reads the data key's record (first use only) in
 * that transaction, and it may wait for the shared unwrap, which does no database work, so it
 * cannot deadlock against the transaction. The returned function blocks (Keystore IPC for a
 * legacy row) and belongs on the caller's cipher dispatcher.
 *
 * Nothing writes data-key rows yet; they exist only in tests until the migration ships.
 */
internal class StoredSecretReader(
    private val legacy: SecretCipher,
    private val dataKeys: DataKeyHolder,
) {

    /**
     * Step 1: what is needed to open [row]'s secret. The returned function returns a new plaintext
     * array that the caller owns and must wipe.
     *
     * @throws io.github.lucateam_wt.tumbler.domain.crypto.KeyTemporarilyUnavailableException if
     *   the row's data key could not be unwrapped this time (a Keystore error that may be
     *   transient); nothing is cached and calling again retries.
     * @throws SecretCipherException if the row's key version is negative or its data key cannot
     *   be used (no record, wrapping key gone, record does not unwrap).
     */
    suspend fun opener(row: AccountEntity): () -> ByteArray {
        val id = row.id
        val blob = row.secretBlob
        if (row.keyVersion == EnvelopeFormat.LEGACY_KEY_VERSION) {
            return { legacy.decrypt(blob, accountAssociatedData(id)) }
        }
        if (row.keyVersion < EnvelopeFormat.LEGACY_KEY_VERSION) throw SecretCipherException("Unsupported key version")
        val key = dataKeys.key(row.keyVersion)
        return { DataKeyCipher.open(key, id, blob) }
    }
}
