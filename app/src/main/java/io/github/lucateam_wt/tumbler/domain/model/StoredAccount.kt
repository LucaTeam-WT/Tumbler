package io.github.lucateam_wt.tumbler.domain.model

/**
 * A stored account with its secret decrypted, as read for a backup export or an import plan.
 * Whoever reads it owns [account]'s secret array and should wipe it after use.
 *
 * [toString] prints no id, label or secret.
 *
 * @property id the account's stable identifier.
 * @property createdAtMillis epoch milliseconds when the account was first stored.
 * @throws IllegalArgumentException if [createdAtMillis] is negative.
 */
class StoredAccount(val id: String, val account: OtpAccount, val createdAtMillis: Long) {

    init {
        require(createdAtMillis >= 0) { "Creation time must not be negative" }
    }

    override fun toString(): String = "StoredAccount(type=${account.type}, <redacted>)"
}
