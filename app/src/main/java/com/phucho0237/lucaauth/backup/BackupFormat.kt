package com.phucho0237.lucaauth.backup

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Layout of a backup file (format version 1); the normative description is
 * `docs/backup-format.md`. All integers are big-endian.
 *
 * ```
 * offset   size  field
 * 0        8     magic "LUCABKUP" (ASCII)
 * 8        1     format version = 1
 * 9        1     KDF id = 1 (scrypt)
 * 10       1     log2(N)
 * 11       1     r
 * 12       1     p
 * 13       1     salt length S (16..64; written as 32)
 * 14       S     salt
 * 14+S     12    AES-GCM nonce
 * 26+S     ...   AES-256-GCM ciphertext of the payload, then the 128-bit tag
 * ```
 *
 * The key is scrypt(NFC UTF-8 password, salt, N, r, p, 32 bytes). Bytes 0 to 26+S-1 (the
 * whole header) are the GCM associated data.
 */
internal object BackupFormat {
    const val MAGIC = "LUCABKUP"
    const val FORMAT_VERSION = 1
    const val KDF_SCRYPT = 1
    const val SALT_LENGTH = 32
    const val MIN_SALT_LENGTH = 16
    const val MAX_SALT_LENGTH = 64
    const val NONCE_LENGTH = 12
    const val TAG_LENGTH = 16
    const val KEY_LENGTH = 32

    /** Files above this size are rejected before anything else is read. */
    const val MAX_FILE_BYTES = 4 * 1024 * 1024

    const val VERSION_OFFSET = 8
    const val KDF_OFFSET = 9
    const val LOG2_N_OFFSET = 10
    const val R_OFFSET = 11
    const val P_OFFSET = 12
    const val SALT_LENGTH_OFFSET = 13
    const val SALT_OFFSET = 14

    /** Header size for a salt of [saltLength] bytes. */
    fun headerLength(saltLength: Int): Int = SALT_OFFSET + saltLength + NONCE_LENGTH

    internal const val TRANSFORMATION = "AES/GCM/NoPadding"
    internal const val TAG_LENGTH_BITS = TAG_LENGTH * 8
}

/**
 * Encrypts [accounts] into a new backup file protected by [password], with the default
 * scrypt parameters ([ScryptParams.DEFAULT]) and a fresh random salt and nonce from
 * [random] on every call.
 *
 * Runs scrypt (about 64 MiB and a noticeable time): call it off the main thread. Does not
 * modify [password] or [accounts]; the caller wipes the password.
 *
 * @throws IllegalArgumentException if [password] fails [validatePassword], or an account
 *   is outside the [BackupPayload] limits. Messages never contain the password, a secret
 *   or a label.
 */
internal fun exportBackup(
    accounts: List<BackupEntry>,
    password: CharArray,
    random: SecureRandom = SecureRandom(),
): ByteArray {
    val salt = ByteArray(BackupFormat.SALT_LENGTH).also(random::nextBytes)
    val nonce = ByteArray(BackupFormat.NONCE_LENGTH).also(random::nextBytes)
    return sealBackup(accounts, password, salt, nonce, ScryptParams.DEFAULT)
}

/**
 * **For tests only.** Production code must only call [exportBackup], which is the one
 * production caller of this function.
 *
 * This is the seam behind [exportBackup]. It takes the salt, nonce and scrypt parameters
 * explicitly, for the golden file and for fast tests. A fixed salt and nonce used with the
 * same password reuse a GCM key and nonce, which breaks AES-GCM's confidentiality and
 * integrity. It is `internal` (not private) only so unit tests in this module can reach it.
 */
