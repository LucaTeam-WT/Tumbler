package io.github.lucateam_wt.tumbler.data.crypto

import androidx.annotation.VisibleForTesting
import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipherException
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Seals and opens an account secret with a [DataKey], in software (`javax.crypto`): AES-256-GCM,
 * a random 12-byte nonce per seal, a 128-bit tag, and associated data that binds the purpose,
 * the key version and the account id ([EnvelopeFormat.secretAssociatedData]).
 *
 * Payload (version 2): `02 | 0c | nonce (12) | ciphertext (1 to 65536) | tag (16)`, so 31 to
 * 65566 bytes. See `docs/storage-format.md`.
 *
 * Every failure is a [SecretCipherException] with a fixed message that never contains key
 * material, plaintext, nonces or associated data. A new [Cipher] is made per call.
 */
internal object DataKeyCipher {

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"

    /**
     * Seals [plaintext] ([EnvelopeFormat.MIN_SECRET_BYTES] to [EnvelopeFormat.MAX_SECRET_BYTES]
     * bytes) for [accountId] under [key], with a fresh nonce from [random]. The result carries
     * the key version bound in the associated data; store it with the blob.
     */
    fun seal(key: DataKey, accountId: String, plaintext: ByteArray, random: SecureRandom): SealedSecret {
        checkPlaintext(plaintext)
        val keyVersion = key.version
        val associatedData = EnvelopeFormat.secretAssociatedData(keyVersion, accountId)
        val nonce = ByteArray(EnvelopeFormat.NONCE_BYTES).also(random::nextBytes)
        val blob = key.use { sealWith(it, nonce, plaintext, associatedData) }
            ?: throw SecretCipherException("Data key unavailable")
        return SealedSecret(keyVersion, blob)
    }

    /**
     * Opens a payload that [seal] made for [accountId] under [key]. The caller owns the returned
     * array and should wipe it.
     */
    fun open(key: DataKey, accountId: String, payload: ByteArray): ByteArray {
        checkPayload(payload)
        val associatedData = EnvelopeFormat.secretAssociatedData(key.version, accountId)
        return key.use { openWith(it, payload, associatedData) }
            ?: throw SecretCipherException("Data key unavailable")
    }

    /**
     * [seal] with an explicit [nonce] and associated data, for known-answer tests. A production
     * caller could reuse a key and nonce, which breaks GCM; `app/lint.xml` makes such a call a
     * lint error. It refuses the same plaintext lengths as [seal], so it never makes a payload
     * that [open] refuses.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun sealWith(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        checkPlaintext(plaintext)
        if (key.size != EnvelopeFormat.DATA_KEY_BYTES) throw SecretCipherException("Invalid key")
        if (nonce.size != EnvelopeFormat.NONCE_BYTES) throw SecretCipherException("Invalid nonce")
        val sealed = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, KEY_ALGORITHM),
                GCMParameterSpec(EnvelopeFormat.TAG_BITS, nonce),
            )
            cipher.updateAAD(associatedData)
            cipher.doFinal(plaintext)
        } catch (e: GeneralSecurityException) {
            throw SecretCipherException("Encryption failed", e)
        } catch (e: ProviderException) {
            throw SecretCipherException("Encryption failed", e)
        }
        try {
            if (sealed.size != plaintext.size + EnvelopeFormat.TAG_BYTES) throw SecretCipherException("Encryption failed")
            val payload = ByteArray(EnvelopeFormat.HEADER_BYTES + nonce.size + sealed.size)
            payload[0] = EnvelopeFormat.SECRET_PAYLOAD_VERSION
            payload[1] = nonce.size.toByte()
            nonce.copyInto(payload, EnvelopeFormat.HEADER_BYTES)
            sealed.copyInto(payload, EnvelopeFormat.HEADER_BYTES + nonce.size)
            return payload
        } finally {
            sealed.fill(0)
        }
    }

    /** [open] with explicit associated data, for known-answer tests (see [sealWith]). */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun openWith(key: ByteArray, payload: ByteArray, associatedData: ByteArray): ByteArray {
        if (key.size != EnvelopeFormat.DATA_KEY_BYTES) throw SecretCipherException("Invalid key")
        checkPayload(payload)
        val sealedOffset = EnvelopeFormat.HEADER_BYTES + EnvelopeFormat.NONCE_BYTES
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, KEY_ALGORITHM),
                GCMParameterSpec(EnvelopeFormat.TAG_BITS, payload, EnvelopeFormat.HEADER_BYTES, EnvelopeFormat.NONCE_BYTES),
            )
            cipher.updateAAD(associatedData)
            cipher.doFinal(payload, sealedOffset, payload.size - sealedOffset)
        } catch (e: GeneralSecurityException) {
            throw SecretCipherException("Decryption failed", e)
        } catch (e: ProviderException) {
            throw SecretCipherException("Decryption failed", e)
        }
    }

    private fun checkPlaintext(plaintext: ByteArray) {
        if (plaintext.size < EnvelopeFormat.MIN_SECRET_BYTES) throw SecretCipherException("Plaintext empty")
        if (plaintext.size > EnvelopeFormat.MAX_SECRET_BYTES) throw SecretCipherException("Plaintext too large")
    }

    /** Refuses a payload whose ciphertext would be empty or too large, as [checkPlaintext] does when sealing. */
    private fun checkPayload(payload: ByteArray) {
        if (payload.size < EnvelopeFormat.MIN_SECRET_PAYLOAD_BYTES) throw SecretCipherException("Payload too short")
        if (payload.size > EnvelopeFormat.MAX_SECRET_PAYLOAD_BYTES) throw SecretCipherException("Payload too large")
        if (payload[0] != EnvelopeFormat.SECRET_PAYLOAD_VERSION) throw SecretCipherException("Unsupported payload version")
        if (payload[1].toInt() != EnvelopeFormat.NONCE_BYTES) throw SecretCipherException("Invalid nonce length")
    }
}
