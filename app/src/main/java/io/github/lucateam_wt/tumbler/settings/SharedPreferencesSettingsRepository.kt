package io.github.lucateam_wt.tumbler.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [SettingsRepository] in one private [SharedPreferences] file ([FILE_NAME]). The file is
 * read once, when this object is made; a change is published at once and written to disk in the
 * background (`apply`). It holds display preferences only, never a secret, and is excluded from
 * cloud backup and device transfer like every other app file (`backup_rules.xml` and `data_extraction_rules.xml` in `res/xml`).
 */
class SharedPreferencesSettingsRepository(private val preferences: SharedPreferences) : SettingsRepository {

    private val theme = MutableStateFlow(ThemeMode.fromStoredValue(preferences.getString(KEY_THEME, null)))

    override val themeMode: StateFlow<ThemeMode> = theme.asStateFlow()

    override fun setThemeMode(mode: ThemeMode) {
        preferences.edit { putString(KEY_THEME, mode.storedValue) }
        theme.value = mode
    }

    companion object {
        /** Name of the settings file in the app's private storage. */
        const val FILE_NAME = "tumbler_settings"

        internal const val KEY_THEME = "theme"
    }
}
