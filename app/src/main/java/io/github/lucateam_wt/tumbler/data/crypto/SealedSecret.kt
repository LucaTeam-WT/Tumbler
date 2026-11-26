package io.github.lucateam_wt.tumbler.data.crypto

/**
 * A secret sealed by [DataKeyCipher.seal]: [blob] opens only with data key [keyVersion], whose
 * number is bound in the blob's associated data. Store both together (`accounts.keyVersion` and
 * `accounts.secretBlob`), so a row can never name a key other than the one that sealed it.
 *
 * Not a data class: [blob] is an array, and [toString] prints no ciphertext.
 */
class SealedSecret internal constructor(val keyVersion: Int, val blob: ByteArray) {

    override fun toString(): String = "SealedSecret(keyVersion=$keyVersion, <redacted>)"
}
