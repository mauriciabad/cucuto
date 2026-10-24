package app.mauri.cucuto.video.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mauri.cucuto.video.PhoneColors
import app.mauri.cucuto.video.StageGeometry
import kotlin.math.roundToInt

// The illustrated phone UI: a few big, simple parts in the brand's colors and type. They show the idea of each step,
// not the app's layout or wording, so the video stays true when the app changes (#194). Text is never under 16 sp
// (32 px at 1080p, docs/explainer-video.md#accessibility-checklist).

/** Type sizes of the phone UI. */
internal object PhoneText {
    val title: TextStyle @Composable get() = MaterialTheme.typography.headlineSmall
    val name: TextStyle @Composable get() = MaterialTheme.typography.titleLarge
    val body: TextStyle @Composable get() =
        MaterialTheme.typography.bodyLarge.copy(
            fontSize = 18.sp,
            lineHeight = 24.sp,
        )
    val label: TextStyle @Composable get() =
        MaterialTheme.typography.titleMedium.copy(
            fontSize = 18.sp,
            lineHeight = 24.sp,
        )
}

/** A phone screen: the background, room for the status bar, padding, and the content in a column. */
@Composable
internal fun Screen(
    modifier: Modifier = Modifier,
    background: Color = PhoneColors.Screen,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(background)
            .padding(top = StageGeometry.StatusBar + 10.dp, start = 14.dp, end = 14.dp, bottom = 18.dp),
        content = content,
    )
}

/** The screen's title. */
@Composable
internal fun ScreenTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = PhoneText.title,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(bottom = 14.dp),
    )
}

/** A rounded card. */
@Composable
internal fun Card(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(color)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** A filled pill button (Material 3 Expressive: round at rest). */
@Composable
internal fun PillButton(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    textColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
    Box(
        modifier
            .height(52.dp)
            .clip(CircleShape)
            .background(color)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = PhoneText.label, color = textColor, textAlign = TextAlign.Center)
    }
}

/** A round check badge that pops in at [shown] (0 to 1, may overshoot). */
@Composable
internal fun CheckBadge(
    shown: Float,
    modifier: Modifier = Modifier,
    size: Dp = 30.dp,
    color: Color = app.mauri.cucuto.video.BrandColors.Success,
) {
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                scaleX = shown
                scaleY = shown
                alpha = shown.coerceIn(0f, 1f)
            }.clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.7f))
    }
}

/** Fades and lifts [content] in as [shown] goes 0 to 1. */
internal fun Modifier.reveal(
    shown: Float,
    lift: Dp = 16.dp,
): Modifier =
    graphicsLayer {
        alpha = shown.coerceIn(0f, 1f)
        translationY = (1 - shown) * lift.toPx()
    }

/** Fills the screen and centers [content]. */
@Composable
internal fun Centered(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content,
    )
}

/** Grows the measured height from 0 to full as [progress] goes 0 to 1, so a new item pushes the others down. */
internal fun Modifier.expandIn(progress: Float): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val height = (placeable.height * progress.coerceIn(0f, 1f)).roundToInt()
        layout(placeable.width, height) { placeable.place(0, height - placeable.height) }
    }