internal fun sealBackup(
    accounts: List<BackupEntry>,
    password: CharArray,
    salt: ByteArray,
    nonce: ByteArray,
    params: ScryptParams,
): ByteArray {
    require(validatePassword(password) == PasswordCheck.Ok) { "Password does not meet the backup password policy" }
    require(salt.size in BackupFormat.MIN_SALT_LENGTH..BackupFormat.MAX_SALT_LENGTH) { "Invalid salt length" }
    require(nonce.size == BackupFormat.NONCE_LENGTH) { "Invalid nonce length" }
    require(params.isAllowedInBackup) { "scrypt parameters outside the backup bounds" }

    val payload = BackupPayload.encode(accounts)
    var passwordBytes: ByteArray? = null
    var key: ByteArray? = null
    try {
        passwordBytes = checkNotNull(passwordKdfInput(password)) { "Password has no UTF-8 encoding" }
        val header = encodeHeader(params, salt, nonce)
        key = BouncyCastleScrypt.derive(passwordBytes, salt, params, BackupFormat.KEY_LENGTH)
        val cipher = Cipher.getInstance(BackupFormat.TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(BackupFormat.TAG_LENGTH_BITS, nonce),
        )
        cipher.updateAAD(header)
        val file = header.copyOf(header.size + payload.size + BackupFormat.TAG_LENGTH)
        val written = cipher.doFinal(payload, 0, payload.size, file, header.size)
        check(written == payload.size + BackupFormat.TAG_LENGTH) { "Unexpected ciphertext length" }
        check(file.size <= BackupFormat.MAX_FILE_BYTES) { "Backup too large" }
        return file
    } catch (e: GeneralSecurityException) {
        throw IllegalStateException("Backup encryption failed", e)
    } finally {
        payload.fill(0)
        passwordBytes?.fill(0)
        key?.fill(0)
    }
}

/**
 * Decrypts and validates a backup file that is already in memory; to read from a file or
 * content URI, use the `InputStream` overload, which never buffers more than
 * [BackupFormat.MAX_FILE_BYTES] + 1 bytes. Returns one closed [BackupReadResult] for every
 * [bytes] and [password]. The checks run in this order, so scrypt only runs on a file whose
 * size, magic, versions and parameters are acceptable:
 *
 * 1. larger than [BackupFormat.MAX_FILE_BYTES]: [BackupReadResult.TooLarge];
 * 2. shorter than the magic or a different magic: [BackupReadResult.NotABackup];
 * 3. unknown format version or KDF id: [BackupReadResult.UnsupportedVersion];
 * 4. scrypt parameters or salt length outside the bounds ([ScryptParams.isAllowedInBackup]):
 *    [BackupReadResult.ParametersOutOfBounds];
 * 5. cut short anywhere after the magic, or a [password] with an unpaired surrogate (no
 *    backup can have been written with it): [BackupReadResult.WrongPasswordOrDamaged],
 *    without running scrypt;
 * 6. scrypt cannot allocate its memory (`OutOfMemoryError`): [BackupReadResult.NotEnoughMemory];
 * 7. GCM authentication fails (wrong password or any modified byte):
 *    [BackupReadResult.WrongPasswordOrDamaged];
 * 8. unknown payload version: [BackupReadResult.UnsupportedVersion]; malformed payload or
 *    an invalid account: [BackupReadResult.InvalidPayload].
 *
 * Throws only for faults that no input can cause: `IllegalStateException` if AES-GCM is
 * missing from the platform, or an `OutOfMemoryError` outside scrypt (every other
 * allocation is bounded by the file size limit).
 *
 * Runs scrypt with the file's parameters (at most 64 MiB): call it off the main thread.
 * Does not modify [bytes] or [password].
 */
internal fun readBackup(bytes: ByteArray, password: CharArray): BackupReadResult =
    readBackup(bytes, password, BouncyCastleScrypt)

/**
 * Reads a backup from [input] and decrypts it like the `ByteArray` overload. Requests at
 * most [BackupFormat.MAX_FILE_BYTES] + 1 bytes from [input] in total: as soon as more than
 * [BackupFormat.MAX_FILE_BYTES] bytes have arrived it returns [BackupReadResult.TooLarge]
 * without reading the rest. Never closes [input]; the caller owns it.
 *
 * An `IOException` from [input], or a read that returns 0 bytes (which `InputStream` does
 * not allow for a non-empty request, and would otherwise loop forever), gives
 * [BackupReadResult.ReadFailed]. The bytes read so far are then zeroed and never parsed.
 * Other exceptions from [input] propagate.
 */
