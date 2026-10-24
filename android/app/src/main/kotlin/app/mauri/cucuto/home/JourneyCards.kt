package app.mauri.cucuto.home

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.R
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.ui.components.CuckooDoor
import app.mauri.cucuto.ui.components.ParticleBurst
import app.mauri.cucuto.ui.components.VoiceAvatar
import app.mauri.cucuto.ui.feedback.Feedback
import app.mauri.cucuto.ui.feedback.FeedbackBeat
import app.mauri.cucuto.ui.feedback.LocalFeedback
import app.mauri.cucuto.ui.theme.CharacterTheme
import app.mauri.cucuto.ui.theme.CucutoTheme

/** The first-run journey's card on Home, one per [JourneyStep]. */
@Composable
internal fun JourneyCard(
    step: JourneyStep,
    onGetDemoPacks: () -> Unit,
    onImportDemoPacks: () -> Unit,
    onOpenChat: (ChatKey) -> Unit,
    onDismissCelebration: () -> Unit,
    modifier: Modifier = Modifier,
    onRestoreBackup: () -> Unit = {},
    onAddFromContacts: () -> Unit = {},
    onTrySampleChat: () -> Unit = {},
) {
    when (step) {
        JourneyStep.None -> {
            Unit
        }

        JourneyStep.ImportPacks -> {
            ImportPacksCard(onGetDemoPacks, onImportDemoPacks, onRestoreBackup, modifier)
        }

        JourneyStep.WaitForMessages -> {
            WaitForMessagesCard(onTrySampleChat, onAddFromContacts, modifier)
        }

        is JourneyStep.GiveFirstVoice -> {
            GiveFirstVoiceCard(step.chat, onOpen = { onOpenChat(step.chat.key) }, modifier)
        }

        is JourneyStep.FirstVoice -> {
            FirstVoiceCard(step.chat, onDismissCelebration, modifier)
        }
    }
}

/**
 * Step 1: no packs yet. "Get the demo packs" opens Discover, which installs the demo bundle from the default repository
 * through the usual review (#214); offline, the user can pick a demo packs file they already have. Nothing is bundled.
 * Someone moving to a new phone restores a backup from here instead (the restore flow of ADR 0028).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportPacksCard(
    onGetDemoPacks: () -> Unit,
    onImportDemoPacks: () -> Unit,
    onRestoreBackup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    JourneySurface(modifier) {
        CuckooDoor(
            beat = null,
            contentDescription = stringResource(R.string.home_empty_door),
            modifier = Modifier.size(DOOR_SIZE),
        )
        JourneyTitle(stringResource(R.string.home_journey_import_title))
        JourneyBody(stringResource(R.string.home_journey_import_body))
        Button(
            onClick = onGetDemoPacks,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.home_get_demo_packs)) }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            itemVerticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(onClick = onImportDemoPacks, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.home_journey_import_button))
            }
            TextButton(onClick = onRestoreBackup, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.home_journey_restore))
            }
        }
    }
}

/**
 * Packs are in, nobody has messaged since: say what happens next, offer the sample chat (ADR 0075: a demo that plays a
 * voice in the app, without the module, and the one way to try characters, #346), or add someone from contacts before
 * they message (ADR 0065).
 */
@Composable
private fun WaitForMessagesCard(
    onTrySampleChat: () -> Unit,
    onAddFromContacts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    JourneySurface(modifier) {
        CuckooDoor(
            beat = null,
            contentDescription = stringResource(R.string.home_empty_door),
            modifier = Modifier.size(DOOR_SIZE),
        )
        JourneyTitle(stringResource(R.string.home_journey_wait_title))
        JourneyBody(stringResource(R.string.home_journey_wait_body))
        Button(
            onClick = onTrySampleChat,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.home_journey_sample)) }
        AddFromContactsButton(onAddFromContacts, Modifier.fillMaxWidth())
    }
}

/** Step 2: someone messaged and has no voice yet. One tap opens the picker for them. */
@Composable
private fun GiveFirstVoiceCard(
    row: ChatRow,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = row.chat.name()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    JourneySurface(modifier, centered = false) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            VoiceAvatar(
                character = null,
                name = row.chat.displayName,
                contentDescription = null,
                size = 64.dp,
                speaking = pressed,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.home_journey_first_title, name),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    // Someone added from contacts has not messaged yet (ADR 0065).
                    text =
                        stringResource(
                            if (row.chat.awaitingLink) {
                                R.string.home_journey_first_body_waiting
                            } else {
                                R.string.home_journey_first_body
                            },
                            name,
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Button(
            onClick = onOpen,
            shapes = ButtonDefaults.shapes(),
            interactionSource = interaction,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.home_journey_first_button)) }
    }
}

/**
 * Step 3, the end of the journey: the cuckoo door pops open on the new character, with the door call and a burst in
 * the character's colors (`Feedback.DoorOpen`, all from one beat). Shown once, until dismissed.
 */
@Composable
private fun FirstVoiceCard(
    row: ChatRow,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val feedback = LocalFeedback.current
    var beat by remember { mutableStateOf<FeedbackBeat?>(null) }
    LaunchedEffect(row.key) { beat = feedback.fire(Feedback.DoorOpen) }
    val name = row.chat.name()
    val character = row.character
    CharacterTheme(seed = character?.seedColor?.let(::Color)) {
        Box(modifier) {
            JourneySurface {
                CuckooDoor(
                    beat = beat,
                    contentDescription = stringResource(R.string.home_journey_done_door, character?.name.orEmpty()),
                    modifier = Modifier.size(DOOR_SIZE),
                    character = {
                        VoiceAvatar(character = character, name = name, contentDescription = null, beat = beat)
                    },
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                ) {
                    JourneyTitle(stringResource(R.string.home_journey_done_title, name))
                    JourneyBody(stringResource(R.string.home_journey_done_body, name, character?.name.orEmpty()))
                }
                TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.home_journey_done_dismiss))
                }
            }
            ParticleBurst(beat, Modifier.matchParentSize())
        }
    }
}

@Composable
private fun JourneySurface(
    modifier: Modifier = Modifier,
    centered: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(
            horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(20.dp),
            content = content,
        )
    }
}

@Composable
private fun JourneyTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().semantics { heading() },
    )
}

@Composable
private fun JourneyBody(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

private val DOOR_SIZE = 140.dp

@Preview(showBackground = true)
@Composable
private fun ImportPacksPreview() {
    CucutoTheme(dynamicColor = false) {
        JourneyCard(JourneyStep.ImportPacks, {}, {}, {}, {})
    }
}

@Preview(showBackground = true)
@Composable
private fun WaitForMessagesPreview() {
    CucutoTheme(dynamicColor = false) {
        JourneyCard(JourneyStep.WaitForMessages, {}, {}, {}, {})
    }
}

@Preview(showBackground = true)
@Composable
private fun GiveFirstVoicePreview() {
    CucutoTheme(dynamicColor = false) {
        JourneyCard(JourneyStep.GiveFirstVoice(previewHomeState().recent.first()), {}, {}, {}, {})
    }
}

@Preview(showBackground = true)
@Composable
private fun FirstVoicePreview() {
    CucutoTheme(dynamicColor = false) {
        JourneyCard(JourneyStep.FirstVoice(previewHomeState().voiced.first()), {}, {}, {}, {})
    }
}
