package app.mauri.cucuto.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import app.mauri.cucuto.ui.feedback.FeedbackBeat
import app.mauri.cucuto.ui.theme.CucutoShapes
import app.mauri.cucuto.ui.theme.CucutoTheme
import app.mauri.cucuto.ui.theme.MorphShape

/**
 * A contact or character avatar: `Cookie9Sided` at rest, morphing to `SoftBurst` and scaling to 0.92 while
 * pressed or [speaking], with the fast spatial spring (brand/motion.md, "Shapes in motion"). When [beat] fires
 * (an assign), it morphs Cookie9Sided → SoftBurst → Cookie9Sided at that timestamp, together with the burst.
 *
 * Never a plain circle: the cookie silhouette is how people tell Cucuto's characters from WhatsApp photos.
 */
@Composable
fun CookieAvatar(
    modifier: Modifier = Modifier,
    beat: FeedbackBeat? = null,
    speaking: Boolean = false,
    color: Color = MaterialTheme.colorScheme.primaryContainer,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val morph = remember { Morph(CucutoShapes.Avatar, CucutoShapes.AvatarActive) }
    val progress = remember { Animatable(0f) }
    val scale = remember { Animatable(1f) }
    val active = pressed || speaking

    LaunchedEffect(active) {
        progress.animateTo(if (active) 1f else 0f, motion.fastSpatialSpec())
    }
    LaunchedEffect(active) {
        scale.animateTo(if (active) PRESSED_SCALE else 1f, motion.fastSpatialSpec())
    }
    LaunchedEffect(beat) {
        if (beat == null) return@LaunchedEffect
        progress.animateTo(1f, motion.fastSpatialSpec())
        progress.animateTo(if (active) 1f else 0f, motion.fastSpatialSpec())
    }

    val clickModifier =
        if (onClick != null) {
            Modifier.clickable(
                interactionSource = interactionSource,
                indication = ripple(),
                onClickLabel = onClickLabel,
                role = Role.Button,
                onClick = onClick,
            )
        } else {
            Modifier
        }
    Box(
        modifier =
            modifier
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    shape = MorphShape(morph, progress.value)
                    clip = true
                }.background(color)
                .then(clickModifier),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColorFor(color)) {
            content()
        }
    }
}

private const val PRESSED_SCALE = 0.92f

@Preview
@Composable
private fun CookieAvatarPreview() {
    CucutoTheme(dynamicColor = false) {
        Row {
            CookieAvatar(Modifier.size(72.dp)) { Icon(Icons.Rounded.Face, contentDescription = null) }
            CookieAvatar(Modifier.size(72.dp), speaking = true, color = MaterialTheme.colorScheme.tertiaryContainer) {
                Icon(Icons.Rounded.Face, contentDescription = null)
            }
        }
    }
}
