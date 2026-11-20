package io.github.lucateam_wt.tumbler.backup

import io.github.lucateam_wt.tumbler.domain.model.OtpAccount
import io.github.lucateam_wt.tumbler.domain.model.OtpType
import io.github.lucateam_wt.tumbler.domain.otp.OtpAlgorithm
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Why an account cannot be written to a backup. A closed set, so the UI can say why. */
internal enum class AccountRejection {
    /**
     * More than [BackupPayload.MAX_ACCOUNTS] accounts. The reported index is
     * [BackupPayload.MAX_ACCOUNTS], the first account that does not fit.
     */
    TOO_MANY_ACCOUNTS,

    /** The issuer or account name has leading or trailing whitespace ([BackupPayload.isCanonicalLabel]). */
    LABEL_NOT_TRIMMED,

    /** The issuer or account name contains an unpaired UTF-16 surrogate, so it has no UTF-8 encoding. */
    LABEL_UNPAIRED_SURROGATE,

    /** The issuer or account name is longer than [BackupPayload.MAX_LABEL_BYTES] UTF-8 bytes. */
    LABEL_TOO_LONG,

    /** The secret is longer than [BackupPayload.MAX_SECRET_BYTES] bytes. */
    SECRET_TOO_LONG,
}

/**
 * Account [accountIndex] (zero-based, in the order given to the writer) cannot be written,
 * for [reason]; the whole export fails and nothing is written. Only the first problem is
 * reported: accounts are checked in order, and for each account the issuer, then the account
 * name, then the secret.
 *
 * The message holds the index, the reason and the field name ("issuer", "account name" or
 * "secret") only, never a label or a secret.
 */
internal class AccountRejectedException(
    val accountIndex: Int,
    val reason: AccountRejection,
    field: String? = null,
) : IllegalArgumentException("Account $accountIndex: $reason" + if (field == null) "" else " ($field)")

/**
 * The plaintext inside a backup (payload version 1), a deterministic length-prefixed
 * big-endian encoding; see `docs/backup-format.md`.
 *
 * ```
 * u8 version = 1, i32 count, then per account:
 * u8 type, u8 algorithm, u8 digits, i32 period, i64 counter, i64 createdAt,
 * u16 + UTF-8 issuer, u16 + UTF-8 account name, u16 + raw secret
 * ```
 *
 * Decoding fails closed: one invalid account rejects the whole payload. Accounts must
 * pass [OtpAccount]'s checks (the same ones the `otpauth://` parser relies on), have
 * labels in the form the parser stores them ([isCanonicalLabel]) and stay within the
 * limits below.
 */
internal object BackupPayload {

    const val VERSION = 1
    const val MAX_ACCOUNTS = 1000
    const val MAX_LABEL_BYTES = 1024
    const val MAX_SECRET_BYTES = 1024

    private const val TYPE_TOTP = 1
    private const val TYPE_HOTP = 2
    private const val ALGORITHM_SHA1 = 1
    private const val ALGORITHM_SHA256 = 2
    private const val ALGORITHM_SHA512 = 3

    /** Version byte and account count. */
    private const val HEADER_BYTES = 1 + Int.SIZE_BYTES

    /** Type, algorithm and digits; period; counter; createdAt; three u16 lengths. */
    private const val FIXED_RECORD_BYTES = 3 + Int.SIZE_BYTES + 2 * Long.SIZE_BYTES + 3 * Short.SIZE_BYTES

    /** Largest payload the limits allow. */
    const val MAX_PAYLOAD_BYTES =
        HEADER_BYTES + MAX_ACCOUNTS * (FIXED_RECORD_BYTES + 2 * MAX_LABEL_BYTES + MAX_SECRET_BYTES)

    sealed interface Decoded {
        /** [toString] prints only the count. */
        class Valid(val accounts: List<BackupEntry>) : Decoded {
            override fun toString(): String = "Valid(accounts=${accounts.size})"
        }

