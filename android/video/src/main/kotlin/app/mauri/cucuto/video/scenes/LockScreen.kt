package app.mauri.cucuto.video.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mauri.cucuto.video.BrandColors
import app.mauri.cucuto.video.Character
import app.mauri.cucuto.video.Cookie
import app.mauri.cucuto.video.Person
import app.mauri.cucuto.video.ShotScope
import app.mauri.cucuto.video.Spring
import app.mauri.cucuto.video.StageGeometry
import app.mauri.cucuto.video.soundArcs

/** A message landing on the lock screen at [at] seconds; [voice] is the character the person has, if any. */
@Immutable
internal data class LockScreenMessage(
    val person: Person,
    val at: Double,
    val voice: Character? = null,
)

/**
 * The lock screen (shots 1 and 9): a plain brand wallpaper, the clock, and generic notification cards from the
 * made-up cast. No WhatsApp branding, no real people, no personal widgets (docs/explainer-video.md, staging).
 */
@Composable
internal fun ShotScope.LockScreen(
    messages: List<LockScreenMessage>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFFFFEDE7), Color(0xFFFFD9C2))))
            .drawBehind {
                for (radius in listOf(70.dp, 110.dp, 150.dp)) {
                    drawCircle(
                        Color.White.copy(alpha = 0.35f),
                        radius.toPx(),
                        Offset(
                            size.width / 2,
                            size.height * 0.86f,
                        ),
                        style = Stroke(14.dp.toPx()),
                    )
                }
            }.padding(top = StageGeometry.StatusBar + 22.dp, start = 10.dp, end = 10.dp, bottom = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "9:30",
            fontSize = 62.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.displayLarge,
        )
        Text("Saturday, 24 October", style = PhoneText.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        // Newest on top, as Android stacks them; each new card pushes the others down.
        messages.reversed().forEach { message ->
            val shown = spring(message.at - 0.08, Spring.DefaultSpatial)
            if (shown > 0f) {
                NotificationCard(
                    message.person,
                    Modifier
                        .expandIn(shown.coerceAtMost(1f))
                        .reveal(shown, lift = (-24).dp),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.Rounded.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(26.dp),
        )
    }
}

/** A generic notification: the sender's initial in a cookie (no photo), their name and the message. */
@Composable
private fun NotificationCard(
    person: Person,
    modifier: Modifier = Modifier,
) {
    Box(modifier.padding(bottom = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Color.White.copy(alpha = 0.94f))
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cookie(44.dp, person.tint) {
                Text(
                    person.name.take(1),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column {
                Text(person.name, style = PhoneText.name, color = MaterialTheme.colorScheme.onSurface)
                Text(person.message, style = PhoneText.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Shot 1's stage: the same grey bell rings beside the phone for every message (the plain ping, always the same), and
 * a question mark: who was that?
 */
@Composable
internal fun ShotScope.SamePingStage(
    pings: List<Double>,
    questionAt: Double,
) {
    val bell = 120.dp
    val ring =
        pings
            .sumOf { at ->
                (spring(at - 0.08, Spring.FastSpatial) - spring(at + 0.5, Spring.DefaultSpatial)).toDouble()
            }.toFloat()
            .coerceIn(0f, 1.2f)
    val squash = pings.fold(1f) { acc, at -> acc * squash(at, 0.16f) }
    val arcs = pings.firstOrNull { t >= it && t < it + 0.9 }?.let { progress(it, it + 0.9) } ?: 0f
    Box(
        Modifier
            .offset(StageGeometry.PopRight - bell / 2, StageGeometry.Height / 2 - bell / 2)
            .size(bell)
            .drawBehind { soundArcs(center, size.width * 0.62f, arcs, Color(0xFF8D7B74), 5.dp.toPx()) }
            .graphicsLayer {
                val scale = 0.75f + 0.25f * ring
                scaleX = scale / squash
                scaleY = scale * squash
                alpha = (0.35f + ring).coerceIn(0f, 1f)
                transformOrigin = TransformOrigin(0.5f, 1f)
            }.clip(CircleShape)
            .background(Color(0xFFE7DCD8)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Notifications,
            contentDescription = null,
            tint = Color(0xFF6F5F59),
            modifier =
                Modifier.size(bell * 0.55f),
        )
    }
    val question = spring(questionAt, Spring.FastSpatial)
    if (question > 0f) {
        Box(
            Modifier
                .offset(StageGeometry.PopLeft - bell / 2, StageGeometry.Height / 2 - bell / 2)
                .size(bell)
                .graphicsLayer {
                    scaleX = question
                    scaleY = question
                    rotationZ = -8f * (1 - question)
                }.clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "?",
                fontSize = 76.sp,
                fontWeight = FontWeight.Bold,
                color = BrandColors.Tangerine,
                style = MaterialTheme.typography.displayLarge,
            )
        }
    }
}
