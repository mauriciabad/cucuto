package app.mauri.cucuto.a11y

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
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

/** Every node of the merged tree (what TalkBack focuses), or of the unmerged tree. */
internal fun ComposeContentTestRule.nodes(merged: Boolean = true): List<SemanticsNode> {
    fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
    return walk(onRoot(useUnmergedTree = !merged).fetchSemanticsNode())
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

/** Every interactive element on screen is at least 48 × 48 dp to touch (Material and Android guidance). */
internal fun ComposeContentTestRule.assertTouchTargets(screen: String) {
    val minPx = with(density) { MIN_TOUCH_DP * this.density }
    val small =
        nodes()
            .filter { it.isInteractive() && it.fullyShown() }
            .filter { it.touchBoundsInRoot.width < minPx - 1 || it.touchBoundsInRoot.height < minPx - 1 }
            .map {
                "${it.describe()}: ${it.touchBoundsInRoot.width / density.density} × ${it.touchBoundsInRoot.height / density.density} dp"
            }
    assertTrue("$screen: touch targets under 48 dp:\n" + small.joinToString("\n"), small.isEmpty())
}

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
