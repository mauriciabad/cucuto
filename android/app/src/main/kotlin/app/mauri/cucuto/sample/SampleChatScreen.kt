package app.mauri.cucuto.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mauri.cucuto.R
import app.mauri.cucuto.core.data.chats.ChatVoice
import app.mauri.cucuto.core.data.voices.VoiceCharacter
import app.mauri.cucuto.ui.components.PushedScreen
import app.mauri.cucuto.ui.components.VoiceAvatar
import app.mauri.cucuto.ui.ringtone.PreviewPlaying
import app.mauri.cucuto.ui.theme.CucutoTheme

/**
 * The sample chat (ADR 0075): a demo chat, clearly labelled, to give a voice and a ringtone and hear them in the app.
 * Nothing it does reaches WhatsApp, the module, backups or Android contacts. [onOpenDiscover] opens Discover after
 * "Get the demo packs" when no pack is installed.
 */
@Composable
fun SampleChatScreen(
    onBack: () -> Unit,
    onOpenDiscover: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SampleChatViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ringing by viewModel.ringing.collectAsStateWithLifecycle()
    SampleChatContent(
        state = state,
        ringing = ringing,
        onBack = onBack,
        onChooseVoice = viewModel::chooseVoice,
        onChooseRingtone = viewModel::chooseRingtone,
        onPlayMessage = viewModel::playMessage,
        onToggleCall = viewModel::toggleCall,
        onGetDemoPacks = {
            viewModel.requestDemoPacks()
            onOpenDiscover()
        },
        modifier = modifier,
    )
}

/** Stateless: the demo banner, the chat's avatar and its two plays, then the voice and ringtone choices. */
@Composable
internal fun SampleChatContent(
    state: SampleChatState,
    ringing: PreviewPlaying<*>?,
    onBack: () -> Unit,
    onChooseVoice: (ChatVoice) -> Unit,
    onChooseRingtone: (ChatVoice?) -> Unit,
    onPlayMessage: () -> Unit,
    onToggleCall: () -> Unit,
    onGetDemoPacks: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PushedScreen(title = stringResource(R.string.sample_chat_title), onBack = onBack, modifier = modifier) { padding ->
        if (state.loading) {
            val label = stringResource(R.string.home_loading)
            LoadingIndicator(Modifier.padding(padding).padding(48.dp).semantics { contentDescription = label })
            return@PushedScreen
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier =
                Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        PaddingValues(
                            top = padding.calculateTopPadding() + 8.dp,
                            bottom = padding.calculateBottomPadding() + 24.dp,
                        ),
                    ).padding(horizontal = 16.dp),
        ) {
            DemoBanner()
            if (!state.hasCharacters) {
                EmptyPacks(onGetDemoPacks)
                return@Column
            }
            ChatHeader(state, onPlayMessage)
            if (state.playFailed) {
                Text(
                    text = stringResource(R.string.sample_chat_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            SectionTitle(stringResource(R.string.sample_chat_voice_section))
            VoiceChoices(state, onChooseVoice)
            SectionTitle(stringResource(R.string.sample_chat_ringtone_section))
            RingtoneChoices(state, ringing, onChooseRingtone, onToggleCall)
        }
    }
}

/** Says, before anything else, that this is a demo and where real chats come from. */
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
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(16.dp)) {
            Icon(Icons.Rounded.Info, contentDescription = null, modifier = Modifier.padding(top = 2.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DemoLabel()
                Text(stringResource(R.string.sample_chat_banner), style = MaterialTheme.typography.bodyMedium)
            }
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

/** The chat as Home would show it, with its voice, and "Play a sample message". */
@Composable
private fun ChatHeader(
    state: SampleChatState,
    onPlayMessage: () -> Unit,
) {
    val character = state.character
    val name = stringResource(R.string.sample_chat_title)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        VoiceAvatar(
            character = character,
            name = name,
            contentDescription = null,
            size = HEADER_AVATAR,
            speaking = state.speaking,
        )
        Text(
            text = name,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text =
                if (character != null) {
                    stringResource(R.string.sample_chat_voice_current, character.name)
                } else {
                    stringResource(R.string.sample_chat_voice_none)
                },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onPlayMessage,
            enabled = character != null,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.sample_chat_play_message))
        }
        Text(
            text = stringResource(R.string.sample_chat_play_message_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Every message character, by pack. A tap gives it to the sample chat and plays it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceChoices(
    state: SampleChatState,
    onChoose: (ChatVoice) -> Unit,
) {
    val current = state.character?.voice
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.packs.filter { it.characters.isNotEmpty() }.forEach { pack ->
            Text(
                text = pack.name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                pack.characters.forEach { character ->
                    CharacterChoice(
                        character = character,
                        selected = character.voice == current,
                        speaking = state.speaking && character.voice == current,
                        onClick = { onChoose(character.voice) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CharacterChoice(
    character: VoiceCharacter,
    selected: Boolean,
    speaking: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val selectedLabel = stringResource(R.string.picker_state_selected)
    val playingLabel = stringResource(R.string.picker_state_playing)
    // As in the picker: TalkBack (and the smoke tests) hear that the chosen character is speaking.
    val stateLabel =
        listOfNotNull(selectedLabel.takeIf { selected }, playingLabel.takeIf { speaking }).joinToString()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.width(CHOICE_WIDTH),
    ) {
        VoiceAvatar(
            character = character,
            name = character.name,
            contentDescription = character.name,
            size = CHOICE_AVATAR,
            speaking = speaking,
            color = if (selected) colors.tertiaryContainer else colors.surfaceContainerHigh,
            onClick = onClick,
            onClickLabel = stringResource(R.string.sample_chat_choose_label),
            modifier =
                Modifier.semantics {
                    this.selected = selected
                    if (stateLabel.isNotEmpty()) stateDescription = stateLabel
                },
        )
        Text(
            text = character.name,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) colors.primary else colors.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/** "Same as the voice" (when the voice has ringtones) and every character with ringtones, then the sample call. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RingtoneChoices(
    state: SampleChatState,
    ringing: PreviewPlaying<*>?,
    onChoose: (ChatVoice?) -> Unit,
    onToggleCall: () -> Unit,
) {
    val picked = state.pickedRingtone?.voice
    val active = ringing != null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.ringtonePacks.isEmpty()) {
            Hint(stringResource(R.string.sample_chat_ringtone_none))
            return@Column
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (state.voiceRingtone != null) {
                RingtoneChip(
                    label = stringResource(R.string.sample_chat_ringtone_same),
                    selected = state.ringtoneFollowsVoice,
                    onClick = { onChoose(null) },
                )
            }
            state.ringtonePacks.forEach { pack ->
                pack.characters.forEach { item ->
                    RingtoneChip(
                        label = item.character.name,
                        selected = item.voice == picked,
                        onClick = { onChoose(item.voice) },
                    )
                }
            }
        }
        if (state.ringtone == null) {
            Hint(stringResource(R.string.sample_chat_ringtone_pick))
            return@Column
        }
        FilledTonalButton(
            onClick = onToggleCall,
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
    }
}

@Composable
private fun RingtoneChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon =
            if (selected) {
                { Icon(Icons.Rounded.Check, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) }
            } else {
                null
            },
    )
}

/** No character can speak: the demo packs first. */
@Composable
private fun EmptyPacks(onGetDemoPacks: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Hint(stringResource(R.string.sample_chat_empty))
        Button(onClick = onGetDemoPacks, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.home_get_demo_packs))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val HEADER_AVATAR = 96.dp
private val CHOICE_AVATAR = 64.dp
private val CHOICE_WIDTH = 88.dp

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
