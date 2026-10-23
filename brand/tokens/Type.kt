// cucuto type scale: Google Sans Flex (SIL OFL 1.1) mapped onto the Material 3 type roles.
// Drop into android/app/src/main/java/app/mauri/cucuto/ui/theme/ and add the variable font as
// android/app/src/main/res/font/google_sans_flex.ttf (subset it first, see brand/README.md#typography).
package app.mauri.cucuto.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.mauri.cucuto.R

/**
 * Roundness (the ROND axis, 0–100) per role family. Big text is fully round and playful; running text keeps a
 * little roundness so it matches the brand without hurting legibility.
 */
private object Round {
    const val DISPLAY = 100f
    const val TITLE = 100f
    const val LABEL = 70f
    const val BODY = 30f
}

/** One instance of the variable font. Optical size follows the font size so small text stays open. */
private fun flex(weight: Int, round: Float, size: TextUnit): FontFamily = FontFamily(
    Font(
        resId = R.font.google_sans_flex,
        weight = FontWeight(weight),
        variationSettings = FontVariation.Settings(
            FontVariation.weight(weight),
            FontVariation.Setting("ROND", round),
            FontVariation.Setting("opsz", size.value.coerceIn(6f, 144f)),
        ),
    ),
)

private fun style(
    size: Int,
    lineHeight: Int,
    weight: Int,
    round: Float,
    tracking: Double = 0.0,
): TextStyle = TextStyle(
    fontFamily = flex(weight, round, size.sp),
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.em,
)

/** Emphasized styles add weight (M3 Expressive "emphasized" type) and keep size and line height. */
private const val EMPHASIS = 200

/**
 * Sizes and line heights are the Material 3 baseline type scale; weights and roundness are cucuto's.
 * Use the emphasized roles for hero moments: the character name when it is assigned, onboarding headlines,
 * the status card when everything turns green.
 */
val CucutoTypography = Typography(
    displayLarge = style(57, 64, 600, Round.DISPLAY, -0.004),
    displayMedium = style(45, 52, 600, Round.DISPLAY),
    displaySmall = style(36, 44, 600, Round.DISPLAY),
    headlineLarge = style(32, 40, 600, Round.DISPLAY),
    headlineMedium = style(28, 36, 600, Round.DISPLAY),
    headlineSmall = style(24, 32, 600, Round.DISPLAY),
    titleLarge = style(22, 28, 600, Round.TITLE),
    titleMedium = style(16, 24, 600, Round.TITLE, 0.009),
    titleSmall = style(14, 20, 600, Round.TITLE, 0.007),
    bodyLarge = style(16, 24, 400, Round.BODY, 0.009),
    bodyMedium = style(14, 20, 400, Round.BODY, 0.018),
    bodySmall = style(12, 16, 400, Round.BODY, 0.033),
    labelLarge = style(14, 20, 600, Round.LABEL, 0.007),
    labelMedium = style(12, 16, 600, Round.LABEL, 0.042),
    labelSmall = style(11, 16, 600, Round.LABEL, 0.045),
    displayLargeEmphasized = style(57, 64, 600 + EMPHASIS, Round.DISPLAY, -0.004),
    displayMediumEmphasized = style(45, 52, 600 + EMPHASIS, Round.DISPLAY),
    displaySmallEmphasized = style(36, 44, 600 + EMPHASIS, Round.DISPLAY),
    headlineLargeEmphasized = style(32, 40, 600 + EMPHASIS, Round.DISPLAY),
    headlineMediumEmphasized = style(28, 36, 600 + EMPHASIS, Round.DISPLAY),
    headlineSmallEmphasized = style(24, 32, 600 + EMPHASIS, Round.DISPLAY),
    titleLargeEmphasized = style(22, 28, 600 + EMPHASIS, Round.TITLE),
    titleMediumEmphasized = style(16, 24, 600 + EMPHASIS, Round.TITLE, 0.009),
    titleSmallEmphasized = style(14, 20, 600 + EMPHASIS, Round.TITLE, 0.007),
    bodyLargeEmphasized = style(16, 24, 400 + EMPHASIS, Round.BODY, 0.009),
    bodyMediumEmphasized = style(14, 20, 400 + EMPHASIS, Round.BODY, 0.018),
    bodySmallEmphasized = style(12, 16, 400 + EMPHASIS, Round.BODY, 0.033),
    labelLargeEmphasized = style(14, 20, 600 + EMPHASIS, Round.LABEL, 0.007),
    labelMediumEmphasized = style(12, 16, 600 + EMPHASIS, Round.LABEL, 0.042),
    labelSmallEmphasized = style(11, 16, 600 + EMPHASIS, Round.LABEL, 0.045),
)
