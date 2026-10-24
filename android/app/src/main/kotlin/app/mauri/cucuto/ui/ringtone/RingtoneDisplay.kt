package app.mauri.cucuto.ui.ringtone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import app.mauri.cucuto.R
import app.mauri.cucuto.core.data.chats.Chat
import app.mauri.cucuto.core.data.chats.ChatRingtone
import app.mauri.cucuto.core.data.chats.ChatVoice
import app.mauri.cucuto.core.data.voices.VoiceCharacter

/** What a chat rings with for phone calls, as Home's rows and the contact header show it (ADR 0073). */
@Immutable
sealed interface RingtoneDisplay {
    /** A character with ringtones: its album cover. */
    data class Character(
        val character: VoiceCharacter,
    ) : RingtoneDisplay

    /** The phone's default ringtone. */
    data object AndroidDefault : RingtoneDisplay

    /** The ringtone set in Contacts after Cucuto, which Cucuto respects for now (#372). */
    data object SetInContacts : RingtoneDisplay
}

/**
 * What [chat] rings with, or null when there is nothing to show: a group (no phone ringtone, #52), "Leave as is", or a
 * character without ringtones. A ringtone changed in Contacts that Cucuto respects is that one, never the stored
 * choice (#372). [find] looks an installed character up.
 */
fun ringtoneDisplay(
    chat: Chat?,
    find: (ChatVoice) -> VoiceCharacter?,
): RingtoneDisplay? {
    if (chat == null || chat.isGroup) return null
    if (chat.ringtoneSetInContacts) return RingtoneDisplay.SetInContacts
    if (chat.ringtone == ChatRingtone.AndroidDefault) return RingtoneDisplay.AndroidDefault
    val character = chat.ringingVoice?.let(find)?.takeIf { it.forCalls } ?: return null
    return RingtoneDisplay.Character(character)
}

/** What TalkBack says about [display]: "Ringtone: K.K. Slider", "Ringtone: phone default". */
@Composable
fun ringtoneDescription(display: RingtoneDisplay): String =
    when (display) {
        is RingtoneDisplay.Character -> stringResource(R.string.home_chat_ringtone_a11y, display.character.name)
        RingtoneDisplay.AndroidDefault -> stringResource(R.string.home_chat_ringtone_default_a11y)
        RingtoneDisplay.SetInContacts -> stringResource(R.string.home_chat_ringtone_contacts_a11y)
    }

/** A small picture of [display]: the album cover, or a call icon for the phone's default or Contacts'. Decorative. */
@Composable
fun RingtoneBadge(
    display: RingtoneDisplay,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    when (display) {
        is RingtoneDisplay.Character -> {
            RecordCover(display.character, size = size, modifier = modifier)
        }

        RingtoneDisplay.AndroidDefault, RingtoneDisplay.SetInContacts -> {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = modifier.size(size),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        CallIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(size * ICON_SCALE),
                    )
                }
            }
        }
    }
}

private const val ICON_SCALE = 0.55f
