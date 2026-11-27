package io.github.lucateam_wt.tumbler.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import io.github.lucateam_wt.tumbler.R
import io.github.lucateam_wt.tumbler.ui.assertTextsFit
import io.github.lucateam_wt.tumbler.settings.ThemeMode
import io.github.lucateam_wt.tumbler.ui.theme.TumblerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The Settings screen: the theme radio group, applying a choice at once, Back, and font scale 1.3. */
class SettingsScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int) = composeRule.activity.getString(id)

    private val labels = mapOf(
        ThemeMode.SYSTEM to R.string.settings_theme_system,
        ThemeMode.LIGHT to R.string.settings_theme_light,
        ThemeMode.DARK to R.string.settings_theme_dark,
    )

    private var backs = 0

    /** The background color of the theme the screen is drawn with, read on every composition. */
    private var background = Color.Unspecified

    /**
     * Shows the screen the way the app does: the theme follows the choice, which the screen
     * changes. [systemDark] stands for the system setting.
     */
    private fun show(initial: ThemeMode = ThemeMode.SYSTEM, systemDark: Boolean = false, fontScale: Float = 1f) {
        composeRule.setContent {
            var mode by remember { mutableStateOf(initial) }
            TumblerTheme(darkTheme = mode.useDarkTheme(systemDark), dynamicColor = false) {
                background = MaterialTheme.colorScheme.background
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    SettingsScreen(themeMode = mode, onThemeModeChange = { mode = it }, onBack = { backs++ })
                }
            }
        }
    }

    private fun option(mode: ThemeMode) = composeRule.onNodeWithTag(SettingsTags.themeOption(mode))

    @Test
    fun themeOptionsAreOneRadioGroupWithTheCurrentChoiceSelected() {
        show(initial = ThemeMode.LIGHT)

        composeRule.onNodeWithText(string(R.string.settings_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.settings_theme))
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        composeRule.onNodeWithTag(SettingsTags.THEME_GROUP)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))
        for (mode in ThemeMode.entries) {
            option(mode)
                .assertIsDisplayed()
                .assertHasClickAction()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
                // One node per option: its label is merged into the row TalkBack reads.
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(AnnotatedString(string(labels.getValue(mode))))))
        }
        option(ThemeMode.LIGHT).assertIsSelected()
        option(ThemeMode.SYSTEM).assertIsNotSelected()
        option(ThemeMode.DARK).assertIsNotSelected()
    }

    @Test
    fun choosingAThemeSelectsItAndAppliesItAtOnce() {
        show(initial = ThemeMode.SYSTEM, systemDark = false)
        composeRule.waitForIdle()
        assertTrue("System on a light system is light", background.luminance() > 0.5f)

        option(ThemeMode.DARK).performClick()

        option(ThemeMode.DARK).assertIsSelected()
        option(ThemeMode.SYSTEM).assertIsNotSelected()
        assertTrue("Dark is applied", background.luminance() < 0.5f)

        option(ThemeMode.LIGHT).performClick()

        option(ThemeMode.LIGHT).assertIsSelected()
        option(ThemeMode.DARK).assertIsNotSelected()
        assertTrue("Light is applied", background.luminance() > 0.5f)
    }

    @Test
    fun systemFollowsADarkSystem() {
        show(initial = ThemeMode.SYSTEM, systemDark = true)
        composeRule.waitForIdle()

        assertTrue(background.luminance() < 0.5f)
    }

    @Test
    fun backButtonIsNamedAndGoesBack() {
        show()

        composeRule.onNodeWithTag(SettingsTags.BACK)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(string(R.string.add_back))))
            .performClick()

        assertEquals(1, backs)
    }

    @Test
    fun nothingIsClippedAtFontScale13() {
        show(fontScale = 1.3f)

        val title = composeRule.onNodeWithText(string(R.string.settings_theme), useUnmergedTree = true)
        val options = ThemeMode.entries.associateWith { mode ->
            composeRule.onNodeWithText(string(labels.getValue(mode)), useUnmergedTree = true)
        }
        assertTextsFit(title, *options.values.toTypedArray())

        for ((mode, text) in options) {
            val row = option(mode).getUnclippedBoundsInRoot()
            val label = text.getUnclippedBoundsInRoot()
            assertTrue("$mode label is outside its row", label.top >= row.top && label.bottom <= row.bottom)
        }
    }
}
