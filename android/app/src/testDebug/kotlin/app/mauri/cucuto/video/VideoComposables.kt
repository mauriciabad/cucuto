package app.mauri.cucuto.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mauri.cucuto.screenshots.DeviceFrame
import app.mauri.cucuto.ui.feedback.FeedbackController
import app.mauri.cucuto.ui.feedback.LocalFeedback
import java.util.Locale

/** The brand's cream background (docs/explainer-video.md) and the soft sound rings of the social card. */
internal val Cream = Color(0xFFFFF4F0)
private val RingColor = Color(0xFFFFE9E2)
private val BezelColor = Color(0xFF2A211E)
private val RippleColor = Color(0xFFF46222)

/**
 * A phone screen of the video: the app in [language] with the brand colors, the drawn status bar and gesture handle
 * (as in the screenshot tests, ADR 0032), beats on the test clock ([nanoTime]) and the touch indicator on top.
 */
@Composable
internal fun VideoPhone(
    recorder: SceneRecorder,
    language: String,
    nanoTime: () -> Long,
    content: @Composable () -> Unit,
) {
    val feedback = remember { FeedbackController(engine = null, haptics = null, nanoTime = nanoTime) }
    CompositionLocalProvider(LocalFeedback provides feedback) {
        Box(Modifier.fillMaxSize()) {
            DeviceFrame(dark = false, locale = Locale.forLanguageTag(language), fontScale = 1f) { content() }
            TouchIndicator(recorder)
        }
    }
}

/** Android's "Show taps": a soft circle under the finger, fading out after it lifts. */
@Composable
private fun TouchIndicator(recorder: SceneRecorder) {
    Canvas(Modifier.fillMaxSize()) {
        val now = recorder.videoTime.doubleValue
        for (touch in recorder.touches) {
            if (now < touch.downMs || now > touch.upMs + TOUCH_FADE_MS) continue
            val alpha = if (now <= touch.upMs) 1f else (1 - (now - touch.upMs) / TOUCH_FADE_MS).toFloat()
            val radius = TOUCH_RADIUS.toPx()
            drawCircle(Color.White.copy(alpha = 0.55f * alpha), radius, touch.position)
            drawCircle(
                Color(0xFF5F5F5F).copy(alpha = 0.6f * alpha),
                radius,
                touch.position,
                style = Stroke(2.dp.toPx()),
            )
        }
    }
}

/** A notification on the lock screen stand-in. */
@Immutable
internal data class StandInNotification(
    val sender: String,
    val text: String,
)

/**
 * Stand-in for the device's lock screen (shots 1 and 9, which need device footage, #194): a plain cream wallpaper,
 * the clock and [visible] WhatsApp notifications sliding in. Drawn here, not recorded, so it is only a placeholder; the
 * notifications look generic and carry no WhatsApp branding.
 */
@Composable
internal fun LockScreenStandIn(
    notifications: List<StandInNotification>,
    visible: List<Boolean>,
    strings: VideoStrings,
) {
    Column(
        Modifier.fillMaxSize().background(Cream).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(96.dp))
        Text("9:30", fontSize = 96.sp, fontWeight = FontWeight.Light, color = Color(0xFF3E2A23))
        Text(strings.lockDate, style = MaterialTheme.typography.titleMedium, color = Color(0xFF5B4038))
        Spacer(Modifier.height(48.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Newest on top, as Android stacks them.
            notifications.indices.reversed().forEach { index ->
                AnimatedVisibility(
                    visible = visible[index],
                    enter = expandVertically() + slideInVertically { -it / 2 } + fadeIn(),
                ) {
                    NotificationCard(notifications[index], strings)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Icon(Icons.Rounded.Lock, contentDescription = null, tint = Color(0xFF5B4038))
        Spacer(Modifier.height(72.dp))
    }
}

@Composable
private fun NotificationCard(
    notification: StandInNotification,
    strings: VideoStrings,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White.copy(alpha = 0.92f))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // No profile picture: the made-up sender's initial, as Android shows a contact without a photo.
        Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFFD9CFCB)), contentAlignment = Alignment.Center) {
            Text(notification.sender.take(1), color = Color(0xFF5B4038), style = MaterialTheme.typography.titleMedium)
        }
        Column(Modifier.weight(1f)) {
            Text(
                "WhatsApp · ${strings.now}",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF6F5B54),
            )
            Text(notification.sender, style = MaterialTheme.typography.titleMedium, color = Color(0xFF231915))
            Text(notification.text, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF3E2A23))
        }
    }
}

