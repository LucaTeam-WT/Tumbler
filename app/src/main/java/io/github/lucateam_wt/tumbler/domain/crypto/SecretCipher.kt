package io.github.lucateam_wt.tumbler.domain.crypto

/**
 * Authenticated encryption for small secrets such as OTP seeds.
 *
 * [associatedData] is authenticated but not encrypted; the same bytes must be passed
 * to [decrypt] that were passed to [encrypt]. Bind it to the record the secret
 * belongs to (for example an account ID) so ciphertexts cannot be swapped between
 * records.
 *
 * Implementations never reuse an IV and are safe to call from multiple threads.
 */
interface SecretCipher {

    /**
     * Encrypts [plaintext] and returns a self-describing payload. Two calls with the
     * same input return different payloads.
     *
     * @throws SecretCipherException on any failure.
     */
    fun encrypt(plaintext: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteArray

    /**
     * Decrypts and authenticates a payload returned by [encrypt]. Never creates a key.
     *
     * @throws KeyUnavailableException if the key does not exist.
     * @throws SecretCipherException if the payload is malformed or was tampered with,
     *   the key is wrong, [associatedData] does not match, or any other failure occurs.
     */
    fun decrypt(payload: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteArray
}
