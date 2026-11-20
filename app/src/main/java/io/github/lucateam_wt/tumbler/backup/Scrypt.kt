package io.github.lucateam_wt.tumbler.backup

import org.bouncycastle.crypto.generators.SCrypt

/**
 * scrypt cost parameters (RFC 7914). N is held as its base-2 logarithm, so it is always
 * a power of two.
 *
 * The constructor accepts any value scrypt itself accepts (so the RFC 7914 test vectors
 * can be expressed); whether a backup file may use the parameters is decided separately
 * by [isAllowedInBackup], which every reader must call before running scrypt.
 *
 * @property log2N base-2 logarithm of the CPU/memory cost N.
 * @property r block size.
 * @property p parallelization.
 */
internal data class ScryptParams(val log2N: Int, val r: Int, val p: Int) {

    init {
        require(log2N in 1..MAX_REPRESENTABLE_LOG2_N) { "log2(N) out of range" }
        require(r >= 1) { "r must be positive" }
        require(p >= 1) { "p must be positive" }
    }

    /** The cost parameter N = 2^[log2N]. */
    val n: Int get() = 1 shl log2N

    /** Whether a backup file may use these parameters; see [isAllowedInBackup]. */
    val isAllowedInBackup: Boolean get() = isAllowedInBackup(1L shl log2N, r, p)

    companion object {
        private const val MAX_REPRESENTABLE_LOG2_N = 30

        /** Parameters written by [exportBackup]: N = 2^16, r = 8, p = 2, about 64 MiB. */
        val DEFAULT = ScryptParams(log2N = 16, r = 8, p = 2)

        const val MIN_LOG2_N = 14
        const val MAX_LOG2_N = 16
        const val MAX_R = 8
        const val MAX_P = 4

        /**
         * Upper bound on the memory scrypt needs, estimated as 128 * N * r bytes: 64 MiB,
         * exactly what [DEFAULT] needs.
         */
        const val MAX_MEMORY_BYTES = 64L * 1024 * 1024

        /**
         * Hard bounds for parameters read from a backup file, checked before scrypt runs so
         * that a crafted file cannot exhaust memory or CPU: N a power of two with
         * 2^14 <= N <= 2^16, 1 <= r <= 8, 1 <= p <= 4 and 128 * N * r <= 64 MiB. The writer
         * uses [DEFAULT], which sits at the N, r and memory bounds, so a reader never needs
         * more than a writer did. Within the N and r bounds the memory check cannot fail; it
         * is kept so that loosening one of them cannot silently raise the memory cap.
         *
         * Also N < 2^(16 * r), which RFC 7914 requires and [SCrypt.generate] enforces by
         * throwing; within the other bounds it only excludes r = 1 with N = 2^16.
         */
        fun isAllowedInBackup(n: Long, r: Int, p: Int): Boolean {
            if (n < 1L shl MIN_LOG2_N || n > 1L shl MAX_LOG2_N) return false
            if (n and (n - 1) != 0L) return false
            if (r !in 1..MAX_R || p !in 1..MAX_P) return false
            // RFC 7914 N < 2^(16 * r); for r >= 2 that exceeds every allowed N.
            if (r == 1 && n >= 1L shl 16) return false
            return 128L * n * r <= MAX_MEMORY_BYTES
        }
    }
}

/**
 * Password-based key derivation. A seam so tests can count calls and prove that bad
 * parameters are rejected before any derivation runs.
 */
internal fun interface Kdf {

    /**
     * Derives [keyLength] bytes from [password] and [salt]. Must not modify or keep the
     * inputs; the caller wipes them and the returned array.
     */
    fun derive(password: ByteArray, salt: ByteArray, params: ScryptParams, keyLength: Int): ByteArray
}

/**
 * scrypt from Bouncy Castle (`org.bouncycastle.crypto.generators.SCrypt`). Only
 * [SCrypt.generate] is used; the Bouncy Castle JCA provider is not registered.
 */
internal object BouncyCastleScrypt : Kdf {
    override fun derive(password: ByteArray, salt: ByteArray, params: ScryptParams, keyLength: Int): ByteArray =
        SCrypt.generate(password, salt, params.n, params.r, params.p, keyLength)
}
