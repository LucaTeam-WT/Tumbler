package io.github.lucateam_wt.tumbler.domain.otp

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-based one-time password generator (RFC 4226).
 *
 * The secret is copied on construction, so later changes to the caller's array have
 * no effect. Instances are immutable and safe to share between threads.
 *
 * @param secret raw shared secret (already Base32-decoded); must not be empty.
 * @property algorithm HMAC algorithm; RFC 4226 itself only defines [OtpAlgorithm.SHA1].
 * @property digits number of output digits, in [MIN_DIGITS]..[MAX_DIGITS].
 * @throws IllegalArgumentException if [secret] is empty or [digits] is out of range.
 */
class Hotp(
    secret: ByteArray,
    val algorithm: OtpAlgorithm = OtpAlgorithm.SHA1,
    val digits: Int = DEFAULT_DIGITS,
) {

    private val key: ByteArray = secret.copyOf()

    init {
        require(key.isNotEmpty()) { "Secret must not be empty" }
        require(digits in MIN_DIGITS..MAX_DIGITS) {
            "Digits must be in $MIN_DIGITS..$MAX_DIGITS, was $digits"
        }
    }

    /**
     * Returns the OTP for [counter], zero-padded to [digits] characters.
     *
     * The counter is encoded as an 8-byte big-endian value and the HMAC output is
     * reduced with RFC 4226 dynamic truncation.
     *
     * @throws IllegalArgumentException if [counter] is negative.
     */
    fun generate(counter: Long): String {
        require(counter >= 0) { "Counter must not be negative" }

        val mac = Mac.getInstance(algorithm.macName)
        mac.init(SecretKeySpec(key, algorithm.macName))
        val hash = mac.doFinal(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(counter).array())

        val offset = hash.last().toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)

        return (binary % POWERS_OF_TEN[digits]).toString().padStart(digits, '0')
    }

    companion object {
        const val MIN_DIGITS = 6
        const val MAX_DIGITS = 8
        const val DEFAULT_DIGITS = 6

        private val POWERS_OF_TEN = IntArray(MAX_DIGITS + 1).also {
            it[0] = 1
            for (i in 1..MAX_DIGITS) it[i] = it[i - 1] * 10
        }
    }
}
