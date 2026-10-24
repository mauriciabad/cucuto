package app.mauri.cucuto.video.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.video.BrandColors
import app.mauri.cucuto.video.Cast
import app.mauri.cucuto.video.Character
import app.mauri.cucuto.video.ShotScope
import app.mauri.cucuto.video.Spring
import app.mauri.cucuto.video.Sprite
import app.mauri.cucuto.video.Svg
import app.mauri.cucuto.video.tapped

/**
 * Shot 4: get the demo packs. One card offers the free demo packs; it is tapped at [getAt], downloads, and at
 * [installedAt] the two packs and their eight characters are there.
 */
@Composable
internal fun ShotScope.DemoPacksScreen(
    getAt: Double,
    installedAt: Double,
) {
    Screen {
        ScreenTitle("Sound packs")
        Card {
            Text("Demo packs", style = PhoneText.name, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Svg(Cast.BUNDLE_COVER, 56.dp, Modifier.clip(RoundedCornerShape(16.dp)))
                Text("Free\n8 characters", style = PhoneText.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val downloading = progress(getAt + 0.25, installedAt - 0.45)
            when {
                t < getAt + 0.2 -> {
                    PillButton("Get", Modifier.fillMaxWidth().tapped(this@DemoPacksScreen, getAt))
                }

                t < installedAt -> {
                    ProgressPill(downloading)
                }

                else -> {
                    PillButton(
                        "Installed",
                        Modifier.fillMaxWidth(),
                        color = BrandColors.SuccessContainer,
                        textColor = BrandColors.Success,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PackRow(
                Cast.CRITTERS_NAME,
                Cast.CRITTERS_COVER,
                Cast.critters,
                spring(installedAt - 0.35, Spring.DefaultSpatial),
            )
            PackRow(
                Cast.KITCHEN_NAME,
                Cast.KITCHEN_COVER,
                Cast.kitchen,
                spring(installedAt - 0.2, Spring.DefaultSpatial),
            )
        }
    }
}

@Composable
private fun ProgressPill(progress: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceAtLeast(0.08f))
                .fillMaxHeight()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
        )
        Text(
            "${(progress * 100).toInt()} %",
            style = PhoneText.label,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun PackRow(
    name: String,
    cover: String,
    characters: List<Character>,
    shown: Float,
) {
    if (shown <= 0f) return
    Column(
        Modifier
            .fillMaxWidth()
            .reveal(shown)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(name, style = PhoneText.label, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Svg(cover, 32.dp, Modifier.clip(RoundedCornerShape(9.dp)))
            characters.forEachIndexed { i, character ->
                // The characters hop in one after the other.
                Sprite(
                    character,
                    32.dp,
                    Modifier.graphicsLayer {
                        val hop = (shown * 1.4f - i * 0.1f).coerceIn(0f, 1.1f)
                        scaleX = hop
                        scaleY = hop
                    },
                )
            }
        }
    }
}