internal fun readBackup(input: InputStream, password: CharArray): BackupReadResult =
    readBackup(input, password, BouncyCastleScrypt)

/** The `InputStream` overload of [readBackup] with the KDF as a seam. */
internal fun readBackup(input: InputStream, password: CharArray, kdf: Kdf): BackupReadResult =
    readBounded(input) { bytes -> readBackup(bytes, password, kdf) }

/**
 * The read step of the `InputStream` overload of [readBackup]: reads [input] within
 * [BackupFormat.MAX_FILE_BYTES] (or returns [BackupReadResult.TooLarge] or
 * [BackupReadResult.ReadFailed]), passes the complete file to [parse], and zeroes that
 * array when [parse] returns or throws. A seam so tests can check the wipe.
 */
internal fun readBounded(input: InputStream, parse: (ByteArray) -> BackupReadResult): BackupReadResult {
    val bytes = when (val read = readAtMost(input, BackupFormat.MAX_FILE_BYTES)) {
        BoundedRead.TooLarge -> return BackupReadResult.TooLarge
        BoundedRead.Failed -> return BackupReadResult.ReadFailed
        is BoundedRead.Complete -> read.bytes
    }
    try {
        return parse(bytes)
    } finally {
        bytes.fill(0)
    }
}

/** [readBackup] with the KDF as a seam, so tests can count derivations. */
internal fun readBackup(bytes: ByteArray, password: CharArray, kdf: Kdf): BackupReadResult {
    if (bytes.size > BackupFormat.MAX_FILE_BYTES) return BackupReadResult.TooLarge
    if (!hasMagic(bytes)) return BackupReadResult.NotABackup
    if (bytes.size <= BackupFormat.VERSION_OFFSET) return BackupReadResult.WrongPasswordOrDamaged
    if (bytes.u8(BackupFormat.VERSION_OFFSET) != BackupFormat.FORMAT_VERSION) return BackupReadResult.UnsupportedVersion
    if (bytes.size <= BackupFormat.KDF_OFFSET) return BackupReadResult.WrongPasswordOrDamaged
    if (bytes.u8(BackupFormat.KDF_OFFSET) != BackupFormat.KDF_SCRYPT) return BackupReadResult.UnsupportedVersion
    if (bytes.size < BackupFormat.SALT_OFFSET) return BackupReadResult.WrongPasswordOrDamaged

    val log2N = bytes.u8(BackupFormat.LOG2_N_OFFSET)
    val r = bytes.u8(BackupFormat.R_OFFSET)
    val p = bytes.u8(BackupFormat.P_OFFSET)
    val saltLength = bytes.u8(BackupFormat.SALT_LENGTH_OFFSET)
    // log2N is at most 255; anything above 62 cannot be shifted into a Long and is far
    // above the bound anyway.
    if (log2N > MAX_SHIFTABLE_LOG2_N || !ScryptParams.isAllowedInBackup(1L shl log2N, r, p)) {
        return BackupReadResult.ParametersOutOfBounds
    }
    if (saltLength !in BackupFormat.MIN_SALT_LENGTH..BackupFormat.MAX_SALT_LENGTH) {
        return BackupReadResult.ParametersOutOfBounds
    }
    val headerLength = BackupFormat.headerLength(saltLength)
    if (bytes.size < headerLength + BackupFormat.TAG_LENGTH) return BackupReadResult.WrongPasswordOrDamaged

    val params = ScryptParams(log2N, r, p)
    val salt = bytes.copyOfRange(BackupFormat.SALT_OFFSET, BackupFormat.SALT_OFFSET + saltLength)
    val passwordBytes = passwordKdfInput(password)
        // No backup can have been written with a password that has no UTF-8 encoding.
        ?: return BackupReadResult.WrongPasswordOrDamaged
    var key: ByteArray? = null
    var plaintext: ByteArray? = null
    try {
        key = try {
            kdf.derive(passwordBytes, salt, params, BackupFormat.KEY_LENGTH)
        } catch (e: IllegalArgumentException) {
            // Defense in depth: the bounds above already exclude everything scrypt rejects.
            return BackupReadResult.ParametersOutOfBounds
        } catch (e: OutOfMemoryError) {
            // scrypt's table (at most 64 MiB) did not fit. Bouncy Castle clears the chunks it
            // had already allocated in its own finally block; passwordBytes is wiped below.
            return BackupReadResult.NotEnoughMemory
        }
        check(key.size == BackupFormat.KEY_LENGTH) { "Unexpected key length" }
        val cipher = Cipher.getInstance(BackupFormat.TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(BackupFormat.TAG_LENGTH_BITS, bytes, headerLength - BackupFormat.NONCE_LENGTH, BackupFormat.NONCE_LENGTH),
        )
        cipher.updateAAD(bytes, 0, headerLength)
        plaintext = try {
            cipher.doFinal(bytes, headerLength, bytes.size - headerLength)
        } catch (e: BadPaddingException) {
            // AEADBadTagException is a BadPaddingException: wrong password or modified bytes.
            return BackupReadResult.WrongPasswordOrDamaged
        }
        return when (val decoded = BackupPayload.decode(plaintext)) {
            is BackupPayload.Decoded.Valid -> BackupReadResult.Success(decoded.accounts)
            BackupPayload.Decoded.UnsupportedVersion -> BackupReadResult.UnsupportedVersion
            BackupPayload.Decoded.Invalid -> BackupReadResult.InvalidPayload
        }
    } catch (e: GeneralSecurityException) {
        // Not input-dependent: AES-GCM missing from the platform.
        throw IllegalStateException("Backup decryption unavailable", e)
    } finally {
        passwordBytes.fill(0)
        key?.fill(0)
        plaintext?.fill(0)
    }
}

