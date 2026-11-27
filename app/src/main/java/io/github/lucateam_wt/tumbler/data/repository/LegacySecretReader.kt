package io.github.lucateam_wt.tumbler.data.repository

import io.github.lucateam_wt.tumbler.data.crypto.KeystoreErrors
import io.github.lucateam_wt.tumbler.domain.crypto.KeyTemporarilyUnavailableException
import io.github.lucateam_wt.tumbler.domain.crypto.KeyUnavailableException
import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipher
import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipherException

/**
 * Decrypt-only access to secrets in the legacy format (`keyVersion` 0, `docs/storage-format.md`)
 * under the Keystore key `lucaauth_secret_key_v1`. Nothing encrypts with that key any more, so
 * this has no encrypt, and no write can recreate a lost legacy key.
 */
internal fun interface LegacySecretReader {

    /**
     * Decrypts the legacy [payload] of account [accountId] (the id is its associated data). The
     * caller owns the returned array and should wipe it. Blocks on the Keystore.
     *
     * @throws KeyUnavailableException if the legacy key is gone.
     * @throws KeyTemporarilyUnavailableException if the Keystore failed in a way that may be transient.
     * @throws SecretCipherException if the payload is malformed or does not authenticate.
     */
    fun decrypt(payload: ByteArray, accountId: String): ByteArray
}

/**
 * [LegacySecretReader] over [cipher]'s decrypt (the app passes `AesGcmSecretCipher` over the legacy
 * Keystore key), with its failures sorted by [KeystoreErrors.sortDecryptFailure].
 */
internal fun legacySecretReader(cipher: SecretCipher): LegacySecretReader = LegacySecretReader { payload, id ->
    try {
        cipher.decrypt(payload, accountAssociatedData(id))
    } catch (e: SecretCipherException) {
        throw KeystoreErrors.sortDecryptFailure(e)
    }
}
