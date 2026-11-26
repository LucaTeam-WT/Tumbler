package io.github.lucateam_wt.tumbler.domain.crypto

/**
 * Thrown when a key exists but could not be used this time, for example because the Android
 * Keystore or its secure hardware reported an error that may be transient. Nothing about the
 * failure is remembered: calling again tries again.
 *
 * [message] is a fixed string and never contains plaintext, keys or associated data.
 */
class KeyTemporarilyUnavailableException(cause: Throwable? = null) :
    SecretCipherException("Encryption key temporarily unavailable", cause)
