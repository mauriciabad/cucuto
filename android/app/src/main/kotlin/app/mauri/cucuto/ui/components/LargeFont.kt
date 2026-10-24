package app.mauri.cucuto.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity

/**
 * True when the user picked a large font size (Settings › Display › Font size, up to 200%). Layouts give text more
 * lines then instead of cutting names and explanations short.
 */
@Composable
fun isLargeFont(): Boolean = LocalDensity.current.fontScale >= LARGE_FONT_SCALE

/** [normal] lines at the default font size, more with a large font (see [isLargeFont]). */
@Composable
fun adaptiveMaxLines(normal: Int): Int = if (isLargeFont()) normal + EXTRA_LINES else normal

private const val LARGE_FONT_SCALE = 1.3f
private const val EXTRA_LINES = 2
