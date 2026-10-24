package app.mauri.cucuto.ui.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.R
import app.mauri.cucuto.packs.LibraryCharacter
import app.mauri.cucuto.packs.ui.CharacterPicture
import app.mauri.cucuto.ui.components.CookieAvatar
import app.mauri.cucuto.ui.components.adaptiveMaxLines
import app.mauri.cucuto.ui.theme.CharacterTheme
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import java.io.File

/**
 * A repository character, like [app.mauri.cucuto.packs.ui.CharacterBadge] on the Packs screen: its picture in a
 * cookie avatar in its own colors, its name below, and a tap plays its sample clip. The picture is fetched when the
 * badge is shown, the clip only when tapped; the avatar morphs while the clip loads and plays. The character's
 * vibration starts with the clip ([SamplePlayer], ADR 0069).
 */
@Composable
internal fun RemoteCharacterBadge(
    character: RemoteCharacter,
    repositoryId: Long,
    previews: PreviewLoader,
    player: SamplePlayer,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
) {
    val sprite by produceState<File?>(null, character.sprite, repositoryId) {
        value = character.sprite?.let { previews.load(it, repositoryId) }
    }
    val scope = rememberCoroutineScope()
    var fetching by remember { mutableStateOf(false) }
    val sample = character.sample
    val play: (() -> Unit)? =
        sample?.let {
            {
                if (!fetching) {
                    fetching = true
                    scope.launch {
                        try {
                            previews.load(it, repositoryId)?.let { clip -> player.play(character, clip) }
                        } finally {
                            fetching = false
                        }
                    }
                }
            }
        }
    // CharacterPicture draws local files; only the sprite matters here.
    val picture = LibraryCharacter(character.key, character.name, sprite, character.seedColor, emptyList(), 0)
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
                speaking = fetching || player.playingKey == character.key,
                color = MaterialTheme.colorScheme.primaryContainer,
                onClick = play,
                onClickLabel = stringResource(R.string.packs_play_character, character.name),
            ) {
                CharacterPicture(picture, Modifier.fillMaxSize().padding(size / 9))
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
    }
}

/** A cover ([url]) of repository [repositoryId], fetched when shown, or [name]'s initial on a cookie while missing. */
@Composable
internal fun RemoteCover(
    url: HttpUrl?,
    name: String,
    repositoryId: Long,
    previews: PreviewLoader,
    modifier: Modifier = Modifier,
) {
    val cover by produceState<File?>(null, url, repositoryId) {
        value = url?.let { previews.load(it, repositoryId) }
    }
    val file = cover
    if (file != null) {
        AsyncImage(
            model = file,
            contentDescription = null,
            modifier = modifier.clip(RoundedCornerShape(16.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        Surface(
            modifier = modifier,
            shape = MaterialShapes.Cookie4Sided.toShape(),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    // Decorative: the card's title says the name.
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
    }
}
