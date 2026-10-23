package app.mauri.cucuto.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mauri.cucuto.core.data.voices.VoiceCharacter
import app.mauri.cucuto.ui.feedback.FeedbackBeat
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap

/**
 * A character's sprite, decoded off the main thread by Coil (PNG, WebP, GIF, AVIF, SVG and JPEG XL). [onBitmap]
 * receives the decoded picture, for seed color extraction ([app.mauri.cucuto.ui.theme.CharacterTheme]). A character
 * without a readable sprite shows a face icon.
 */
@Composable
fun CharacterSprite(
    character: VoiceCharacter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    onBitmap: ((Bitmap) -> Unit)? = null,
) {
    val sprite = character.sprite
    if (sprite == null) {
        Icon(Icons.Rounded.Face, contentDescription = contentDescription, modifier = modifier)
        return
    }
    val context = LocalPlatformContext.current
    AsyncImage(
        model =
            ImageRequest
                .Builder(context)
                .data(sprite)
                // Seed extraction reads pixels; software bitmaps avoid a copy per sprite.
                .allowHardware(onBitmap == null)
                .build(),
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier,
        onSuccess = { success -> onBitmap?.invoke(success.result.image.toBitmap()) },
    )
}

/**
 * The avatar of a chat or a character: a [CookieAvatar] holding the character's sprite, or the chat's initial (a
 * person icon when the name is unknown) while it has no voice. [contentDescription] labels the whole avatar for
 * TalkBack; pass null when the row next to it already says who it is.
 */
@Composable
fun VoiceAvatar(
    character: VoiceCharacter?,
    name: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    beat: FeedbackBeat? = null,
    speaking: Boolean = false,
    color: Color =
        if (character != null) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    onBitmap: ((Bitmap) -> Unit)? = null,
) {
    val label =
        if (contentDescription !=
            null
        ) {
            Modifier.semantics { this.contentDescription = contentDescription }
        } else {
            Modifier
        }
    val initial = initialOf(name)
    CookieAvatar(
        modifier = modifier.size(size).then(label),
        beat = beat,
        speaking = speaking,
        color = color,
        onClick = onClick,
        onClickLabel = onClickLabel,
    ) {
        when {
            character != null -> {
                CharacterSprite(
                    character = character,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().padding(size * SPRITE_INSET),
                    onBitmap = onBitmap,
                )
            }

            initial != null -> {
                Text(
                    text = initial,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (size.value * INITIAL_SCALE).sp,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }

            else -> {
                Icon(Icons.Rounded.Person, contentDescription = null, modifier = Modifier.size(size * ICON_SCALE))
            }
        }
    }
}

/** The first letter (or symbol) of [name], uppercased; null for a blank or missing name. */
internal fun initialOf(name: String?): String? {
    val trimmed = name?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val end = trimmed.offsetByCodePoints(0, 1)
    return trimmed.substring(0, end).uppercase()
}

private const val SPRITE_INSET = 0.12f
private const val INITIAL_SCALE = 0.42f
private const val ICON_SCALE = 0.5f
