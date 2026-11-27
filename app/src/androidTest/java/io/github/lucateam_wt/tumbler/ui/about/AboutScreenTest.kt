package io.github.lucateam_wt.tumbler.ui.about

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import io.github.lucateam_wt.tumbler.R
import io.github.lucateam_wt.tumbler.ui.theme.TumblerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The About screen: name, version, license, the source link, Back, and font scale 1.3. */
class AboutScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)

    private val version = AppVersion(name = "1.2.3", code = 42)

    private var opens = 0
    private var backs = 0

    private fun show(linkOpens: Boolean = true, fontScale: Float = 1f) {
        composeRule.setContent {
            TumblerTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    AboutScreen(
                        version = version,
                        onOpenSource = {
                            opens++
                            linkOpens
                        },
                        onBack = { backs++ },
                    )
                }
            }
        }
    }

    @Test
    fun showsNameVersionLicenseAndSource() {
        show()

        composeRule.onNodeWithText(string(R.string.about_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.app_name))
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        composeRule.onNodeWithText(string(R.string.about_version, "1.2.3", 42L)).assertIsDisplayed()
        composeRule.onNodeWithText("GPL-3.0-or-later").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.about_license_title)).assertIsDisplayed()
        composeRule.onNodeWithText("https://github.com/LucaTeam-WT/Tumbler", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun sourceLinkIsAButtonThatOpensTheLink() {
        show()

        composeRule.onNodeWithTag(AboutTags.SOURCE)
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(
                SemanticsMatcher("click label is \"Open in browser\"") {
                    it.config.getOrNull(SemanticsActions.OnClick)?.label == string(R.string.about_source_open)
                },
            )
            .performClick()

        assertEquals(1, opens)
        composeRule.onNodeWithText(string(R.string.about_link_unavailable)).assertDoesNotExist()
    }

    @Test
    fun aLinkNoAppCanOpenShowsAMessage() {
        show(linkOpens = false)

        composeRule.onNodeWithTag(AboutTags.SOURCE).performClick()

        assertEquals(1, opens)
        composeRule.onNodeWithText(string(R.string.about_link_unavailable)).assertIsDisplayed()
    }

    @Test
    fun backButtonIsNamedAndGoesBack() {
        show()

        composeRule.onNodeWithTag(AboutTags.BACK)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(string(R.string.add_back))))
            .performClick()

        assertEquals(1, backs)
    }

    @Test
    fun theInstalledVersionComesFromThePackageManager() {
        val installed = composeRule.activity.appVersion()

        assertTrue("versionName is empty", installed.name.isNotBlank())
        assertTrue("versionCode is ${installed.code}", installed.code >= 1)
    }

    @Test
    fun nothingIsClippedAtFontScale13() {
        show(fontScale = 1.3f)

        for (text in listOf(
            string(R.string.app_name),
            string(R.string.about_version, "1.2.3", 42L),
            string(R.string.about_license_title),
            "GPL-3.0-or-later",
            string(R.string.about_source_title),
            "https://github.com/LucaTeam-WT/Tumbler",
        )) {
            assertNotClipped(composeRule.onNodeWithText(text, useUnmergedTree = true))
        }
    }

    private fun assertNotClipped(node: SemanticsNodeInteraction) {
        node.performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse("text overflows", layouts.single().hasVisualOverflow)
    }
}
