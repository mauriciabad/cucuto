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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mauri.cucuto.R
import app.mauri.cucuto.packs.LibraryCharacter
import app.mauri.cucuto.ui.components.CookieAvatar
import app.mauri.cucuto.ui.components.adaptiveMaxLines
import app.mauri.cucuto.ui.ringtone.RingtonePlayback
import app.mauri.cucuto.ui.theme.CharacterTheme
import coil3.compose.AsyncImage

/**
 * A character's picture in a cookie avatar, in the character's own colors, with its name and what it offers below
 * (messages, ringtone, or both: ADR 0063). Tapping plays one of its clips through [player]. A ringtone-only
 * character rings its song through [ringtones] like a call, from the start until tapped again (#246, #242); the
 * avatar morphs while it plays.
 */
@Composable
fun CharacterBadge(
    character: LibraryCharacter,
    playerKey: String,
    player: ClipPlayer,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    ringtones: RingtonePlayback<String>? = null,
) {
    val song = character.ringtones.firstOrNull().takeIf { character.clips.isEmpty() && ringtones != null }
    val ringing =
        ringtones
            ?.playing
            ?.collectAsStateWithLifecycle()
            ?.value
            ?.key == playerKey
    val canPlay = character.clips.isNotEmpty() || song != null
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
                speaking = player.playingKey == playerKey || ringing,
                color = MaterialTheme.colorScheme.primaryContainer,
                onClick =
                    when {
                        song != null && ringtones != null -> (
                            {
                                player.stop()
                                if (ringing) ringtones.stop() else ringtones.play(playerKey, song)
                            }
                        )

                        canPlay -> (
                            {
                                ringtones?.stop()
                                player.play(playerKey, character.clips)
                            }
                        )

                        else -> null
                    },
                onClickLabel = stringResource(R.string.packs_play_character, character.name),
            ) {
                CharacterPicture(character, Modifier.fillMaxSize().padding(size / 9))
            }
        }
        Text(
            text = character.name,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = adaptiveMaxLines(2),
            overflow = TextOverflow.Ellipsis,
            // The avatar above already says the name to TalkBack.
            modifier = Modifier.clearAndSetSemantics {},
        )
        offersLabel(character)?.let { offers ->
            Text(
                text = offers,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = adaptiveMaxLines(2),
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** What [character] offers: messages, a ringtone, or both (ADR 0063); null when unknown (no clips, no ringtones). */
@Composable
fun offersLabel(character: LibraryCharacter): String? =
    when {
        character.forMessages && character.forCalls -> stringResource(R.string.packs_offers_both)
        character.forCalls -> stringResource(R.string.packs_offers_ringtone)
        character.forMessages -> stringResource(R.string.packs_offers_messages)
        else -> null
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
