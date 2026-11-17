package com.phucho0237.lucaauth.data.crypto

import javax.crypto.SecretKey

/**
 * Supplies the AES key used by [AesGcmSecretCipher].
 *
 * Encryption may create the key; decryption must never create it, because a new key
 * cannot decrypt anything written with the old one and would only hide the loss.
 */
interface KeyProvider {

    /**
     * Returns the key, creating it if it does not exist yet. Used for encryption.
     *
     * @throws java.security.GeneralSecurityException if the key store cannot be read or
     *   the key cannot be loaded or generated (for example
     *   [java.security.KeyStoreException], [java.security.UnrecoverableKeyException],
     *   [java.security.NoSuchAlgorithmException] or
     *   [java.security.InvalidAlgorithmParameterException]).
     * @throws java.io.IOException if the key store cannot be loaded.
     * @throws java.security.ProviderException if the key store or secure hardware reports
     *   an internal error.
     */
    fun getOrCreateKey(): SecretKey

    /**
     * Returns the existing key, or `null` if there is none. Never creates a key. Used
     * for decryption.
     *
     * @throws java.security.GeneralSecurityException if the key store cannot be read or
     *   the key cannot be loaded (for example [java.security.KeyStoreException] or
     *   [java.security.UnrecoverableKeyException]).
     * @throws java.io.IOException if the key store cannot be loaded.
     * @throws java.security.ProviderException if the key store or secure hardware reports
     *   an internal error.
     */
    fun getExistingKey(): SecretKey?
}