/** Where the phone sits on the 1920 × 1080 stage, in pixels. `explainer.main.kts` reads it from `layout.properties`. */
internal object StageLayout {
    const val WIDTH = 1920
    const val HEIGHT = 1080

    /** The phone screen: the 822 × 1782 px captures scaled to 443 × 960. */
    const val SCREEN_W = 443
    const val SCREEN_H = 960
    const val SCREEN_X = (WIDTH - SCREEN_W) / 2
    const val SCREEN_Y = (HEIGHT - SCREEN_H) / 2
    const val SCREEN_RADIUS = 40f
    const val BEZEL = 14f

    val screen = Rect(Offset(SCREEN_X.toFloat(), SCREEN_Y.toFloat()), Size(SCREEN_W.toFloat(), SCREEN_H.toFloat()))
    val body = screen.inflate(BEZEL)
    val center = screen.center

    fun properties(): String =
        "screenX=$SCREEN_X\nscreenY=$SCREEN_Y\nscreenW=$SCREEN_W\nscreenH=$SCREEN_H\nscreenRadius=${SCREEN_RADIUS.toInt()}\n"
}

/** The cream stage with the sound rings around [center]. */
internal fun DrawScope.stageBackground(center: Offset = StageLayout.center) {
    drawRect(Cream)
    for (radius in RING_RADII) drawCircle(RingColor, radius, center, style = Stroke(RING_WIDTH))
}

/**
 * The stage behind a phone shot: rings, the phone's outline (a dark body; the screen is drawn over it by ffmpeg) and,
 * at [ripples] (each 0 to 1, or nothing), the vibration shown as ripples spreading from the outline.
 */
internal fun DrawScope.phoneStage(ripples: List<Float> = emptyList()) {
    stageBackground()
    for (progress in ripples) {
        if (progress !in 0f..1f) continue
        val grow = RIPPLE_START + RIPPLE_GROW * progress
        val rect = StageLayout.body.inflate(grow)
        drawRoundRect(
            color = RippleColor.copy(alpha = 0.55f * (1 - progress)),
            topLeft = rect.topLeft,
            size = rect.size,
            cornerRadius = CornerRadius(StageLayout.SCREEN_RADIUS + StageLayout.BEZEL + grow),
            style = Stroke(RIPPLE_STROKE * (1 - progress * 0.5f)),
        )
    }
    val body = StageLayout.body
    drawRoundRect(
        BezelColor,
        body.topLeft,
        body.size,
        CornerRadius(StageLayout.SCREEN_RADIUS + StageLayout.BEZEL),
    )
    drawRoundRect(
        Color.Black,
        StageLayout.screen.topLeft,
        StageLayout.screen.size,
        CornerRadius(StageLayout.SCREEN_RADIUS),
    )
}

/** The texts the video draws itself (stand-ins and end card), in English and Spanish (docs/explainer-video.md). */
internal data class VideoStrings(
    val lockDate: String,
    val now: String,
    val tagline: String,
    val finePrint: String,
    val ada: String,
    val bea: String,
    val leo: String,
    val adaReal: String,
) {
    companion object {
        fun of(language: String): VideoStrings =
            if (language == "es") {
                VideoStrings(
                    lockDate = "sábado, 24 de octubre",
                    now = "ahora",
                    tagline = "Dale voz a cada contacto.",
                    finePrint =
                        "Gratis y de código abierto · Android 17 · Necesita root y LSPosed · " +
                            "Sin relación con WhatsApp ni Meta",
                    ada = "¿Cenamos a las 8?",
                    bea = "Llego 5 min tarde",
                    leo = "Mira esto 😄",
                    adaReal = "¿Cenamos a las 8? 🍝",
                )
            } else {
                VideoStrings(
                    lockDate = "Saturday, 24 October",
                    now = "now",
                    tagline = "Give each contact a voice.",
                    finePrint =
                        "Free and open source · Android 17 · Needs root and LSPosed · " +
                            "Not affiliated with WhatsApp or Meta",
                    ada = "Dinner at 8?",
                    bea = "Running 5 min late",
                    leo = "Look at this 😄",
                    adaReal = "Dinner at 8? 🍝",
                )
            }
    }
}

private val TOUCH_RADIUS = 22.dp
private const val TOUCH_FADE_MS = 250.0
private val RING_RADII = listOf(330f, 470f, 610f, 750f)
private const val RING_WIDTH = 64f
private const val RIPPLE_START = 6f
private const val RIPPLE_GROW = 120f
private const val RIPPLE_STROKE = 10f
