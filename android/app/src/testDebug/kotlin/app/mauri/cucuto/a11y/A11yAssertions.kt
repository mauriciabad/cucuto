package app.mauri.cucuto.a11y

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import app.mauri.cucuto.ui.theme.CucutoTheme
import org.junit.Assert.assertTrue

/** Shows [content] in the brand theme (dynamic color off) at [fontScale] (1 to 2, Android's font size slider). */
internal fun ComposeContentTestRule.showScreen(
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            CucutoTheme(dynamicColor = false) { content() }
        }
    }
    waitForIdle()
}

/** Every node of the merged tree (what TalkBack focuses), or of the unmerged tree, in every window (sheets too). */
internal fun ComposeContentTestRule.nodes(merged: Boolean = true): List<SemanticsNode> {
    fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
    return onAllNodes(isRoot(), useUnmergedTree = !merged).fetchSemanticsNodes().flatMap(::walk)
}

private fun SemanticsNode.isInteractive(): Boolean =
    config.getOrNull(SemanticsActions.OnClick) != null || config.getOrNull(SemanticsProperties.ToggleableState) != null

private fun SemanticsNode.texts(): List<String> =
    config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
        listOfNotNull(config.getOrNull(SemanticsProperties.EditableText)?.text)

/** Fully on screen: a node cut by a scrolling edge has smaller bounds than its size and is skipped. */
private fun SemanticsNode.fullyShown(): Boolean =
    boundsInRoot.width >= size.width - 1 && boundsInRoot.height >= size.height - 1 && size.width > 0

private fun SemanticsNode.describe(): String = texts().joinToString(" / ").ifEmpty { "node #$id" }

/** Every button, switch, checkbox and tappable row says what it is (text or content description). */
internal fun ComposeContentTestRule.assertInteractiveNodesLabeled(screen: String) {
    val unlabeled =
        nodes()
            .filter {
                it.isInteractive() &&
                    it.texts().none(
                        String::isNotBlank,
                    )
            }.map { "node #${it.id} ${it.boundsInRoot}" }
    assertTrue("$screen: interactive nodes without a label:\n" + unlabeled.joinToString("\n"), unlabeled.isEmpty())
}

/** No TalkBack stop says the same words twice (a name in a content description and again as text). */
internal fun ComposeContentTestRule.assertNoDuplicateAnnouncements(screen: String) {
    val duplicates =
        nodes().mapNotNull { node ->
            val words = node.texts().map(String::trim).filter(String::isNotEmpty)
            val repeated =
                words
                    .groupingBy { it }
                    .eachCount()
                    .filterValues { it > 1 }
                    .keys
            if (repeated.isEmpty()) null else "${node.describe()} repeats ${repeated.joinToString()}"
        }
    assertTrue("$screen: duplicate announcements:\n" + duplicates.joinToString("\n"), duplicates.isEmpty())
}

/**
 * Every interactive element gets a 48 × 48 dp touch target (Material and Android guidance), on the whole screen.
 *
 * Compose stretches the touch area of any clickable smaller than 48 dp to 48 dp around it (and `touchBoundsInRoot`
 * reports the stretched area, so measuring that alone never fails, #226). What can go wrong is a small control with
 * no room to stretch: its 48 dp area overlaps another control's, and taps near the edge land on the neighbor. So a
 * control under 48 dp fails when its 48 dp area overlaps the area of another control that moves with it (same window,
 * same scrolling container: content scrolled under a top bar is covered by it, not crowding it), is not its parent or
 * child, and is not a backdrop behind it (a sheet's scrim, a card it sits on).
 *
 * After the first screenful, each scrolling container is scrolled to its end, most of a viewport at a time
 * ([scrollThrough]), and the controls that came into view are checked too.
 */
internal fun ComposeContentTestRule.assertTouchTargets(screen: String) {
    val minPx = with(density) { MIN_TOUCH_DP * this.density }
    val crowded = linkedSetOf<String>()

    fun checkVisible() {
        val controls = nodes().filter { it.isInteractive() && it.fullyShown() }
        for (small in controls.filter { it.size.width < minPx - 1 || it.size.height < minPx - 1 }) {
            val area = small.touchArea(minPx)
            val layer = small.layer()
            val neighbor =
                controls.firstOrNull { other ->
                    other.id != small.id &&
                        other.layer() == layer &&
                        !other.isAncestorOf(small) &&
                        !small.isAncestorOf(other) &&
                        !other.boundsInRoot.contains(area) &&
                        area.overlapsBeyondSlack(other.touchArea(minPx))
                } ?: continue
            val dp = density.density
            crowded +=
                "${small.describe()} (${small.size.width / dp} × ${small.size.height / dp} dp) has no room for " +
                "48 dp: it touches ${neighbor.describe()}"
        }
    }
    checkVisible()
    scrollThrough(::checkVisible)
    assertTrue("$screen: touch targets under 48 dp:\n" + crowded.joinToString("\n"), crowded.isEmpty())
}

