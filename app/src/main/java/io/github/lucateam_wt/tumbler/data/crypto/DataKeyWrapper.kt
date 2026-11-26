package io.github.lucateam_wt.tumbler.data.crypto

import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipher

/**
 * Wraps and unwraps a data key with one wrapping key (a Keystore key in the app). Pure Kotlin, so
 * tests can use a JVM key and count calls.
 */
interface DataKeyWrapper {

    /**
     * Encrypts [dataKey] with [associatedData] and returns the wrapped key. May create the
     * wrapping key if it does not exist yet.
     *
     * @throws io.github.lucateam_wt.tumbler.domain.crypto.SecretCipherException on any failure.
     */
    fun wrap(dataKey: ByteArray, associatedData: ByteArray): ByteArray

    /**
     * Decrypts a key that [wrap] made with the same [associatedData]. Never creates a wrapping key.
     * The caller owns the returned array.
     *
     * @throws io.github.lucateam_wt.tumbler.domain.crypto.KeyUnavailableException if the wrapping
     *   key does not exist.
     * @throws io.github.lucateam_wt.tumbler.domain.crypto.SecretCipherException on any other failure.
     */
    fun unwrap(wrapped: ByteArray, associatedData: ByteArray): ByteArray
}

/**
 * [DataKeyWrapper] over an [AesGcmSecretCipher]-style [SecretCipher]: the wrapped key is that
 * cipher's payload, `01 | 0c | IV (12) | ciphertext (32) | tag (16)`, 62 bytes.
 */
class AesGcmDataKeyWrapper(private val cipher: SecretCipher) : DataKeyWrapper {

    override fun wrap(dataKey: ByteArray, associatedData: ByteArray): ByteArray =
        cipher.encrypt(dataKey, associatedData)

    override fun unwrap(wrapped: ByteArray, associatedData: ByteArray): ByteArray =
        cipher.decrypt(wrapped, associatedData)
}
