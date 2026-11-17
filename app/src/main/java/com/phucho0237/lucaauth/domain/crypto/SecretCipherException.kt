package com.phucho0237.lucaauth.domain.crypto

/**
 * Thrown by [SecretCipher] for every failure.
 *
 * [message] is always a fixed string and never contains plaintext, ciphertext, keys,
 * IVs or associated data.
 */
class SecretCipherException(message: String, cause: Throwable? = null) : Exception(message, cause)
