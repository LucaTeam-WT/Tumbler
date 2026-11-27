package io.github.lucateam_wt.tumbler.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

/**
 * Controls for [assertTextsFit]: it must fail for text that is cut off and pass for text that
 * only wraps or is shorter than the space it may use.
 */
class TextFitTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val long = "A deliberately long sentence that cannot fit into a very small container at all"

    private fun show(content: @Composable () -> Unit) = composeRule.setContent(content)

    private fun text(value: String = long) = composeRule.onNodeWithText(value, useUnmergedTree = true)

    /** The helper fails, and its message names the text and every expected reason. */
    private fun assertFlagged(vararg reasons: String) {
        val error = try {
            assertTextsFit(text())
            null
        } catch (e: AssertionError) {
            e
        }
        assertNotNull("assertTextsFit accepted a text that does not fit", error)
        val message = error!!.message.orEmpty()
        assertTrue("the message does not name the text: $message", message.contains("\"$long\""))
        for (reason in reasons) assertTrue("expected \"$reason\" in: $message", message.contains(reason))
    }

    @Test
    fun flagsTextEllipsizedInATooSmallContainer() {
        show {
            Box(Modifier.width(60.dp)) { Text(long, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }

        assertFlagged("ellipsized", "maxLines = 1 cuts it")
    }

    @Test
    fun flagsTextCutByMaxLines() {
        show {
            Box(Modifier.width(60.dp)) { Text(long, maxLines = 2) }
        }

        assertFlagged("maxLines = 2 cuts it")
    }

    @Test
    fun flagsTextTallerThanAFixedHeight() {
        show {
            Box(Modifier.width(120.dp).height(12.dp)) { Text(long) }
        }

        assertFlagged("px of height, its box has")
    }

    @Test
    fun flagsTextOutsideItsParentAndTheScreen() {
        show {
            // The tag gives the narrow box a semantics node, so it is the text's parent.
            Box(Modifier.width(40.dp).testTag("narrow")) {
                Box(Modifier.wrapContentWidth(align = Alignment.Start, unbounded = true)) {
                    Text(long + " " + long, softWrap = false)
                }
            }
        }

        val error = try {
            assertTextsFit(text("$long $long"))
            null
        } catch (e: AssertionError) {
            e
        }
        assertNotNull("assertTextsFit accepted a text outside its parent", error)
        val message = error!!.message.orEmpty()
        assertTrue(message, message.contains("outside its parent"))
        assertTrue(message, message.contains("outside the screen"))
    }

    @Test
    fun flagsTextCutByAnAncestorsClip() {
        show {
            Box(Modifier.size(40.dp).clipToBounds()) {
                Box(Modifier.wrapContentWidth(align = Alignment.Start, unbounded = true)) {
                    Text(long, softWrap = false)
                }
            }
        }

        assertFlagged("cut by an ancestor's clip")
    }

    @Test
    fun acceptsTextThatWrapsOntoMoreLines() {
        show {
            Box(Modifier.width(80.dp)) { Text(long) }
        }

        assertTextsFit(text())
        val report = text().textFitReport()
        if (report.lineCount < 2) fail("the control text should wrap onto several lines: ${report.details}")
    }

    @Test
    fun acceptsShortTextInAWideContainer() {
        // The shape the old hasVisualOverflow check flagged by mistake on every screen.
        show {
            Column(Modifier.fillMaxWidth()) { Text("Tumbler") }
        }

        assertTextsFit(text("Tumbler"))
    }
}
