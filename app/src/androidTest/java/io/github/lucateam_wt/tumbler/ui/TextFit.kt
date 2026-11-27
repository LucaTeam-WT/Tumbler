package io.github.lucateam_wt.tumbler.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import androidx.test.platform.app.InstrumentationRegistry

/** Rounding slack in pixels for comparing float layout values with integer sizes. */
private const val SLACK_PX = 0.5f

/**
 * Fails if any of [nodes] (text nodes, usually found with `useUnmergedTree = true`) is cut off:
 * truncated (more lines than `maxLines` allows, or ellipsized), taller or wider than its own box,
 * or outside its parent, outside the screen or clipped by an ancestor. Text that wraps onto more
 * lines is fine. Every node is checked before failing, and the message names each offending node
 * with its text, bounds, its parent's bounds and why it was flagged. A node inside a scrollable
 * container is scrolled into view first.
 *
 * Why not [TextLayoutResult.hasVisualOverflow] of the `GetTextLayoutResult` semantics action:
 * in Compose foundation 1.12.1 that result is rebuilt (`slowCreateTextLayoutResultOrNull`) with
 * the paragraph at the full `maxWidth` of the last constraints (and their `maxHeight`) next to the
 * real box size, so `didOverflowWidth` is true for every text narrower than the space it may use
 * (on a device: "Tumbler", box 329 px, paragraph 1152 px, one 328.8 px line), and its line count
 * and height do not describe what is drawn. Only its `layoutInput` (text, style, density, font
 * resolver, maxLines, softWrap, overflow) and its `size` (the real box) are used here; the text
 * is measured again with [TextMeasurer] at the real box width.
 */
internal fun assertTextsFit(vararg nodes: SemanticsNodeInteraction) {
    val reports = nodes.map { it.textFitReport() }
    val failing = reports.filter { it.problems.isNotEmpty() }
    if (failing.isNotEmpty()) {
        throw AssertionError(
            "${failing.size} of ${reports.size} text nodes do not fit:\n" +
                failing.joinToString("\n") { it.describe() },
        )
    }
}

/** What [assertTextsFit] found for one node; [lineCount] is the text's natural line count in its box. */
internal class TextFitReport(val text: String, val problems: List<String>, val lineCount: Int, val details: String) {
    fun describe() = "- \"$text\": ${problems.joinToString("; ")}\n    $details"
}

internal fun SemanticsNodeInteraction.textFitReport(): TextFitReport {
    if (fetchSemanticsNode().hasScrollableAncestor()) performScrollTo()
    assertIsDisplayed()
    val node = fetchSemanticsNode()
    val results = mutableListOf<TextLayoutResult>()
    performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
    val semanticsResult = results.single()
    val box = semanticsResult.size
    val measured = measureInBox(semanticsResult, box)
    val coordinates = node.layoutInfo.coordinates
    val parentBounds = node.parent?.layoutInfo?.coordinates?.unclippedBoundsInRoot()
    return TextFitReport(
        text = semanticsResult.layoutInput.text.text,
        problems = textFitProblems(semanticsResult, box, measured, coordinates, parentBounds),
        lineCount = measured.natural.lineCount,
        details = describe(semanticsResult, box, measured, coordinates, parentBounds),
    )
}

/**
 * The text measured again at the real [box] width: [natural] with no line limit and unbounded
 * height (what the text needs), [asLaidOut] with the node's own maxLines, overflow and the box
 * height (to see ellipsis).
 */
private class Measured(val natural: TextLayoutResult, val asLaidOut: TextLayoutResult)

