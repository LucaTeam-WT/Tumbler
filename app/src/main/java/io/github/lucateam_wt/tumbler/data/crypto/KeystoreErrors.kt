package io.github.lucateam_wt.tumbler.data.crypto

import android.security.keystore.KeyPermanentlyInvalidatedException
import io.github.lucateam_wt.tumbler.domain.crypto.KeyTemporarilyUnavailableException
import io.github.lucateam_wt.tumbler.domain.crypto.KeyUnavailableException
import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipherException
import javax.crypto.AEADBadTagException

/**
 * Sorts the failures of Keystore cipher calls ([AesGcmSecretCipher] over a Keystore key) into
 * definite and possibly transient ones (`docs/storage-format.md`, "Key states").
 */
internal object KeystoreErrors {

    /** How deep [isAuthenticationFailure] looks into a cause chain. */
    private const val MAX_CAUSE_DEPTH = 8

    /**
     * Sorts a failure of [AesGcmSecretCipher.decrypt] of a stored secret. A key that was
     * permanently invalidated becomes [KeyUnavailableException]. Definite, returned as
     * they are: the key is gone ([KeyUnavailableException]), the payload does not authenticate
     * (an [AEADBadTagException] anywhere in the cause chain), or the payload is malformed (the
     * cipher's own checks, made before any Keystore call, throw without a cause). Every other
     * failure wraps a Keystore or secure-hardware error ([java.security.ProviderException],
     * [java.security.KeyStoreException], [java.io.IOException], an
     * [java.security.InvalidKeyException] and the like) that may be transient, and becomes a
     * [KeyTemporarilyUnavailableException] with it as the cause.
     */
    fun sortDecryptFailure(e: SecretCipherException): SecretCipherException = when {
        e is KeyUnavailableException || e is KeyTemporarilyUnavailableException -> e
        isPermanentlyInvalidated(e) -> KeyUnavailableException()
        e.cause == null || isAuthenticationFailure(e) -> e
        else -> KeyTemporarilyUnavailableException(e)
    }

    /** True if [e] or one of its causes is an [AEADBadTagException]: the key or payload does not authenticate. */
    fun isAuthenticationFailure(e: Throwable): Boolean = causeChain(e).any { it is AEADBadTagException }

    /**
     * True if [e] or one of its causes is a [KeyPermanentlyInvalidatedException]: the Keystore key
     * exists but can never be used again, so it counts as gone ([KeyUnavailableException]), never
     * as a failure to retry.
     */
    fun isPermanentlyInvalidated(e: Throwable): Boolean = causeChain(e).any { it is KeyPermanentlyInvalidatedException }

    /** [e] and its causes, at most [MAX_CAUSE_DEPTH] below it. */
    fun causeChain(e: Throwable): Sequence<Throwable> =
        generateSequence(e) { it.cause }.take(MAX_CAUSE_DEPTH + 1)
}