/** The node's bounds, grown around their center to at least [minPx] on each side (Compose's touch area). */
private fun SemanticsNode.touchArea(minPx: Float): Rect {
    val bounds = boundsInRoot
    val growX = ((minPx - bounds.width) / 2).coerceAtLeast(0f)
    val growY = ((minPx - bounds.height) / 2).coerceAtLeast(0f)
    return Rect(bounds.left - growX, bounds.top - growY, bounds.right + growX, bounds.bottom + growY)
}

private fun Rect.overlapsBeyondSlack(other: Rect): Boolean {
    val overlap = intersect(other)
    return overlap.width > SLACK_PX && overlap.height > SLACK_PX
}

private fun SemanticsNode.isAncestorOf(node: SemanticsNode): Boolean =
    generateSequence(node.parent) { it.parent }.any { it.id == id }

/** What the node moves with: its nearest scrolling ancestor, or else its window's root. */
private fun SemanticsNode.layer(): Int {
    val ancestors = generateSequence(parent) { it.parent }.toList()
    return (ancestors.firstOrNull { it.config.getOrNull(SemanticsActions.ScrollBy) != null } ?: ancestors.lastOrNull())
        ?.id ?: id
}

private fun Rect.contains(other: Rect): Boolean =
    left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom

/**
 * Scrolls every scrolling container on screen (lists, scrolling columns, rows of chips) from where it is to its end,
 * 80% of its viewport at a time, and calls [atEachStep] after each step. Containers that only appear while scrolling
 * (a row of chips further down a list) are scrolled through too. Uses the containers' own scroll action, the one
 * TalkBack and Switch Access use, so it works the same for lazy and plain scrolling.
 */
internal fun ComposeContentTestRule.scrollThrough(atEachStep: () -> Unit) {
    val done = mutableSetOf<Int>()
    while (true) {
        val next =
            nodes(merged = false).firstOrNull {
                it.id !in done && it.config.getOrNull(SemanticsActions.ScrollBy) != null && it.axis() != null
            } ?: return
        done += next.id
        val id = next.id
        for (step in 1..MAX_SCROLL_STEPS) {
            if (!scrollOnce(id)) break
            atEachStep()
        }
    }
}

/** Scrolls container [id] by 80% of its viewport; false when it is gone or was already at its end. */
private fun ComposeContentTestRule.scrollOnce(id: Int): Boolean {
    fun range() = nodes(merged = false).firstOrNull { it.id == id }?.axis()
    val node = nodes(merged = false).firstOrNull { it.id == id } ?: return false
    val before = node.axis()?.value() ?: return false
    val vertical = node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
    val viewport = if (vertical) node.size.height else node.size.width
    onNode(SemanticsMatcher("node #$id") { it.id == id }, useUnmergedTree = true)
        .performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy ->
            val step = viewport * SCROLL_FRACTION
            if (vertical) scrollBy(0f, step) else scrollBy(step, 0f)
        }
    waitForIdle()
    val after = range()?.value() ?: return false
    return after > before
}

private fun SemanticsNode.axis(): ScrollAxisRange? =
    config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
        ?: config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)

/**
 * Text cut off without an ellipsis: lines dropped by `maxLines`, or glyphs wider or taller than the text's own box
 * on screen. What large fonts must never do. Measured from the drawn lines, not `hasVisualOverflow`, which also flags
 * text laid out once with looser constraints (intrinsic measurements). Hidden text (zero size) is skipped.
 */
internal fun ComposeContentTestRule.clippedTexts(): List<String> =
    nodes(merged = false).mapNotNull { node ->
        val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult) ?: return@mapNotNull null
        val results = mutableListOf<TextLayoutResult>()
        action.action?.invoke(results)
        val layout = results.firstOrNull() ?: return@mapNotNull null
        if (layout.lineCount == 0 || node.size.width == 0 || node.size.height == 0) return@mapNotNull null
        val last = layout.lineCount - 1
        if (layout.isLineEllipsized(last)) return@mapNotNull null
        val widest = (0..last).maxOf { layout.getLineRight(it) - layout.getLineLeft(it) }
        val tall = layout.getLineBottom(last)
        val text = layout.layoutInput.text.text
        // A word split across lines ("Norma" / "l") means its box is too narrow for it: squeezed, not reflowed.
        val brokenWord =
            (0 until last).any { line ->
                val end = layout.getLineEnd(line)
                end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()
            }
        val cut =
            layout.multiParagraph.didExceedMaxLines ||
                widest > node.size.width + SLACK_PX ||
                tall > node.size.height + SLACK_PX
        when {
            cut -> "$text (${widest.toInt()}×${tall.toInt()} in ${node.size})"
            brokenWord -> "$text (a word is split across lines)"
            else -> null
        }
    }

private const val SLACK_PX = 2f

private const val MIN_TOUCH_DP = 48f

/** Far more steps than the longest screen needs; stops a list that keeps growing. */
private const val MAX_SCROLL_STEPS = 60
private const val SCROLL_FRACTION = 0.8f