        data object Invalid : Decoded

        data object UnsupportedVersion : Decoded
    }

    /**
     * Whether [label] (an issuer or account name) is in the form the `otpauth://` parser
     * stores labels: no leading or trailing whitespace, as Kotlin's `String.trim()` defines
     * it (the parser trims both labels with it). Empty is allowed: the parser produces an
     * empty issuer, an empty account name, or both (for the label `:`).
     *
     * A backup never changes a label: the writer refuses a label that is not canonical and
     * the reader rejects the whole payload if one appears, so an imported " GitHub" can
     * neither become "GitHub" silently nor be stored next to it without matching.
     */
    fun isCanonicalLabel(label: String): Boolean = label == label.trim()

    /**
     * Encodes [accounts] into a new array that the caller must wipe.
     *
     * @throws AccountRejectedException (an `IllegalArgumentException`) naming the first
     *   account that cannot be written and why ([AccountRejection]): more than
     *   [MAX_ACCOUNTS] accounts, a label with leading or trailing whitespace
     *   ([isCanonicalLabel]), a label that is not valid Unicode (an unpaired surrogate), a
     *   label longer than [MAX_LABEL_BYTES] UTF-8 bytes, or a secret longer than
     *   [MAX_SECRET_BYTES]. The message names the account index, reason and field only.
     */
    fun encode(accounts: List<BackupEntry>): ByteArray {
        if (accounts.size > MAX_ACCOUNTS) throw AccountRejectedException(MAX_ACCOUNTS, AccountRejection.TOO_MANY_ACCOUNTS)
        val issuers = ArrayList<ByteArray>(accounts.size)
        val names = ArrayList<ByteArray>(accounts.size)
        var size = HEADER_BYTES
        accounts.forEachIndexed { index, entry ->
            val account = entry.account
            val issuer = encodeLabel(account.issuer, index, "issuer")
            val name = encodeLabel(account.accountName, index, "account name")
            if (account.secret.size > MAX_SECRET_BYTES) {
                throw AccountRejectedException(index, AccountRejection.SECRET_TOO_LONG, "secret")
            }
            issuers += issuer
            names += name
            size += FIXED_RECORD_BYTES + issuer.size + name.size + account.secret.size
        }

        // Exact size, so there is no hidden copy of the secrets from a growing buffer.
        val out = ByteBuffer.allocate(size)
        out.put(VERSION.toByte())
        out.putInt(accounts.size)
        accounts.forEachIndexed { index, entry ->
            val account = entry.account
            out.put(typeCode(account.type).toByte())
            out.put(algorithmCode(account.algorithm).toByte())
            out.put(account.digits.toByte())
            out.putInt(account.period)
            out.putLong(account.counter)
            out.putLong(entry.createdAtMillis)
            putField(out, issuers[index])
            putField(out, names[index])
            putField(out, account.secret)
        }
        check(!out.hasRemaining()) { "Payload size mismatch" }
        return out.array()
    }

    /**
     * Decodes [payload]. Does not modify or keep [payload]; the secrets in the result are
     * new arrays. On failure, the secrets decoded so far are wiped.
     */
    fun decode(payload: ByteArray): Decoded {
        val input = DataInputStream(ByteArrayInputStream(payload))
        val accounts = ArrayList<BackupEntry>()
        var valid = false
        try {
            if (payload.isEmpty()) return Decoded.Invalid
            if (input.readUnsignedByte() != VERSION) return Decoded.UnsupportedVersion
            val count = input.readInt()
            if (count !in 0..MAX_ACCOUNTS) return Decoded.Invalid
            repeat(count) {
                accounts += readEntry(input) ?: return Decoded.Invalid
            }
            if (input.available() != 0) return Decoded.Invalid
            valid = true
            return Decoded.Valid(accounts)
        } catch (e: EOFException) {
            return Decoded.Invalid
        } finally {
            if (!valid) accounts.forEach { it.account.secret.fill(0) }
        }
    }

