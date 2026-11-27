package io.github.lucateam_wt.tumbler.settings

/**
 * The app's theme as the user chose it in Settings.
 *
 * @property storedValue how the choice is written to the settings file; stable across renames.
 */
enum class ThemeMode(val storedValue: String) {
    /** Follow the system's dark theme setting (the default). */
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    /** Whether the app uses its dark color scheme, given whether the system is in dark theme now. */
    fun useDarkTheme(systemInDarkTheme: Boolean): Boolean = when (this) {
        SYSTEM -> systemInDarkTheme
        LIGHT -> false
        DARK -> true
    }

    companion object {
        /** The choice for a stored value; a missing or unknown value means [SYSTEM]. */
        fun fromStoredValue(value: String?): ThemeMode = entries.firstOrNull { it.storedValue == value } ?: SYSTEM
    }
}
