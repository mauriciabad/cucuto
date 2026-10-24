package app.mauri.cucuto.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mauri.cucuto.R
import app.mauri.cucuto.ui.components.PushedScreen
import app.mauri.cucuto.ui.components.VoiceAvatar
import app.mauri.cucuto.ui.feedback.Feedback
import app.mauri.cucuto.ui.feedback.LocalFeedback
import app.mauri.cucuto.ui.ringtone.PreviewPlaying
import app.mauri.cucuto.ui.ringtone.RecordCover
import app.mauri.cucuto.ui.ringtone.RingtoneBadge
import app.mauri.cucuto.ui.ringtone.RingtoneDisplay
import app.mauri.cucuto.ui.ringtone.SoundRow
import app.mauri.cucuto.ui.ringtone.SoundRowIcon
import app.mauri.cucuto.ui.theme.CharacterTheme
import app.mauri.cucuto.ui.theme.CucutoTheme

/**
 * The sample chat (ADR 0075): a demo chat, clearly labelled, laid out like contact detail (#346). Its Messages and
 * Phone calls rows open the real character and ringtone pickers ([onChooseVoice], [onChooseRingtone]), which choose
 * for the sample chat; "Try it" plays the voice and the ringtone in the app. Nothing it does reaches WhatsApp, the
 * module, backups or Android contacts. [onOpenDiscover] opens Discover after "Get the demo packs" when no pack is
 * installed.
 */
@Composable
fun SampleChatScreen(
    onBack: () -> Unit,
    onOpenDiscover: () -> Unit,
    onChooseVoice: () -> Unit,
    onChooseRingtone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SampleChatViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ringing by viewModel.ringing.collectAsStateWithLifecycle()
    val feedback = LocalFeedback.current
    // A sample call never rings on behind another app or a locked screen.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.stopCall() }
    SampleChatContent(
        state = state,
        ringing = ringing,
        onBack = onBack,
        onChooseVoice = {
            feedback.fire(Feedback.Tap)
            viewModel.stopCall()
            onChooseVoice()
        },
        onChooseRingtone = {
            feedback.fire(Feedback.Tap)
            viewModel.stopCall()
            onChooseRingtone()
        },
        onPlayMessage = viewModel::playMessage,
        onToggleCall = viewModel::toggleCall,
        onGetDemoPacks = {
            viewModel.requestDemoPacks()
            onOpenDiscover()
        },
        modifier = modifier,
    )
}

