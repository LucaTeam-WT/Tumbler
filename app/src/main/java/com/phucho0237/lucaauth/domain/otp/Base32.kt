package com.phucho0237.lucaauth.domain.otp

/**
 * Base32 decoder for the RFC 4648 alphabet (`A-Z`, `2-7`), as used by
 * `otpauth://` secrets.
 *
 * Decoding is lenient about formatting commonly found in user-entered secrets:
 * letters are case-insensitive, whitespace and hyphens are ignored, and trailing
 * `=` padding is optional.
 */
object Base32 {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val BITS_PER_CHAR = 5
    private const val BITS_PER_BYTE = 8

    /** Lengths (mod 8) that cannot be produced by encoding a whole number of bytes. */
    private val INVALID_LENGTH_REMAINDERS = setOf(1, 3, 6)

    /**
     * Decodes [input] into raw bytes.
     *
     * @throws IllegalArgumentException if [input] contains a character outside the
     *   Base32 alphabet (including `=` anywhere but the end) or has a length that no
     *   valid encoding can produce. The message never includes the input itself.
     */
    fun decode(input: String): ByteArray {
        val cleaned = input
            .filterNot { it.isWhitespace() || it == '-' }
            .trimEnd('=')
        require(cleaned.length % BITS_PER_BYTE !in INVALID_LENGTH_REMAINDERS) {
            "Invalid Base32 length"
        }

        val output = ByteArray(cleaned.length * BITS_PER_CHAR / BITS_PER_BYTE)
        var buffer = 0
        var bitCount = 0
        var index = 0
        for (char in cleaned) {
            val value = ALPHABET.indexOf(char.uppercaseChar())
            require(value >= 0) { "Invalid Base32 character" }
            buffer = (buffer shl BITS_PER_CHAR) or value
            bitCount += BITS_PER_CHAR
            if (bitCount >= BITS_PER_BYTE) {
                bitCount -= BITS_PER_BYTE
                output[index++] = (buffer shr bitCount).toByte()
                buffer = buffer and ((1 shl bitCount) - 1)
            }
        }
        return output
    }
}
