package app.mauri.cucuto.screenshots

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Height of the drawn status bar, also given to the screen as its status bar inset. */
internal val STATUS_BAR = 32.dp

/** Height of the gesture navigation bar, also given to the screen as its navigation bar inset. */
internal val NAVIGATION_BAR = 24.dp

/**
 * A plain Android status bar (clock, signal, Wi-Fi, battery) and the gesture handle, drawn over the screen in the
 * theme's `onSurface` color. No notification icons and no carrier: the screenshots show Cucuto, nothing else.
 */
@Composable
internal fun BoxScope.SystemBars() {
    val color = MaterialTheme.colorScheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(STATUS_BAR)
                .padding(horizontal = 24.dp),
    ) {
        Text("9:30", color = color, fontSize = 14.sp, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.weight(1f))
        Canvas(Modifier.size(14.dp)) {
            // Signal: a filled right triangle.
            val path =
                Path().apply {
                    moveTo(size.width, 0f)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
            drawPath(path, color)
        }
        Canvas(Modifier.size(16.dp, 14.dp)) {
            // Wi-Fi: a quarter-circle fan pointing down.
            val radius = size.height * 1.15f
            drawArc(
                color = color,
                startAngle = 225f,
                sweepAngle = 90f,
                useCenter = true,
                topLeft = Offset(size.width / 2 - radius, size.height - radius),
                size = Size(radius * 2, radius * 2),
            )
        }
        Canvas(Modifier.width(22.dp).height(12.dp)) {
            // Battery: an outlined body, a nub and an 80 % charge.
            val nub = size.width * 0.08f
            val stroke = 1.2.dp.toPx()
            val body = Size(size.width - nub - stroke, size.height - stroke)
            drawRoundRect(
                color = color,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = body,
                cornerRadius = CornerRadius(3.dp.toPx()),
                style = Stroke(stroke),
            )
            val inset = stroke * 2
            drawRoundRect(
                color = color,
                topLeft = Offset(inset, inset),
                size = Size((body.width - inset * 1.5f) * 0.8f, body.height - inset * 1.5f),
                cornerRadius = CornerRadius(1.5.dp.toPx()),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(size.width - nub, size.height * 0.3f),
                size = Size(nub, size.height * 0.4f),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
        }
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(NAVIGATION_BAR),
    ) {
        Box(
            Modifier
                .size(width = 108.dp, height = 4.dp)
                .background(color.copy(alpha = HANDLE_ALPHA), RoundedCornerShape(2.dp)),
        )
    }
}

private const val HANDLE_ALPHA = 0.8f
