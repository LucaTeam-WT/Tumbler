package io.github.lucateam_wt.tumbler.domain.model

/**
 * Size limits for an account the app creates. They are the backup format's limits
 * (`docs/backup-format.md`), so every account added through the app can be exported.
 * Accounts stored before these limits existed are not checked here; an export reports them
 * by id instead.
 */
object AccountLimits {

    /** Most UTF-8 bytes in an issuer or in an account name. */
    const val MAX_LABEL_UTF8_BYTES = 1024

    /** Most bytes in a decoded secret. */
    const val MAX_SECRET_BYTES = 1024

    /** What [checkLabel] found. */
    enum class LabelCheck {
        /** Well-formed text of at most [MAX_LABEL_UTF8_BYTES] UTF-8 bytes. */
        OK,

        /** Well-formed text, but its UTF-8 encoding is longer than [MAX_LABEL_UTF8_BYTES] bytes. */
        TOO_LONG,

        /** Holds an unpaired surrogate, so it has no UTF-8 encoding (whatever its length). */
        NOT_VALID_TEXT,
    }

    /**
     * Checks [label] against the backup's label rules without encoding it: [LabelCheck.OK] if
     * it is well-formed UTF-16 (no unpaired surrogate, so it has an exact UTF-8 encoding) and
     * that encoding is at most [MAX_LABEL_UTF8_BYTES] bytes. A label that is both too long and
     * not valid text is [LabelCheck.NOT_VALID_TEXT].
     */
    fun checkLabel(label: String): LabelCheck {
        var bytes = 0
        var tooLong = false
        var i = 0
        while (i < label.length) {
            val char = label[i]
            val width = when {
                char.code < 0x80 -> 1
                char.code < 0x800 -> 2
                Character.isHighSurrogate(char) -> {
                    if (i + 1 >= label.length || !Character.isLowSurrogate(label[i + 1])) return LabelCheck.NOT_VALID_TEXT
                    i++
                    4
                }
                Character.isLowSurrogate(char) -> return LabelCheck.NOT_VALID_TEXT
                else -> 3
            }
            // Stop counting once over the limit (so the sum cannot overflow), but keep looking
            // for an unpaired surrogate.
            if (!tooLong) {
                bytes += width
                tooLong = bytes > MAX_LABEL_UTF8_BYTES
            }
            i++
        }
        return if (tooLong) LabelCheck.TOO_LONG else LabelCheck.OK
    }

    /** True if [checkLabel] finds [label] [LabelCheck.OK]. */
    fun isLabelWithinLimits(label: String): Boolean = checkLabel(label) == LabelCheck.OK
}

/**
 * An `otpauth://` link holds a [field] larger than [AccountLimits] (the backup format's
 * limits), so the account is not added: every account the app creates must fit in a backup.
 * The message names only the field, never its value.
 */
class AccountLimitException(val field: Field) : IllegalArgumentException("${field.description} is too long for a backup") {

    enum class Field(internal val description: String) {
        ISSUER("Issuer"),
        ACCOUNT_NAME("Account name"),
        SECRET("Secret"),
    }
}