private const val MAX_SHIFTABLE_LOG2_N = 62

/** First buffer size for [readAtMost]; it doubles up to the limit as data arrives. */
private const val INITIAL_READ_BUFFER_BYTES = 64 * 1024

private sealed interface BoundedRead {
    class Complete(val bytes: ByteArray) : BoundedRead
    data object TooLarge : BoundedRead
    data object Failed : BoundedRead
}

/**
 * Reads [input] to its end if it holds at most [limit] bytes. Never requests more than
 * [limit] + 1 bytes in total, so a longer stream is detected without reading the rest.
 * Every buffer it fills is zeroed before it returns.
 */
private fun readAtMost(input: InputStream, limit: Int): BoundedRead {
    val cap = limit + 1
    var buffer = ByteArray(minOf(INITIAL_READ_BUFFER_BYTES, cap))
    var size = 0
    try {
        while (true) {
            if (size == buffer.size) {
                // Only reached while buffer.size < cap: at cap, size > limit returned below.
                val grown = buffer.copyOf(minOf(buffer.size * 2, cap))
                buffer.fill(0)
                buffer = grown
            }
            val read = input.read(buffer, size, buffer.size - size)
            if (read < 0) return BoundedRead.Complete(buffer.copyOf(size))
            if (read == 0) return BoundedRead.Failed
            size += read
            if (size > limit) return BoundedRead.TooLarge
        }
    } catch (e: IOException) {
        return BoundedRead.Failed
    } finally {
        buffer.fill(0)
    }
}

private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

private fun hasMagic(bytes: ByteArray): Boolean {
    val magic = BackupFormat.MAGIC
    if (bytes.size < magic.length) return false
    for (i in magic.indices) {
        if (bytes[i] != magic[i].code.toByte()) return false
    }
    return true
}

private fun encodeHeader(params: ScryptParams, salt: ByteArray, nonce: ByteArray): ByteArray {
    val header = ByteBuffer.allocate(BackupFormat.headerLength(salt.size))
    header.put(BackupFormat.MAGIC.toByteArray(Charsets.US_ASCII))
    header.put(BackupFormat.FORMAT_VERSION.toByte())
    header.put(BackupFormat.KDF_SCRYPT.toByte())
    header.put(params.log2N.toByte())
    header.put(params.r.toByte())
    header.put(params.p.toByte())
    header.put(salt.size.toByte())
    header.put(salt)
    header.put(nonce)
    check(!header.hasRemaining()) { "Header size mismatch" }
    return header.array()
}