    /** Reads one account, or returns null if it is malformed or invalid. */
    private fun readEntry(input: DataInputStream): BackupEntry? {
        val type = typeOf(input.readUnsignedByte()) ?: return null
        val algorithm = algorithmOf(input.readUnsignedByte()) ?: return null
        val digits = input.readUnsignedByte()
        val period = input.readInt()
        val counter = input.readLong()
        val createdAt = input.readLong()
        val issuer = readLabel(input) ?: return null
        val accountName = readLabel(input) ?: return null
        val secretLength = input.readUnsignedShort()
        if (secretLength !in 1..MAX_SECRET_BYTES) return null
        val secret = ByteArray(secretLength)
        try {
            input.readFully(secret)
            // OtpAccount enforces digits, period, counter and a non-empty secret, exactly as
            // for a parsed otpauth:// URI; BackupEntry enforces createdAt >= 0.
            return BackupEntry(
                OtpAccount(issuer, accountName, type, secret, algorithm, digits, period, counter),
                createdAt,
            )
        } catch (e: IllegalArgumentException) {
            secret.fill(0)
            return null
        } catch (e: EOFException) {
            secret.fill(0)
            throw e
        }
    }

    /** Reads a label, or returns null if it is too long, not strict UTF-8 or not canonical. */
    private fun readLabel(input: DataInputStream): String? {
        val length = input.readUnsignedShort()
        if (length > MAX_LABEL_BYTES) return null
        val bytes = ByteArray(length)
        input.readFully(bytes)
        val label = try {
            strictUtf8Decoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            return null
        }
        return label.takeIf(::isCanonicalLabel)
    }

    private fun encodeLabel(label: String, index: Int, field: String): ByteArray {
        if (!isCanonicalLabel(label)) throw AccountRejectedException(index, AccountRejection.LABEL_NOT_TRIMMED, field)
        val encoded = try {
            strictUtf8Encoder().encode(CharBuffer.wrap(label))
        } catch (e: CharacterCodingException) {
            // The only malformed input a String can hold is an unpaired surrogate.
            throw AccountRejectedException(index, AccountRejection.LABEL_UNPAIRED_SURROGATE, field)
        }
        val bytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
        if (bytes.size > MAX_LABEL_BYTES) throw AccountRejectedException(index, AccountRejection.LABEL_TOO_LONG, field)
        return bytes
    }

    private fun putField(out: ByteBuffer, bytes: ByteArray) {
        out.putShort(bytes.size.toShort())
        out.put(bytes)
    }

    private fun strictUtf8Encoder() = Charsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)

    private fun strictUtf8Decoder() = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)

    // Explicit codes, never enum ordinals, so reordering an enum cannot change the format.

    private fun typeCode(type: OtpType): Int = when (type) {
        OtpType.TOTP -> TYPE_TOTP
        OtpType.HOTP -> TYPE_HOTP
    }

    private fun typeOf(code: Int): OtpType? = when (code) {
        TYPE_TOTP -> OtpType.TOTP
        TYPE_HOTP -> OtpType.HOTP
        else -> null
    }

    private fun algorithmCode(algorithm: OtpAlgorithm): Int = when (algorithm) {
        OtpAlgorithm.SHA1 -> ALGORITHM_SHA1
        OtpAlgorithm.SHA256 -> ALGORITHM_SHA256
        OtpAlgorithm.SHA512 -> ALGORITHM_SHA512
    }

    private fun algorithmOf(code: Int): OtpAlgorithm? = when (code) {
        ALGORITHM_SHA1 -> OtpAlgorithm.SHA1
        ALGORITHM_SHA256 -> OtpAlgorithm.SHA256
        ALGORITHM_SHA512 -> OtpAlgorithm.SHA512
        else -> null
    }
}
