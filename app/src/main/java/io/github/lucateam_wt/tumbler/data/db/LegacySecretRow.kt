package io.github.lucateam_wt.tumbler.data.db

/**
 * Id and encrypted secret of a row in the legacy format, read by [AccountDao.legacyRows].
 * Not a data class: [secretBlob] is an array, and [toString] prints neither field.
 */
class LegacySecretRow(val id: String, val secretBlob: ByteArray) {

    override fun toString(): String = "LegacySecretRow(<redacted>)"
}
