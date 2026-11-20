package io.github.lucateam_wt.tumbler.data.crypto

import io.github.lucateam_wt.tumbler.domain.crypto.KeyUnavailableException
import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipher
import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipherException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM [SecretCipher] using only `javax.crypto`, so it runs on the plain JVM and
 * with Android Keystore keys alike.
 *
 * Payload format: `[version = 1][IV length][IV][ciphertext + 128-bit tag]`.
 *
 * The IV is never supplied by this class: the provider generates a fresh random IV
 * on every encryption (Android Keystore requires randomized encryption). A new
 * [Cipher] is created per call, so instances are thread-safe.
 *
 * [encrypt] uses [KeyProvider.getOrCreateKey]; [decrypt] uses
 * [KeyProvider.getExistingKey] and throws [KeyUnavailableException] instead of
 * creating a key.
 */
class AesGcmSecretCipher(private val keyProvider: KeyProvider) : SecretCipher {

    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        if (plaintext.size > MAX_PLAINTEXT_BYTES) throw SecretCipherException("Plaintext too large")

        val iv: ByteArray
        val sealed: ByteArray
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, keyProvider.getOrCreateKey())
            iv = cipher.iv ?: throw SecretCipherException("Encryption failed")
            if (associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
            sealed = cipher.doFinal(plaintext)
        } catch (e: GeneralSecurityException) {
            throw SecretCipherException("Encryption failed", e)
        } catch (e: ProviderException) {
            throw SecretCipherException("Encryption failed", e)
        } catch (e: IOException) {
            throw SecretCipherException("Encryption failed", e)
        }

        try {
            if (iv.size != IV_LENGTH || sealed.size != plaintext.size + TAG_LENGTH_BYTES) {
                throw SecretCipherException("Encryption failed")
            }
            val payload = ByteArray(HEADER_LENGTH + iv.size + sealed.size)
            payload[0] = VERSION
            payload[1] = iv.size.toByte()
            iv.copyInto(payload, HEADER_LENGTH)
            sealed.copyInto(payload, HEADER_LENGTH + iv.size)
            return payload
        } finally {
            iv.fill(0)
            sealed.fill(0)
        }
    }

    override fun decrypt(payload: ByteArray, associatedData: ByteArray): ByteArray {
        if (payload.size < MIN_PAYLOAD_BYTES) throw SecretCipherException("Payload too short")
        if (payload.size > MAX_PAYLOAD_BYTES) throw SecretCipherException("Payload too large")
        if (payload[0] != VERSION) throw SecretCipherException("Unsupported payload version")
        if (payload[1].toInt() != IV_LENGTH) throw SecretCipherException("Invalid IV length")

        val sealedOffset = HEADER_LENGTH + IV_LENGTH
        try {
            val key = keyProvider.getExistingKey() ?: throw KeyUnavailableException()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(TAG_LENGTH_BITS, payload, HEADER_LENGTH, IV_LENGTH)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            if (associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
            return cipher.doFinal(payload, sealedOffset, payload.size - sealedOffset)
        } catch (e: GeneralSecurityException) {
            throw SecretCipherException("Decryption failed", e)
        } catch (e: ProviderException) {
            throw SecretCipherException("Decryption failed", e)
        } catch (e: IOException) {
            throw SecretCipherException("Decryption failed", e)
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
        const val HEADER_LENGTH = 2
        const val IV_LENGTH = 12
        const val TAG_LENGTH_BITS = 128
        const val TAG_LENGTH_BYTES = TAG_LENGTH_BITS / 8
        const val MAX_PLAINTEXT_BYTES = 64 * 1024
        const val MIN_PAYLOAD_BYTES = HEADER_LENGTH + IV_LENGTH + TAG_LENGTH_BYTES
        const val MAX_PAYLOAD_BYTES = MIN_PAYLOAD_BYTES + MAX_PLAINTEXT_BYTES
    }
}
