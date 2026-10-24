package app.mauri.cucuto.packs.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mauri.cucuto.R
import app.mauri.cucuto.packs.LibraryCharacter
import app.mauri.cucuto.ui.components.CookieAvatar
import app.mauri.cucuto.ui.components.adaptiveMaxLines
import app.mauri.cucuto.ui.ringtone.MediaRingtonePreview
import app.mauri.cucuto.ui.ringtone.PreviewPlaying
import app.mauri.cucuto.ui.ringtone.RingtonePlayButton
import app.mauri.cucuto.ui.ringtone.RingtonePlayback
import app.mauri.cucuto.ui.theme.CharacterTheme

/**
 * One character of a pack's detail screen (ADR 0063, #246): its picture, its name and what it offers (messages, a
 * ringtone, or both). A tap on the picture plays a clip ([clips]), or rings the song of a ringtone-only character;
 * the buttons ring its ringtone like a call, from the start until stopped, with pause and progress ([ringtones]).
 */
@Composable
fun CharacterRow(
    character: LibraryCharacter,
    key: String,
    clips: ClipPlayer,
    ringtones: RingtonePlayback<String>,
    modifier: Modifier = Modifier,
) {
    val playing by ringtones.playing.collectAsStateWithLifecycle()
    CharacterRowContent(
        character = character,
        speaking = clips.playingKey == key,
        ringtone = playing?.takeIf { it.key == key },
        onPlayClip = {
            ringtones.stop()
            clips.play(key, character.clips)
        },
        onToggleRingtone = {
            clips.stop()
            character.ringtones.firstOrNull()?.let { ringtones.toggle(key, it) }
        },
        onStopRingtone = ringtones::stop,
        modifier = modifier,
    )
}

/** Stateless [CharacterRow]: [ringtone] is its playing preview, or null. */
@Composable
fun CharacterRowContent(
    character: LibraryCharacter,
    speaking: Boolean,
    ringtone: PreviewPlaying<String>?,
    onPlayClip: () -> Unit,
    onToggleRingtone: () -> Unit,
    modifier: Modifier = Modifier,
    onStopRingtone: () -> Unit = {},
) {
    val song = character.ringtones.firstOrNull()
    val playing = ringtone != null && !ringtone.paused
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.fillMaxWidth().heightIn(min = 72.dp).padding(vertical = 4.dp),
    ) {
        CharacterTheme(seed = character.seedColor?.let(::Color)) {
            val onClick =
                when {
                    character.clips.isNotEmpty() -> onPlayClip
                    song != null -> onToggleRingtone
                    else -> null
                }
            CookieAvatar(
                modifier = Modifier.size(56.dp).semantics { contentDescription = character.name },
                speaking = speaking || (playing && character.clips.isEmpty()),
                color = MaterialTheme.colorScheme.primaryContainer,
                onClick = onClick,
                onClickLabel = stringResource(R.string.packs_play_character, character.name),
            ) {
                CharacterPicture(character, Modifier.fillMaxSize().padding(7.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                character.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = adaptiveMaxLines(2),
                overflow = TextOverflow.Ellipsis,
            )
            offersLabel(character)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (song != null) {
            RingtonePlayButton(
                preview = ringtone,
                name = character.name,
                onToggle = onToggleRingtone,
                onStop = onStopRingtone,
            )
        }
    }
}

/**
 * Ringtone previews for the Packs screens (#246): the song rings like a call, from its start until stopped, with
 * ringtone audio attributes. Stops when it leaves the composition or the screen stops.
 */
@Composable
fun rememberRingtonePlayback(): RingtonePlayback<String> {
    val scope = rememberCoroutineScope()
    val playback = remember(scope) { RingtonePlayback<String>(scope, MediaRingtonePreview()) }
    DisposableEffect(playback) { onDispose { playback.stop() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { playback.stop() }
    return playback
}
