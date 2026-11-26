package io.github.lucateam_wt.tumbler.data.crypto

import io.github.lucateam_wt.tumbler.domain.crypto.SecretCipherException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Byte layouts of the envelope format (`docs/storage-format.md`). Every associated-data string
 * is built here and only here, so sealing and opening cannot disagree about it.
 */
internal object EnvelopeFormat {

    /** `accounts.keyVersion` of a row whose secret is a legacy payload under the Keystore key. */
    const val LEGACY_KEY_VERSION = 0

    /** The first data key version; every data key version is at least this. */
    const val FIRST_DATA_KEY_VERSION = 1

    /** A data key is 256 bits. */
    const val DATA_KEY_BYTES = 32

    /** Header of a wrapped data key: the [AesGcmSecretCipher] payload version and IV length. */
    const val WRAP_PAYLOAD_VERSION: Byte = 1
    const val WRAP_IV_BYTES = 12

    /** A wrapped data key: `01 | 0c | IV (12) | ciphertext (32) | tag (16)`. */
    const val WRAPPED_DATA_KEY_BYTES = 2 + WRAP_IV_BYTES + DATA_KEY_BYTES + 16

    /** Header of a secret sealed with a data key: payload version and nonce length. */
    const val SECRET_PAYLOAD_VERSION: Byte = 2
    const val NONCE_BYTES = 12
    const val TAG_BITS = 128
    const val TAG_BYTES = TAG_BITS / 8
    const val HEADER_BYTES = 2

    /** The smallest secret the format seals or opens: an empty secret is refused both ways. */
    const val MIN_SECRET_BYTES = 1

    /** The largest secret the format seals or opens, as for the legacy payload. */
    const val MAX_SECRET_BYTES = 64 * 1024

    /** A sealed secret is `HEADER_BYTES + NONCE_BYTES + secret + TAG_BYTES`: 31 to 65566 bytes. */
    const val MIN_SECRET_PAYLOAD_BYTES = HEADER_BYTES + NONCE_BYTES + MIN_SECRET_BYTES + TAG_BYTES
    const val MAX_SECRET_PAYLOAD_BYTES = HEADER_BYTES + NONCE_BYTES + MAX_SECRET_BYTES + TAG_BYTES

    /** An account id or key alias is at most this many UTF-8 bytes in associated data (ids are 36-character UUIDs). */
    const val MAX_NAME_BYTES = 255

    /** Keystore alias of the key that wraps data key version 1. */
    const val WRAP_KEY_ALIAS = "lucaauth_wrap_key_v1"

    private const val SECRET_PURPOSE = "TumblerSecret"
    private const val WRAP_PURPOSE = "TumblerDataKey"

    /**
     * The Keystore alias that wraps data key [version]: [base] for version 1, `base_k<version>`
     * for later ones. Every version has its own alias, so a version that replaces a lost wrapping
     * key never generates a key under an alias that a stored record names.
     */
    fun wrapAlias(base: String, version: Int): String {
        require(version >= FIRST_DATA_KEY_VERSION) { "Data key versions start at 1" }
        return if (version == FIRST_DATA_KEY_VERSION) base else "${base}_k$version"
    }

    /**
     * Associated data of a secret sealed with data key [keyVersion] for account [accountId]:
     * ASCII `TumblerSecret`, `00`, the version as a 4-byte big-endian integer, then the id as
     * strict UTF-8.
     *
     * @throws SecretCipherException if the version is below 1, or the id is not valid UTF-16
     *   (an unpaired surrogate) or longer than [MAX_NAME_BYTES] in UTF-8.
     */
    fun secretAssociatedData(keyVersion: Int, accountId: String): ByteArray =
        associatedData(SECRET_PURPOSE, keyVersion, accountId, "Invalid account id")

    /**
     * Associated data of data key [version] wrapped under the Keystore key [alias]: ASCII
     * `TumblerDataKey`, `00`, the version as a 4-byte big-endian integer, then the alias as
     * strict UTF-8.
     *
     * @throws SecretCipherException if the version is below 1 or the alias is invalid.
     */
    fun wrapAssociatedData(version: Int, alias: String): ByteArray =
        associatedData(WRAP_PURPOSE, version, alias, "Invalid key alias")

    /** True if [wrapped] has the exact size and header of a wrapped data key. */
    fun isWellFormedWrappedKey(wrapped: ByteArray): Boolean =
        wrapped.size == WRAPPED_DATA_KEY_BYTES &&
            wrapped[0] == WRAP_PAYLOAD_VERSION &&
            wrapped[1].toInt() == WRAP_IV_BYTES

    private fun associatedData(purpose: String, version: Int, name: String, invalidName: String): ByteArray {
        if (version < FIRST_DATA_KEY_VERSION) throw SecretCipherException("Unsupported key version")
        val nameBytes = strictUtf8(name) ?: throw SecretCipherException(invalidName)
        if (nameBytes.size > MAX_NAME_BYTES) throw SecretCipherException(invalidName)
        val prefix = purpose.toByteArray(Charsets.US_ASCII)
        return ByteBuffer.allocate(prefix.size + 1 + Int.SIZE_BYTES + nameBytes.size)
            .put(prefix)
            .put(0)
            .putInt(version)
            .put(nameBytes)
            .array()
    }

    /** UTF-8 that refuses unpaired surrogates instead of replacing them, so two ids never share bytes. */
    private fun strictUtf8(text: String): ByteArray? = try {
        val encoded = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(text))
        ByteArray(encoded.remaining()).also { encoded.get(it) }
    } catch (_: CharacterCodingException) {
        null
    }
}
