package io.github.lucateam_wt.tumbler.domain.crypto

/**
 * Thrown when a new secret cannot be written because the newest storage key cannot be used for
 * good: its wrapping key is gone or invalidated, its record does not unwrap, or accounts name a
 * key version that has no record. Nothing is created automatically; writing works again only
 * after the user has confirmed starting a new storage key. Accounts sealed with the old key are
 * never deleted or rewritten.
 *
 * [message] is a fixed string and never contains keys, plaintext or associated data.
 */
class NewStorageKeyRequiredException(cause: Throwable? = null) :
    SecretCipherException("A new storage key is required", cause)
