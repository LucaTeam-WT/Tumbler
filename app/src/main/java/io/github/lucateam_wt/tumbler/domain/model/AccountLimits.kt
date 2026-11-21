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

    /**
     * True if [label] is well-formed UTF-16 (no unpaired surrogate, so it has an exact UTF-8
     * encoding) and that encoding is at most [MAX_LABEL_UTF8_BYTES] bytes. It counts
     * without encoding the label.
     */
    fun isLabelWithinLimits(label: String): Boolean {
        var bytes = 0
        var i = 0
        while (i < label.length) {
            val char = label[i]
            bytes += when {
                char.code < 0x80 -> 1
                char.code < 0x800 -> 2
                Character.isHighSurrogate(char) -> {
                    if (i + 1 >= label.length || !Character.isLowSurrogate(label[i + 1])) return false
                    i++
                    4
                }
                Character.isLowSurrogate(char) -> return false
                else -> 3
            }
            if (bytes > MAX_LABEL_UTF8_BYTES) return false
            i++
        }
        return true
    }
}
