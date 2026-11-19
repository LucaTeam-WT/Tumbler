package com.phucho0237.lucaauth.backup

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.text.Normalizer

/** Minimum length of an export password, in Unicode code points after NFC normalization. */
internal const val MIN_PASSWORD_CHARACTERS = 12

/** Outcome of checking a new export password. Never contains the password. */
internal sealed interface PasswordCheck {
    data object Ok : PasswordCheck

    /** Fewer than [minimumCharacters] characters. */
    data class TooShort(val minimumCharacters: Int) : PasswordCheck

    /** Contains an unpaired UTF-16 surrogate, so it has no UTF-8 encoding. */
    data object InvalidCharacters : PasswordCheck

    /** The confirmation differs from the password. */
    data object Mismatch : PasswordCheck
}

/**
 * Checks a new export password: at least [MIN_PASSWORD_CHARACTERS] characters, counted as
 * Unicode code points after NFC normalization (so "ệ" counts once however it was typed and
 * an emoji outside the BMP counts once). There is no other rule.
 *
 * Does not modify [password]; the caller wipes it.
 */
internal fun validatePassword(password: CharArray): PasswordCheck {
    val normalized = normalizePassword(password)
    try {
        if (!isWellFormedUtf16(normalized)) return PasswordCheck.InvalidCharacters
        val characters = Character.codePointCount(normalized, 0, normalized.size)
        return if (characters < MIN_PASSWORD_CHARACTERS) {
            PasswordCheck.TooShort(MIN_PASSWORD_CHARACTERS)
        } else {
            PasswordCheck.Ok
        }
    } finally {
        normalized.fill(WIPE_CHAR)
    }
}

/**
 * Checks a new export password and its confirmation: first [validatePassword] on
 * [password], then whether [confirmation] produces the same scrypt input (same NFC
 * normalized UTF-8 bytes), compared in constant time.
 *
 * Does not modify either array; the caller wipes them.
 */
internal fun validatePasswordConfirmation(password: CharArray, confirmation: CharArray): PasswordCheck {
    val check = validatePassword(password)
    if (check != PasswordCheck.Ok) return check
    val first = passwordKdfInput(password)
    val second = passwordKdfInput(confirmation)
    try {
        val matches = first != null && second != null && MessageDigest.isEqual(first, second)
        return if (matches) PasswordCheck.Ok else PasswordCheck.Mismatch
    } finally {
        first?.fill(0)
        second?.fill(0)
    }
}

/**
 * The bytes scrypt receives as its password: [password] normalized to Unicode NFC, then
 * encoded as UTF-8 without a byte order mark or terminator. Returns null if [password]
 * holds an unpaired surrogate (no UTF-8 encoding exists).
 *
 * Returns a new array the caller must wipe. Does not modify [password]. If [password] is
 * not already in NFC, normalization goes through an immutable `String` that cannot be
 * wiped; an already normalized password (all ASCII passwords, for example) never does.
 */
internal fun passwordKdfInput(password: CharArray): ByteArray? {
    val normalized = normalizePassword(password)
    // UTF-8 needs at most 3 bytes per UTF-16 unit (a surrogate pair needs 4 for 2 units),
    // so one exact-capacity buffer is enough and the encoder never reallocates.
    val buffer = ByteArray(normalized.size * MAX_UTF8_BYTES_PER_UTF16_UNIT)
    try {
        if (!isWellFormedUtf16(normalized)) return null
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val out = ByteBuffer.wrap(buffer)
        val input = CharBuffer.wrap(normalized)
        if (encoder.encode(input, out, true).isError || encoder.flush(out).isError) return null
        return buffer.copyOf(out.position())
    } finally {
        normalized.fill(WIPE_CHAR)
        buffer.fill(0)
    }
}

/** Returns a new NFC-normalized copy of [password] that the caller must wipe. */
private fun normalizePassword(password: CharArray): CharArray {
    val view = CharBuffer.wrap(password)
    if (Normalizer.isNormalized(view, Normalizer.Form.NFC)) return password.copyOf()
    return Normalizer.normalize(view, Normalizer.Form.NFC).toCharArray()
}

/** True if every surrogate in [chars] is part of a high-low pair. */
private fun isWellFormedUtf16(chars: CharArray): Boolean {
    var i = 0
    while (i < chars.size) {
        val c = chars[i]
        if (Character.isHighSurrogate(c)) {
            if (i + 1 >= chars.size || !Character.isLowSurrogate(chars[i + 1])) return false
            i += 2
        } else {
            if (Character.isLowSurrogate(c)) return false
            i++
        }
    }
    return true
}

private const val MAX_UTF8_BYTES_PER_UTF16_UNIT = 3
private const val WIPE_CHAR = '\u0000'
