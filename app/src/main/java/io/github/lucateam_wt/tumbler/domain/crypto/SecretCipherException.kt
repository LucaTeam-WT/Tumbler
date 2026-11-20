package io.github.lucateam_wt.tumbler.domain.crypto

/**
 * Thrown by [SecretCipher] for every failure.
 *
 * [message] is always a fixed string and never contains plaintext, ciphertext, keys,
 * IVs or associated data.
 */
open class SecretCipherException(message: String, cause: Throwable? = null) : Exception(message, cause)