private fun measureInBox(semanticsResult: TextLayoutResult, box: IntSize): Measured {
    val input = semanticsResult.layoutInput
    lateinit var measured: Measured
    // Font resolution and layout on the main thread, like the real text.
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        val measurer = TextMeasurer(input.fontFamilyResolver, input.density, input.layoutDirection, cacheSize = 0)
        measured = Measured(
            natural = measurer.measure(
                text = input.text,
                style = input.style,
                overflow = TextOverflow.Clip,
                softWrap = input.softWrap,
                maxLines = Int.MAX_VALUE,
                constraints = Constraints(maxWidth = box.width),
            ),
            asLaidOut = measurer.measure(
                text = input.text,
                style = input.style,
                overflow = input.overflow,
                softWrap = input.softWrap,
                maxLines = input.maxLines,
                constraints = Constraints(maxWidth = box.width, maxHeight = box.height),
            ),
        )
    }
    return measured
}

private fun TextLayoutResult.lineWidths() = (0 until lineCount).map { getLineRight(it) - getLineLeft(it) }

private fun textFitProblems(
    semanticsResult: TextLayoutResult,
    box: IntSize,
    measured: Measured,
    coordinates: LayoutCoordinates,
    parentBounds: Rect?,
): List<String> = buildList {
    val natural = measured.natural
    val maxLines = semanticsResult.layoutInput.maxLines
    if (natural.lineCount > maxLines) add("needs ${natural.lineCount} lines, maxLines = $maxLines cuts it")
    val ellipsized = (0 until measured.asLaidOut.lineCount).filter { measured.asLaidOut.isLineEllipsized(it) }
    if (ellipsized.isNotEmpty()) add("ellipsized on line(s) $ellipsized")
    if (natural.size.height > box.height + SLACK_PX) add("needs ${natural.size.height} px of height, its box has ${box.height} px")
    val widest = natural.lineWidths().maxOrNull() ?: 0f
    if (widest > box.width + SLACK_PX) add("a line needs %.1f px, its box is ${box.width} px wide".format(widest))
    val unclipped = coordinates.unclippedBoundsInRoot()
    val clipped = coordinates.boundsInRoot()
    if (!clipped.encloses(unclipped)) add("cut by an ancestor's clip: only $clipped of $unclipped is drawn")
    if (parentBounds != null && !parentBounds.encloses(unclipped)) add("outside its parent $parentBounds")
    if (!coordinates.screenBounds().encloses(unclipped)) add("outside the screen ${coordinates.screenBounds()}")
}

private fun describe(
    semanticsResult: TextLayoutResult,
    box: IntSize,
    measured: Measured,
    coordinates: LayoutCoordinates,
    parentBounds: Rect?,
): String {
    val input = semanticsResult.layoutInput
    val lines = measured.natural.lineWidths().joinToString(", ") { "%.1f".format(it) }
    return "node ${coordinates.unclippedBoundsInRoot()} (drawn ${coordinates.boundsInRoot()}), parent $parentBounds, " +
        "screen ${coordinates.screenBounds()}; box $box; measured in it: ${measured.natural.size}, " +
        "${measured.natural.lineCount} line(s) [$lines]; maxLines ${input.maxLines}, softWrap ${input.softWrap}, " +
        "overflow ${input.overflow}, fontScale ${input.density.fontScale}; semantics result (not used): " +
        "hasVisualOverflow ${semanticsResult.hasVisualOverflow}, paragraph " +
        "%.1f x %.1f, %d line(s)".format(semanticsResult.multiParagraph.width, semanticsResult.multiParagraph.height, semanticsResult.lineCount)
}

private fun LayoutCoordinates.unclippedBoundsInRoot() = Rect(positionInRoot(), size.toSize())

private fun LayoutCoordinates.screenBounds() = Rect(Offset.Zero, findRootCoordinates().size.toSize())

private fun Rect.encloses(other: Rect) =
    other.left >= left - SLACK_PX && other.top >= top - SLACK_PX &&
        other.right <= right + SLACK_PX && other.bottom <= bottom + SLACK_PX

private fun SemanticsNode.hasScrollableAncestor(): Boolean {
    var ancestor = parent
    while (ancestor != null) {
        if (SemanticsActions.ScrollBy in ancestor.config) return true
        ancestor = ancestor.parent
    }
    return false
}