/** Stateless: contact detail's header and Sounds card for the sample chat, the demo banner, then "Try it". */
@Composable
internal fun SampleChatContent(
    state: SampleChatState,
    ringing: PreviewPlaying<*>?,
    onBack: () -> Unit,
    onChooseVoice: () -> Unit,
    onChooseRingtone: () -> Unit,
    onPlayMessage: () -> Unit,
    onToggleCall: () -> Unit,
    onGetDemoPacks: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CharacterTheme(seed = state.character?.seedColor?.let { Color(it) }) {
        PushedScreen(
            title = stringResource(R.string.sample_chat_title),
            onBack = onBack,
            modifier = modifier,
        ) { padding ->
            if (state.loading) {
                val label = stringResource(R.string.home_loading)
                LoadingIndicator(Modifier.padding(padding).padding(48.dp).semantics { contentDescription = label })
                return@PushedScreen
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier =
                    Modifier
                        .fillMaxSize()
                        .consumeWindowInsets(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(
                            PaddingValues(
                                top = padding.calculateTopPadding(),
                                bottom = padding.calculateBottomPadding() + 24.dp,
                            ),
                        ).padding(horizontal = 16.dp),
            ) {
                Header(state)
                DemoBanner()
                if (!state.hasCharacters) {
                    EmptyPacks(onGetDemoPacks)
                    return@Column
                }
                SoundsCard(state, onChooseVoice, onChooseRingtone)
                TryCard(state, ringing, onPlayMessage, onToggleCall)
            }
        }
    }
}

/** Contact detail's header: the avatar with the ringtone's cover tucked against it, what it speaks and rings as. */
@Composable
private fun Header(state: SampleChatState) {
    val name = stringResource(R.string.sample_chat_title)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Box {
            VoiceAvatar(
                character = state.character,
                name = name,
                contentDescription = null,
                size = AVATAR_SIZE,
                speaking = state.speaking,
            )
            state.ringtoneDisplay?.let {
                RingtoneBadge(it, RINGTONE_BADGE_SIZE, Modifier.align(Alignment.BottomEnd).offset(x = 12.dp, y = 4.dp))
            }
        }
        Column(
            Modifier.weight(1f).padding(start = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            DemoLabel()
            val character = state.character
            Text(
                text =
                    if (character != null) {
                        stringResource(R.string.contact_speaks_as, character.name)
                    } else {
                        stringResource(R.string.sample_chat_voice_none)
                    },
                style = MaterialTheme.typography.titleMedium,
                // A new voice comes back from the picker: TalkBack says it (#226).
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            when (val ringtone = state.ringtoneDisplay) {
                is RingtoneDisplay.Character -> {
                    SecondaryText(stringResource(R.string.contact_rings_with, ringtone.character.name))
                }

                RingtoneDisplay.AndroidDefault -> {
                    SecondaryText(stringResource(R.string.contact_rings_default))
                }

                null -> {}
            }
        }
    }
}

/** Says that this is a demo and where real chats come from. */
@Composable
private fun DemoBanner() {
    Card(
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(16.dp).semantics(mergeDescendants = true) {},
        ) {
            Icon(Icons.Rounded.Info, contentDescription = null, modifier = Modifier.padding(top = 2.dp))
            Text(stringResource(R.string.sample_chat_banner), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun DemoLabel() {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.tertiary,
        contentColor = MaterialTheme.colorScheme.onTertiary,
    ) {
        Text(
            text = stringResource(R.string.sample_chat_demo_label),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * Contact detail's "Sounds" card (ADR 0063) with the same rows: "Messages" opens the character picker and "Phone
 * calls" the ringtone picker, both for the sample chat.
 */
@Composable
private fun SoundsCard(
    state: SampleChatState,
    onChooseVoice: () -> Unit,
    onChooseRingtone: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 8.dp, horizontal = 4.dp)) {
            Text(
                stringResource(R.string.contact_sounds_header),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).semantics { heading() },
            )
            val character = state.character
            val packName = state.packName
            SoundRow(
                label = stringResource(R.string.contact_row_messages),
                value =
                    when {
                        character != null && packName != null -> {
                            stringResource(R.string.contact_row_messages_value, character.name, packName)
                        }

                        character != null -> {
                            character.name
                        }

                        else -> {
                            stringResource(R.string.contact_row_messages_none)
                        }
                    },
                clickLabel =
                    stringResource(
                        if (character !=
                            null
                        ) {
                            R.string.contact_change
                        } else {
                            R.string.contact_choose
                        },
                    ),
                onClick = onChooseVoice,
                leading = {
                    if (character != null) {
                        VoiceAvatar(
                            character = character,
                            name = character.name,
                            contentDescription = null,
                            size = 48.dp,
                        )
                    } else {
                        SoundRowIcon(Icons.Rounded.Notifications)
                    }
                },
            )
            HorizontalDivider(Modifier.padding(horizontal = 12.dp))
            PhoneCallsRow(state, onChooseRingtone)
        }
    }
}

/** The "Phone calls" row, worded as contact detail's: the ringtone, and whether it follows the messages. */
@Composable
private fun PhoneCallsRow(
    state: SampleChatState,
    onChoose: () -> Unit,
) {
    val ringtone = state.ringtone
    SoundRow(
        label = stringResource(R.string.contact_row_calls),
        value =
            when {
                state.ringtoneDefault -> stringResource(R.string.ringtone_row_default)
                ringtone != null -> ringtone.character.name
                else -> stringResource(R.string.ringtone_row_none)
            },
        detail =
            when {
                state.ringtoneDefault -> stringResource(R.string.ringtone_row_default_detail)
                ringtone == null && state.ringtonePacks.isEmpty() -> stringResource(R.string.sample_chat_ringtone_none)
                ringtone == null -> null
                state.ringtoneFollowsVoice -> stringResource(R.string.ringtone_row_follows)
                else -> stringResource(R.string.ringtone_row_own)
            },
        clickLabel = stringResource(R.string.ringtone_choose),
        onClick = onChoose,
        leading = {
            if (ringtone != null) {
                RecordCover(ringtone.character, size = 48.dp)
            } else {
                SoundRowIcon(Icons.Rounded.Call)
            }
        },
    )
}

/** What contact detail's Simulate message card is for a real chat: the sample message, and the sample call. */
@Composable
private fun TryCard(
    state: SampleChatState,
    ringing: PreviewPlaying<*>?,
    onPlayMessage: () -> Unit,
    onToggleCall: () -> Unit,
) {
    val character = state.character
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.sample_chat_try_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            val playing = stringResource(R.string.a11y_playing)
            Button(
                onClick = onPlayMessage,
                enabled = character != null,
                shapes = ButtonDefaults.shapes(),
                modifier =
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics {
                        if (state.speaking) stateDescription = playing
                    },
            ) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.sample_chat_play_message))
            }
            CardText(
                stringResource(
                    if (character != null) {
                        R.string.sample_chat_play_message_hint
                    } else {
                        R.string.sample_chat_play_message_needs_voice
                    },
                ),
            )
            val active = ringing != null
            FilledTonalButton(
                onClick = onToggleCall,
                enabled = state.ringtone != null,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    if (active) Icons.Rounded.Close else Icons.Rounded.Call,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(if (active) R.string.sample_chat_stop_call else R.string.sample_chat_play_call))
            }
            if (state.ringtoneDefault) CardText(stringResource(R.string.sample_chat_ringtone_default))
            if (state.playFailed) {
                Text(
                    text = stringResource(R.string.sample_chat_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

/** No character can speak: the demo packs first. */
@Composable
private fun EmptyPacks(onGetDemoPacks: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 8.dp)) {
        SecondaryText(stringResource(R.string.sample_chat_empty))
        Button(onClick = onGetDemoPacks, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.home_get_demo_packs))
        }
    }
}

@Composable
private fun SecondaryText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CardText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
    )
}

private val AVATAR_SIZE = 96.dp
private val RINGTONE_BADGE_SIZE = 44.dp

@Preview(showBackground = true)
@Composable
private fun SampleChatPreview() {
    CucutoTheme(dynamicColor = false) {
        SampleChatContent(
            state = SampleChatState(loading = false),
            ringing = null,
            onBack = {},
            onChooseVoice = {},
            onChooseRingtone = {},
            onPlayMessage = {},
            onToggleCall = {},
            onGetDemoPacks = {},
        )
    }
}
