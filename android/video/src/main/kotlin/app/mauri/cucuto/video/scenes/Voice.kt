package app.mauri.cucuto.video.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.video.Cast
import app.mauri.cucuto.video.Cookie
import app.mauri.cucuto.video.CuckooHouse
import app.mauri.cucuto.video.HouseColors
import app.mauri.cucuto.video.ShotScope
import app.mauri.cucuto.video.Spring
import app.mauri.cucuto.video.Sprite
import app.mauri.cucuto.video.burst
import app.mauri.cucuto.video.burstColors
import app.mauri.cucuto.video.doorPose
import app.mauri.cucuto.video.mixWhite
import app.mauri.cucuto.video.soundArcs
import app.mauri.cucuto.video.tapped

/** Shot 7: Ada has a voice. The cuckoo door, in the frog's colors, pops open at [opensAt] on Bleep the frog. */
@Composable
internal fun ShotScope.FirstVoiceScreen(opensAt: Double) {
    val frog = Cast.frog
    Screen {
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .fillMaxWidth()
                .drawBehind { burst(center, t - opensAt - 0.1, frog.burstColors(), reach = 0.6f) },
            contentAlignment = Alignment.Center,
        ) {
            CuckooHouse(
                pose = doorPose(opensAt),
                height = 180.dp,
                colors = HouseColors.of(frog),
                character = frog,
                modifier = Modifier.reveal(spring(0.0, Spring.SlowSpatial)),
            )
        }
        Spacer(Modifier.height(22.dp))
        Column(
            Modifier.fillMaxWidth().reveal(spring(opensAt + 0.3, Spring.DefaultSpatial)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Ada has a voice",
                style = PhoneText.title,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(frog.name, style = PhoneText.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1.3f))
    }
}

/**
 * Shot 8: Ada's card, now with the frog. "Simulate message" is tapped at [tapAt] and at [playsAt] the frog plays
 * through the same path as a real message: sound, and the vibration around the phone.
 */
@Composable
internal fun ShotScope.SimulateScreen(
    tapAt: Double,
    playsAt: Double,
) {
    val frog = Cast.frog
    Screen {
        Column(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val pulse =
                (
                    spring(
                        playsAt,
                        Spring.FastSpatial,
                    ) - spring(playsAt + 0.3, Spring.FastSpatial)
                ).coerceIn(0f, 1f)
            Box(
                Modifier.drawBehind {
                    soundArcs(center, size.width * 0.62f, progress(playsAt, playsAt + 1.0), frog.seed, 4.dp.toPx())
                },
            ) {
                Cookie(124.dp, frog.seed.mixWhite(0.6f), burst = pulse) {
                    val squash = squash(playsAt, 0.18f)
                    Sprite(
                        frog,
                        88.dp,
                        Modifier.graphicsLayer {
                            scaleX = 1f / squash
                            scaleY = squash
                            transformOrigin = TransformOrigin(0.5f, 1f)
                        },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Ada", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(frog.name, style = PhoneText.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        val status = fade(tapAt + 0.15)
        if (status > 0f) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .reveal(status)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Text(
                    if (after(playsAt)) "Playing, like a real message" else "Sending a test message…",
                    style = PhoneText.body,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(10.dp))
        }
        PillButton("Simulate message", Modifier.fillMaxWidth().tapped(this@SimulateScreen, tapAt))
    }
}
