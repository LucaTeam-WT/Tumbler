package com.phucho0237.lucaauth.ui.accounts

/**
 * Groups [code] for easier reading: `123456` becomes `123 456`, `12345678` becomes
 * `1234 5678` and a 7-digit code becomes `123 4567`.
 */
fun formatCode(code: String): String {
    val firstGroup = code.length / 2
    return if (code.length < 4) code else "${code.take(firstGroup)} ${code.drop(firstGroup)}"
}
