package app.mauri.cucuto.ui.ringtone

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.core.data.voices.VoiceCharacter
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.ui.components.CharacterSprite
import app.mauri.cucuto.ui.theme.CucutoTheme
import app.mauri.cucuto.ui.theme.LocalReducedMotion
import kotlinx.coroutines.isActive

/**
 * An album cover with its record (brief, "Ringtones": "tapping one plays it with a spinning-record animation"). The
 * character's sprite is the sleeve; when [pulledOut] the record slides out to the right, and while [spinning] it turns
 * like a 45 on a turntable. With animations removed the record still comes out, but stays still.
 *
 * Decorative: the cell around it carries the name and state for TalkBack. It is [size] tall and 1.3 × [size] wide, so
 * the record has room whether it is out or not and the grid never reflows.
 */
@Composable
fun RecordCover(
    character: VoiceCharacter,
    size: Dp,
    modifier: Modifier = Modifier,
    pulledOut: Boolean = false,
    spinning: Boolean = false,
    sleeveColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
) {
    val reduced = LocalReducedMotion.current
    val pull by animateFloatAsState(
        targetValue = if (pulledOut || spinning) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "record-pull",
    )
    val angle = remember { Animatable(0f) }
    LaunchedEffect(spinning, reduced) {
        if (!spinning || reduced) return@LaunchedEffect
        while (isActive) {
            angle.animateTo(angle.value + FULL_TURN, tween(TURN_MS, easing = LinearEasing))
            angle.snapTo(angle.value % FULL_TURN)
        }
    }
    val recordSize = size * RECORD_SCALE
    // In its sleeve the cover sits in the middle; as the record comes out, the sleeve moves left to make room.
    val sleeveX = size * ((WIDTH_SCALE - 1f) / 2f) * (1f - pull)
    Box(modifier.size(width = size * WIDTH_SCALE, height = size).clearAndSetSemantics {}) {
        Record(
            character = character,
            label = sleeveColor,
            modifier =
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = sleeveX + (size - recordSize) / 2 + (size * PULL_SCALE) * pull)
                    .size(recordSize)
                    .graphicsLayer { rotationZ = angle.value },
        )
        Surface(
            shape = RoundedCornerShape(size * CORNER_SCALE),
            color = sleeveColor,
            shadowElevation = 2.dp,
            modifier = Modifier.align(Alignment.CenterStart).offset(x = sleeveX).size(size),
        ) {
            CharacterSprite(
                character = character,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(size * SPRITE_INSET),
            )
        }
    }
}

/** A vinyl record: grooves, a sheen, and the character on the label. Black in both themes, like the real thing. */
@Composable
private fun Record(
    character: VoiceCharacter,
    label: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2
            drawCircle(VINYL)
            for (i in 1..GROOVES) {
                drawCircle(
                    color = GROOVE,
                    radius = radius * (LABEL_SCALE + (1f - LABEL_SCALE) * i / (GROOVES + 1)),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            // The sheen turns with the record, so the spin shows even on a symmetric disc.
            drawArc(
                color = SHEEN,
                startAngle = -60f,
                sweepAngle = 40f,
                useCenter = true,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
            )
        }
        Box(
            Modifier
                .fillMaxSize(LABEL_SCALE)
                .clip(CircleShape)
                .background(label),
            contentAlignment = Alignment.Center,
        ) {
            CharacterSprite(character, contentDescription = null, modifier = Modifier.fillMaxSize(LABEL_SPRITE))
        }
        // The spindle hole.
        Box(Modifier.size(4.dp).clip(CircleShape).background(VINYL))
    }
}

private val VINYL = Color(0xFF17171B)
private val GROOVE = Color(0x24FFFFFF)
private val SHEEN = Color(0x1FFFFFFF)
private const val GROOVES = 5
private const val LABEL_SCALE = 0.42f
private const val LABEL_SPRITE = 0.7f
private const val RECORD_SCALE = 0.94f
private const val WIDTH_SCALE = 1.3f
private const val PULL_SCALE = 0.3f
private const val CORNER_SCALE = 0.16f
private const val SPRITE_INSET = 0.1f
private const val FULL_TURN = 360f

/** 45 rpm: one turn in 1.33 s. */
private const val TURN_MS = 1_333

@Preview(showBackground = true)
@Composable
private fun RecordCoverPreview() {
    CucutoTheme(dynamicColor = false) {
        RecordCover(
            character =
                VoiceCharacter(
                    packId = "app.mauri.demo",
                    id = "frog",
                    name = "Bleep the frog",
                    sprite = null,
                    spriteAnimated = null,
                    seedColor = null,
                    clips = emptyList(),
                    haptic = Haptic.Keep,
                ),
            size = 96.dp,
            pulledOut = true,
            modifier = Modifier.padding(16.dp),
        )
    }
}
