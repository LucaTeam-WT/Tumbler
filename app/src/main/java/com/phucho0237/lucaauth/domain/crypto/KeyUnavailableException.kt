package com.phucho0237.lucaauth.domain.crypto

/**
 * Thrown by [SecretCipher.decrypt] when the encryption key does not exist, for example
 * after app data was cleared. Payloads encrypted with the lost key cannot be recovered.
 * Decryption never creates a new key.
 */
class KeyUnavailableException : SecretCipherException("Encryption key unavailable")
