package io.github.lucateam_wt.tumbler.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeTest {

    @Test
    fun `system follows the system dark theme`() {
        assertTrue(ThemeMode.SYSTEM.useDarkTheme(systemInDarkTheme = true))
        assertFalse(ThemeMode.SYSTEM.useDarkTheme(systemInDarkTheme = false))
    }

    @Test
    fun `light is light whatever the system says`() {
        assertFalse(ThemeMode.LIGHT.useDarkTheme(systemInDarkTheme = true))
        assertFalse(ThemeMode.LIGHT.useDarkTheme(systemInDarkTheme = false))
    }

    @Test
    fun `dark is dark whatever the system says`() {
        assertTrue(ThemeMode.DARK.useDarkTheme(systemInDarkTheme = true))
        assertTrue(ThemeMode.DARK.useDarkTheme(systemInDarkTheme = false))
    }

    @Test
    fun `stored values map back to their choice`() {
        for (mode in ThemeMode.entries) assertEquals(mode, ThemeMode.fromStoredValue(mode.storedValue))
    }

    @Test
    fun `missing, unknown and differently cased values mean system`() {
        for (value in listOf(null, "", "Dark", "DARK", "night", " dark")) {
            assertEquals(value.toString(), ThemeMode.SYSTEM, ThemeMode.fromStoredValue(value))
        }
    }

    @Test
    fun `the options are offered in the order system, light, dark`() {
        assertEquals(listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK), ThemeMode.entries)
    }
}
