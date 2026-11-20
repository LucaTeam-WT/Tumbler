package com.phucho0237.lucaauth.backup

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.text.Normalizer

/** Minimum length of an export password, in Unicode code points after NFC normalization. */
internal const val MIN_PASSWORD_CHARACTERS = 12

/**
 * Maximum length of any password, in Unicode code points after NFC normalization: a new
 * export password above it is [PasswordCheck.TooLong], and [passwordKdfInput] has no result
 * for one (so a reader reports `WrongPasswordOrDamaged` before scrypt runs). It bounds every
 * buffer derived from a password.
 */
internal const val MAX_PASSWORD_CHARACTERS = 1024

/**
 * The most code points the canonical decomposition of one code point has (U+1F82, for
 * example, is U+03B1 U+0313 U+0300 U+0345), so the most code points NFC can compose into
 * one. `BackupPasswordTest` checks it against every code point.
 */
internal const val MAX_CANONICAL_DECOMPOSITION_LENGTH = 4

/**
 * Longest input, in UTF-16 units, that can still have at most [MAX_PASSWORD_CHARACTERS]
 * code points after NFC: NFC composes at most [MAX_CANONICAL_DECOMPOSITION_LENGTH] code
 * points into one, and a code point takes at most 2 UTF-16 units. A longer input is
 * rejected before it is copied or normalized.
 */
internal const val MAX_PASSWORD_INPUT_UTF16_UNITS = MAX_PASSWORD_CHARACTERS * MAX_CANONICAL_DECOMPOSITION_LENGTH * 2

/** Outcome of checking a new export password. Never contains the password. */
internal sealed interface PasswordCheck {
    data object Ok : PasswordCheck

    /** Fewer than [minimumCharacters] characters. */
    data class TooShort(val minimumCharacters: Int) : PasswordCheck

    /** More than [maximumCharacters] characters. */
    data class TooLong(val maximumCharacters: Int) : PasswordCheck

    /** Contains an unpaired UTF-16 surrogate, so it has no UTF-8 encoding. */
    data object InvalidCharacters : PasswordCheck

    /** The confirmation differs from the password. */
    data object Mismatch : PasswordCheck
}

/**
 * Checks a new export password: at least [MIN_PASSWORD_CHARACTERS] and at most
 * [MAX_PASSWORD_CHARACTERS] characters, counted as Unicode code points after NFC
 * normalization (so "ệ" counts once however it was typed and an emoji outside the BMP
 * counts once), and no unpaired surrogate. There is no other rule.
 *
 * An input longer than [MAX_PASSWORD_INPUT_UTF16_UNITS] is [PasswordCheck.TooLong] at once,
 * without being copied or normalized (even if it also holds an unpaired surrogate).
 *
 * Does not modify [password]; the caller wipes it.
 */
internal fun validatePassword(password: CharArray): PasswordCheck {
    if (password.size > MAX_PASSWORD_INPUT_UTF16_UNITS) return PasswordCheck.TooLong(MAX_PASSWORD_CHARACTERS)
    val normalized = normalizePassword(password)
    try {
        if (!isWellFormedUtf16(normalized)) return PasswordCheck.InvalidCharacters
        val characters = Character.codePointCount(normalized, 0, normalized.size)
        return when {
            characters < MIN_PASSWORD_CHARACTERS -> PasswordCheck.TooShort(MIN_PASSWORD_CHARACTERS)
            characters > MAX_PASSWORD_CHARACTERS -> PasswordCheck.TooLong(MAX_PASSWORD_CHARACTERS)
            else -> PasswordCheck.Ok
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
 * holds an unpaired surrogate (no UTF-8 encoding exists) or is longer than
 * [MAX_PASSWORD_CHARACTERS] code points after NFC (an input longer than
 * [MAX_PASSWORD_INPUT_UTF16_UNITS] is refused before it is copied). No backup can have been
 * written with such a password.
 *
 * Returns a new array (at most 4 bytes per code point, so at most 4 KiB) the caller must
 * wipe. Does not modify [password]. Which passwords pass through an immutable `String`
 * that cannot be wiped is described at [normalizePassword].
 */
internal fun passwordKdfInput(password: CharArray): ByteArray? {
    if (password.size > MAX_PASSWORD_INPUT_UTF16_UNITS) return null
    val normalized = normalizePassword(password)
    try {
        if (!isWellFormedUtf16(normalized)) return null
        if (Character.codePointCount(normalized, 0, normalized.size) > MAX_PASSWORD_CHARACTERS) return null
        return encodeUtf8OrNull(normalized)
    } finally {
        normalized.fill(WIPE_CHAR)
    }
}

/**
 * Strict UTF-8 encoding of [chars] into a new exact-size array the caller must wipe, or null
 * unless the encoder reports complete success. Every coder result other than underflow
 * fails: malformed input, and also overflow (the buffer is full), which `isError` does not
 * count, so a too-small buffer can never yield a truncated password.
 *
 * [capacity] is a seam for tests. The default, 3 bytes per UTF-16 unit, always suffices
 * (a surrogate pair needs 4 bytes for 2 units). It is computed as a `Long` and a capacity
 * outside `0..Int.MAX_VALUE` gives null without allocating, so no size can overflow an `Int`.
 * The intermediate buffer is wiped before returning.
 */
internal fun encodeUtf8OrNull(
    chars: CharArray,
    capacity: Long = chars.size.toLong() * MAX_UTF8_BYTES_PER_UTF16_UNIT,
): ByteArray? {
    if (capacity < 0 || capacity > Int.MAX_VALUE) return null
    val buffer = ByteArray(capacity.toInt())
    try {
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val out = ByteBuffer.wrap(buffer)
        val input = CharBuffer.wrap(chars)
        if (!encoder.encode(input, out, true).isUnderflow || input.hasRemaining()) return null
        if (!encoder.flush(out).isUnderflow) return null
        return buffer.copyOf(out.position())
    } finally {
        buffer.fill(0)
    }
}

/**
 * Returns a new NFC-normalized copy of [password] that the caller must wipe.
 *
 * Fast path: if every char is below U+0300 the password is already NFC (no such character
 * has a decomposition, a non-zero combining class or a composition with another such
 * character; `BackupPasswordTest` checks every one and every pair), so it is copied
 * without calling `java.text.Normalizer` and never becomes a `String`. That covers ASCII,
 * Latin-1, Latin Extended-A and -B, IPA and the spacing modifier letters.
 *
 * Any other password goes to [slowPath], by default `java.text.Normalizer`:
 * - `Normalizer.isNormalized` turns the password into an immutable `String` on Android
 *   8.0 to 11 (API 26 to 30: the platform calls `src.toString()`); from Android 12
 *   (API 31) the platform hands the `CharBuffer` to ICU without converting it;
 * - a password that is not already NFC is normalized by `Normalizer.normalize`, which
 *   returns a `String` on every version.
 */
internal fun normalizePassword(
    password: CharArray,
    slowPath: (CharArray) -> CharArray = ::normalizeWithJavaText,
): CharArray {
    if (password.all { it < NFC_FAST_PATH_LIMIT }) return password.copyOf()
    return slowPath(password)
}

private fun normalizeWithJavaText(password: CharArray): CharArray {
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

/** Every char below this (U+0300 COMBINING GRAVE ACCENT, the first combining mark) is NFC-stable. */
internal const val NFC_FAST_PATH_LIMIT = '\u0300'
