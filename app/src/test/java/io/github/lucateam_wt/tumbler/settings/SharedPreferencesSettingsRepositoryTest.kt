package io.github.lucateam_wt.tumbler.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SharedPreferencesSettingsRepositoryTest {

    private val preferences = FakeSharedPreferences()

    @Test
    fun `a fresh install follows the system theme and writes nothing`() {
        val repository = SharedPreferencesSettingsRepository(preferences)

        assertEquals(ThemeMode.SYSTEM, repository.themeMode.value)
        assertEquals(0, preferences.applies + preferences.commits)
        assertEquals(emptyMap<String, String?>(), preferences.values)
    }

    @Test
    fun `a choice is published at once and stored with apply`() {
        val repository = SharedPreferencesSettingsRepository(preferences)

        repository.setThemeMode(ThemeMode.DARK)

        assertEquals(ThemeMode.DARK, repository.themeMode.value)
        assertEquals("dark", preferences.values[SharedPreferencesSettingsRepository.KEY_THEME])
        assertEquals(1, preferences.applies)
        assertEquals(0, preferences.commits)
    }

    @Test
    fun `every choice survives a restart`() {
        for (mode in ThemeMode.entries) {
            SharedPreferencesSettingsRepository(preferences).setThemeMode(mode)

            // A new repository on the same file is what the next process start sees.
            assertEquals(mode, SharedPreferencesSettingsRepository(preferences).themeMode.value)
        }
    }

    @Test
    fun `the last of several changes wins`() {
        val repository = SharedPreferencesSettingsRepository(preferences)

        repository.setThemeMode(ThemeMode.DARK)
        repository.setThemeMode(ThemeMode.LIGHT)

        assertEquals(ThemeMode.LIGHT, repository.themeMode.value)
        assertEquals(ThemeMode.LIGHT, SharedPreferencesSettingsRepository(preferences).themeMode.value)
    }

    @Test
    fun `an unknown stored value falls back to the system theme`() {
        preferences.values[SharedPreferencesSettingsRepository.KEY_THEME] = "sepia"

        assertEquals(ThemeMode.SYSTEM, SharedPreferencesSettingsRepository(preferences).themeMode.value)
    }

    @Test
    fun `the stored values are fixed strings, not enum names`() {
        // Changing one of these would silently reset users' choices to System.
        assertEquals(listOf("system", "light", "dark"), ThemeMode.entries.map { it.storedValue })
        assertEquals("tumbler_settings", SharedPreferencesSettingsRepository.FILE_NAME)
        assertEquals("theme", SharedPreferencesSettingsRepository.KEY_THEME)
    }
}
