package app.mauri.cucuto.video.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.toPath
import app.mauri.cucuto.video.Cast
import app.mauri.cucuto.video.Character
import app.mauri.cucuto.video.Cookie
import app.mauri.cucuto.video.ShotScope
import app.mauri.cucuto.video.Spring
import app.mauri.cucuto.video.Sprite
import app.mauri.cucuto.video.burst
import app.mauri.cucuto.video.burstColors
import app.mauri.cucuto.video.mixWhite
import app.mauri.cucuto.video.soundArcs
import app.mauri.cucuto.video.tapped

/**
 * Shot 6: choosing Ada's voice. The eight demo characters in a grid; each tap in [taps] plays one (it squashes and
 * speaks). The last one tapped is selected ("Tap again"); tapping it again at [assignAt] assigns it: the tile morphs,
 * the burst fires in its colors, and a snackbar confirms at [snackbarAt].
 */
@Composable
internal fun ShotScope.PickerScreen(
    taps: List<Pair<Double, Character>>,
    assignAt: Double,
    snackbarAt: Double,
) {
    val chosen = taps.last().second
    Box(Modifier.fillMaxSize()) {
        Screen {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val assigned = spring(assignAt, Spring.FastSpatial)
                // Cookie → soft burst → cookie as the voice lands (brand/motion.md).
                val pulse = (assigned - spring(assignAt + 0.25, Spring.FastSpatial)).coerceIn(0f, 1f)
                Cookie(44.dp, if (after(assignAt)) chosen.seed.mixWhite(0.55f) else Cast.ada.tint, burst = pulse) {
                    if (after(assignAt)) {
                        Sprite(
                            chosen,
                            34.dp,
                            Modifier.graphicsLayer {
                                scaleX = assigned
                                scaleY = assigned
                            },
                        )
                    } else {
                        Text("A", style = PhoneText.name, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                Text("Voice for Ada", style = PhoneText.name, color = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Cast.all.chunked(2).forEach { row ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.zIndex(if (chosen in row) 1f else 0f),
                    ) {
                        row.forEach { character ->
                            Tile(
                                character,
                                tapsOn =
                                    taps.filter { it.second == character }.map { it.first } +
                                        (if (character == chosen) listOf(assignAt) else emptyList()),
                                selectedFrom = if (character == chosen) taps.last().first else null,
                                assignAt = if (character == chosen) assignAt else null,
                                modifier = Modifier.weight(1f).zIndex(if (character == chosen) 1f else 0f),
                            )
                        }
                    }
                }
            }
        }
        Snackbar(
            "${chosen.shortName} now speaks for Ada",
            spring(snackbarAt, Spring.DefaultSpatial),
            Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun ShotScope.Tile(
    character: Character,
    tapsOn: List<Double>,
    selectedFrom: Double?,
    assignAt: Double?,
    modifier: Modifier = Modifier,
) {
    val speaking = tapsOn.lastOrNull { t >= it }
    val assigned = assignAt != null && after(assignAt)
    val selected = selectedFrom != null && after(selectedFrom) && !assigned
    val glow = speaking?.let { window(it, it + 0.9) } ?: 0f
    val morph = if (assignAt != null) spring(assignAt, Spring.FastSpatial) else 0f
    val shape: Shape = if (morph > 0f) TileMorphShape(morph.coerceIn(0f, 1.05f)) else RoundedCornerShape(26.dp)
    val background =
        when {
            assigned -> character.seed.mixWhite(0.6f)
            else -> lerp(MaterialTheme.colorScheme.surfaceContainerLow, character.seed.mixWhite(0.75f), glow)
        }
    Box(
        modifier
            .height(88.dp)
            .drawWithContent {
                drawContent()
                if (assignAt != null) burst(center, t - assignAt, character.burstColors(), reach = 0.5f)
            }.tapped(this, *tapsOn.toDoubleArray(), touchAlign = Offset(0.62f, 0.45f))
            .clip(shape)
            .background(background)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val squash = tapsOn.fold(1f) { acc, at -> acc * squash(at, 0.2f) }
            Box(
                Modifier.drawWithContent {
                    soundArcs(
                        center,
                        size.width * 0.5f,
                        speaking?.let { progress(it, it + 0.8) } ?: 0f,
                        character.seed,
                        3.dp.toPx(),
                    )
                    drawContent()
                },
            ) {
                Sprite(
                    character,
                    50.dp,
                    Modifier.graphicsLayer {
                        scaleX = 1f / squash
                        scaleY = squash
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    },
                )
            }
            Text(
                if (selected) "Tap again" else character.shortName,
                style = PhoneText.label,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

/** The picker tile's shape on assign: a rounded square morphing to a four-sided cookie (brand/README.md). */
private class TileMorphShape(
    private val progress: Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val path = TileMorph.toPath(progress).asComposePath()
        path.transform(Matrix().apply { scale(size.width, size.height) })
        return Outline.Generic(path)
    }
}

private val TileMorph by lazy { Morph(MaterialShapes.Square, MaterialShapes.Cookie4Sided) }

@Composable
private fun Snackbar(
    text: String,
    shown: Float,
    modifier: Modifier = Modifier,
) {
    if (shown <= 0f) return
    Box(
        modifier
            .padding(start = 12.dp, end = 12.dp, bottom = 22.dp)
            .fillMaxWidth()
            .graphicsLayer {
                translationY = (1 - shown) * 80.dp.toPx()
                alpha = shown.coerceIn(0f, 1f)
            }.clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF3B2A24))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(text, style = PhoneText.body, color = Color(0xFFFFEDE5))
    }
}
