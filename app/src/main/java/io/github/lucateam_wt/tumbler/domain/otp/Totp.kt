package io.github.lucateam_wt.tumbler.domain.otp

/**
 * Time-based one-time password generator (RFC 6238), built on [Hotp].
 *
 * Uses T0 = 0 (the Unix epoch). The time step counter is
 * `floorDiv(nowSeconds, period)`, so times before the epoch are rejected by [Hotp].
 * Instances are immutable and safe to share between threads.
 *
 * @param secret raw shared secret (already Base32-decoded); must not be empty.
 * @property algorithm HMAC algorithm.
 * @property digits number of output digits, in [Hotp.MIN_DIGITS]..[Hotp.MAX_DIGITS].
 * @property period time step length in seconds; must be positive.
 * @throws IllegalArgumentException if any parameter is invalid.
 */
class Totp(
    secret: ByteArray,
    val algorithm: OtpAlgorithm = OtpAlgorithm.SHA1,
    val digits: Int = Hotp.DEFAULT_DIGITS,
    val period: Int = DEFAULT_PERIOD_SECONDS,
) {

    init {
        require(period > 0) { "Period must be positive, was $period" }
    }

    private val hotp = Hotp(secret, algorithm, digits)

    /**
     * Returns the OTP valid at [nowSeconds] (Unix epoch seconds).
     *
     * @throws IllegalArgumentException if [nowSeconds] is negative.
     */
    fun generate(nowSeconds: Long): String =
        hotp.generate(Math.floorDiv(nowSeconds, period.toLong()))

    /**
     * Returns how many seconds the code for [nowSeconds] stays valid, in `1..period`.
     * At the first second of a time step this equals [period].
     */
    fun secondsRemaining(nowSeconds: Long): Int =
        period - Math.floorMod(nowSeconds, period.toLong()).toInt()

    companion object {
        const val DEFAULT_PERIOD_SECONDS = 30
    }
}
