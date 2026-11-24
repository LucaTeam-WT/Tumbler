package io.github.lucateam_wt.tumbler.domain.otp

import io.github.lucateam_wt.tumbler.domain.model.AccountLimitException
import io.github.lucateam_wt.tumbler.domain.model.AccountLimits
import io.github.lucateam_wt.tumbler.domain.model.OtpAccount
import io.github.lucateam_wt.tumbler.domain.model.OtpType
import java.io.ByteArrayOutputStream

/**
 * Parser for `otpauth://` URIs (Google Authenticator Key URI Format):
 *
 * ```
 * otpauth://totp/Issuer:account?secret=BASE32&issuer=Issuer&algorithm=SHA1&digits=6&period=30
 * otpauth://hotp/Issuer:account?secret=BASE32&counter=0
 * ```
 *
 * Pure Kotlin so it runs on the plain JVM. The label (path) is percent-decoded only,
 * so `+` stays a literal plus sign there. Query parameter values are form-decoded:
 * `+` becomes a space, and a literal plus must be sent as `%2B`.
 */
object OtpAuthUri {

    private const val SCHEME = "otpauth"
    private const val SCHEME_SEPARATOR = "://"
    private const val HEX_RADIX = 16

    /**
     * Parses [uri] into an [OtpAccount].
     *
     * The issuer comes from the `issuer` parameter if it is present and not blank,
     * otherwise from the label prefix before the first `:`. Unknown parameters are
     * ignored; for duplicated parameters the last value wins.
     *
     * The labels are checked before the secret is decoded, and a decoded secret that is
     * rejected (too long, or the account is otherwise invalid) is wiped before the error
     * propagates.
     *
     * @throws AccountLimitException (an [IllegalArgumentException]) if the link is otherwise
     *   valid enough to be read but its issuer or account name is above
     *   [AccountLimits.MAX_LABEL_UTF8_BYTES] UTF-8 bytes, or its secret above
     *   [AccountLimits.MAX_SECRET_BYTES] bytes: the backup format's limits.
     * @throws IllegalArgumentException if [uri] is malformed or has invalid values, including
     *   a label with an unpaired surrogate. No message contains the secret, a label or the URI.
     */
    fun parse(uri: String): OtpAccount = parse(uri, Base32::decode)

    /** [parse] with the Base32 decoder as a seam, so tests can see the array it returns. */
    internal fun parse(uri: String, decodeSecret: (String) -> ByteArray): OtpAccount {
        val text = uri.trim()
        require(text.isNotEmpty()) { "URI is empty" }

        val schemeEnd = text.indexOf(SCHEME_SEPARATOR)
        require(schemeEnd >= 0 && text.substring(0, schemeEnd).equals(SCHEME, ignoreCase = true)) {
            "Not an otpauth URI"
        }
        val rest = text.substring(schemeEnd + SCHEME_SEPARATOR.length).substringBefore('#')
        val path = rest.substringBefore('?')
        val query = rest.substringAfter('?', missingDelimiterValue = "")

        val type = when (path.substringBefore('/').lowercase()) {
            "totp" -> OtpType.TOTP
            "hotp" -> OtpType.HOTP
            else -> throw IllegalArgumentException("Unsupported OTP type")
        }

        val label = percentDecode(path.substringAfter('/', missingDelimiterValue = ""))
        require(label.isNotBlank()) { "Missing label" }
        val labelIssuer = if (':' in label) label.substringBefore(':').trim() else ""
        val accountName = label.substringAfter(':').trim()

        val params = parseQuery(query)

        val secretText = params["secret"]
        require(!secretText.isNullOrBlank()) { "Missing secret" }

        val algorithm = params["algorithm"]?.let { name ->
            OtpAlgorithm.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unsupported algorithm")
        } ?: OtpAlgorithm.SHA1

        val digits = params["digits"]?.let { requireNotNull(it.toIntOrNull()) { "Invalid digits" } }
            ?: Hotp.DEFAULT_DIGITS

        var period = Totp.DEFAULT_PERIOD_SECONDS
        var counter = 0L
        when (type) {
            OtpType.TOTP -> params["period"]?.let {
                period = requireNotNull(it.toIntOrNull()) { "Invalid period" }
            }
            OtpType.HOTP -> {
                val counterText = requireNotNull(params["counter"]) { "Missing counter" }
                counter = requireNotNull(counterText.toLongOrNull()) { "Invalid counter" }
            }
        }

        val issuer = params["issuer"]?.trim()?.takeIf { it.isNotEmpty() } ?: labelIssuer
        // The backup format's limits, so every account added here can be exported. Checked
        // before the secret is decoded, so a rejected link never holds a decoded secret.
        checkLabel(issuer, AccountLimitException.Field.ISSUER)
        checkLabel(accountName, AccountLimitException.Field.ACCOUNT_NAME)

        val secret = decodeSecret(secretText)
        try {
            if (secret.size > AccountLimits.MAX_SECRET_BYTES) {
                throw AccountLimitException(AccountLimitException.Field.SECRET)
            }
            // Ranges (digits, period, counter, non-empty secret) are validated by OtpAccount.
            return OtpAccount(
                issuer = issuer,
                accountName = accountName,
                type = type,
                secret = secret,
                algorithm = algorithm,
                digits = digits,
                period = period,
                counter = counter,
            )
        } catch (t: Throwable) {
            secret.fill(0)
            throw t
        }
    }

    private fun checkLabel(label: String, field: AccountLimitException.Field) {
        when (AccountLimits.checkLabel(label)) {
            AccountLimits.LabelCheck.OK -> Unit
            AccountLimits.LabelCheck.TOO_LONG -> throw AccountLimitException(field)
            AccountLimits.LabelCheck.NOT_VALID_TEXT -> throw IllegalArgumentException("${field.description} is not valid text")
        }
    }

    /**
     * Splits a query string into decoded name/value pairs. Names are lower-cased;
     * in values `+` is decoded as a space.
     */
    private fun parseQuery(query: String): Map<String, String> {
        val params = HashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val name = percentDecode(pair.substringBefore('=')).lowercase()
            val value = pair.substringAfter('=', missingDelimiterValue = "")
            params[name] = percentDecode(value, plusAsSpace = true)
        }
        return params
    }

    /**
     * Decodes `%XX` escapes as UTF-8. `+` becomes a space only if [plusAsSpace] is set;
     * otherwise it is left unchanged.
     */
    private fun percentDecode(text: String, plusAsSpace: Boolean = false): String {
        if ('%' !in text && !(plusAsSpace && '+' in text)) return text
        val result = StringBuilder(text.length)
        val pending = ByteArrayOutputStream()
        fun flush() {
            if (pending.size() > 0) {
                result.append(String(pending.toByteArray(), Charsets.UTF_8))
                pending.reset()
            }
        }

        var i = 0
        while (i < text.length) {
            val char = text[i]
            if (char == '%') {
                require(i + 2 < text.length) { "Invalid percent-encoding" }
                val high = Character.digit(text[i + 1], HEX_RADIX)
                val low = Character.digit(text[i + 2], HEX_RADIX)
                require(high >= 0 && low >= 0) { "Invalid percent-encoding" }
                pending.write((high shl 4) or low)
                i += 3
            } else {
                flush()
                result.append(if (plusAsSpace && char == '+') ' ' else char)
                i++
            }
        }
        flush()
        return result.toString()
    }
}
