package io.github.lucateam_wt.tumbler.settings

import kotlinx.coroutines.flow.StateFlow

/**
 * The user's app settings. Holds no secret and nothing about accounts: only display preferences.
 * Future settings (app lock, clipboard clearing) get their own property and setter here.
 */
interface SettingsRepository {

    /** The theme choice; updated at once by [setThemeMode]. */
    val themeMode: StateFlow<ThemeMode>

    /** Saves [mode] and publishes it on [themeMode]. */
    fun setThemeMode(mode: ThemeMode)
}
