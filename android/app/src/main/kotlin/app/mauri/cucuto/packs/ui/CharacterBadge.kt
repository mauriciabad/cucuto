package app.mauri.cucuto.packs.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.RenderVectorGroup
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.R
import app.mauri.cucuto.packs.LibraryCharacter
import app.mauri.cucuto.ui.components.CookieAvatar
import app.mauri.cucuto.ui.theme.CharacterTheme
import coil3.compose.AsyncImage

/**
 * A character's picture in a cookie avatar, in the character's own colors, with its name below. Tapping plays one of
 * its clips through [player]; the avatar morphs while the clip plays.
 */
@Composable
fun CharacterBadge(
    character: LibraryCharacter,
    playerKey: String,
    player: ClipPlayer,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
) {
    val canPlay = character.clips.isNotEmpty()
    Column(
        modifier = modifier.width(size + 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CharacterTheme(seed = character.seedColor?.let(::Color)) {
            CookieAvatar(
                modifier =
                    Modifier
                        .size(size)
                        .semantics { contentDescription = character.name },
                speaking = player.playingKey == playerKey,
                color = MaterialTheme.colorScheme.primaryContainer,
                onClick = if (canPlay) ({ player.play(playerKey, character.clips) }) else null,
                onClickLabel = stringResource(R.string.packs_play_character, character.name),
            ) {
                CharacterPicture(character, Modifier.fillMaxSize().padding(size / 9))
            }
        }
        Text(
            text = character.name,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The character's sprite (any format the importer accepts, JPEG XL and SVG included), or a face while missing. */
@Composable
fun CharacterPicture(
    character: LibraryCharacter,
    modifier: Modifier = Modifier,
) {
    val face = Icons.Rounded.Face
    val fallback =
        rememberVectorPainter(
            defaultWidth = face.defaultWidth,
            defaultHeight = face.defaultHeight,
            viewportWidth = face.viewportWidth,
            viewportHeight = face.viewportHeight,
            name = face.name,
            tintColor = LocalContentColor.current,
            tintBlendMode = BlendMode.SrcIn,
            autoMirror = false,
        ) { _, _ -> RenderVectorGroup(group = face.root) }
    AsyncImage(
        model = character.sprite,
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit,
        placeholder = fallback,
        error = fallback,
        fallback = fallback,
    )
}
