package app.mauri.cucuto.ui.ringtone

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mauri.cucuto.R
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.db.RingtoneMode
import app.mauri.cucuto.core.ringtone.contacts.AndroidContact
import app.mauri.cucuto.core.ringtone.sync.RingtoneProblem
import app.mauri.cucuto.ui.feedback.Feedback
import app.mauri.cucuto.ui.feedback.LocalFeedback
import app.mauri.cucuto.ui.theme.CucutoTheme

/** What the section can ask for; the stateful [PhoneRingtoneSection] wires it to its view model. */
data class PhoneRingtoneActions(
    val onTurnOn: () -> Unit = {},
    val onLink: (AndroidContact) -> Unit = {},
    val onPickContact: () -> Unit = {},
    val onUnlink: () -> Unit = {},
    val onUse: () -> Unit = {},
    val onMode: (RingtoneMode) -> Unit = {},
    val onVariant: (String) -> Unit = {},
    val onStop: () -> Unit = {},
    val onTakeOver: () -> Unit = {},
    val onAllowPhone: () -> Unit = {},
)

/**
 * The phone ringtone (#52) of [chat], as a self-contained card for the contact detail screen: turn the feature on
 * (with the contacts permission explained first), link the Android contact (suggested by phone number, or picked),
 * choose how the version is picked, and see what rings. The album-cover picker and previews are #53.
 */
@Composable
fun PhoneRingtoneSection(
    chat: ChatKey,
    modifier: Modifier = Modifier,
) {
    val viewModel =
        hiltViewModel<PhoneRingtoneViewModel, PhoneRingtoneViewModel.Factory>(
            key = "phone-ringtone:${chat.packageName}:${chat.conversationKey}",
            creationCallback = { factory -> factory.create(chat) },
        )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val turnOn = rememberContactsPermissionFlow(onGrant = viewModel::enable)
    val allowPhone =
        rememberPhonePermissionFlow { viewModel.permissionsChanged() }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
            uri?.let { viewModel.linkPicked(it.toString()) }
        }
    LaunchedEffect(state.enabled, state.linked, state.contactId) {
        if (state.enabled && !state.linked && state.contactId != null) viewModel.loadSuggestions()
    }
    PhoneRingtoneContent(
        state = state,
        actions =
            PhoneRingtoneActions(
                onTurnOn = turnOn,
                onLink = viewModel::link,
                onPickContact = { runCatching { picker.launch(null) } },
                onUnlink = viewModel::unlink,
                onUse = viewModel::use,
                onMode = { mode ->
                    viewModel.setMode(mode)
                    if (mode == RingtoneMode.RANDOM) allowPhone()
                },
                onVariant = viewModel::setVariant,
                onStop = viewModel::stop,
                onTakeOver = viewModel::takeOver,
                onAllowPhone = allowPhone,
            ),
        modifier = modifier,
    )
}

/** Stateless phone ringtone card, for previews and UI tests. */
@Composable
fun PhoneRingtoneContent(
    state: PhoneRingtoneUiState,
    actions: PhoneRingtoneActions,
    modifier: Modifier = Modifier,
) {
    val feedback = LocalFeedback.current

    fun tap(action: () -> Unit): () -> Unit =
        {
            feedback.fire(Feedback.Tap)
            action()
        }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.ringtone_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            if (state.loading) return@Column
            when {
                !state.hasCharacter -> {
                    Body(stringResource(R.string.ringtone_no_character))
                }

                state.variants.isEmpty() -> {
                    Body(stringResource(R.string.ringtone_no_ringtones, state.characterName.orEmpty()))
                }

                !state.enabled -> {
                    Body(stringResource(R.string.ringtone_off_body))
                    FilledTonalButton(
                        onClick = tap(actions.onTurnOn),
                    ) { Text(stringResource(R.string.ringtone_turn_on)) }
                }

                !state.linked -> {
                    LinkPrompt(state, actions, ::tap)
                }

                else -> {
                    Linked(state, actions, ::tap)
                }
            }
        }
    }
}

@Composable
private fun LinkPrompt(
    state: PhoneRingtoneUiState,
    actions: PhoneRingtoneActions,
    tap: (() -> Unit) -> () -> Unit,
) = Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Body(stringResource(R.string.ringtone_link_prompt))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (contact in state.suggestions) {
            AssistChip(
                onClick = tap { actions.onLink(contact) },
                label = {
                    Text(
                        stringResource(
                            R.string.ringtone_link_suggestion,
                            contact.displayName ?: contact.lookupKey.take(8),
                        ),
                    )
                },
            )
        }
        OutlinedButton(onClick = tap(actions.onPickContact)) { Text(stringResource(R.string.ringtone_pick_contact)) }
    }
}

@Composable
private fun Linked(
    state: PhoneRingtoneUiState,
    actions: PhoneRingtoneActions,
    tap: (() -> Unit) -> () -> Unit,
) = Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            state.linkedName?.let { stringResource(R.string.ringtone_linked, it) }
                ?: stringResource(R.string.ringtone_linked_unnamed),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = tap(actions.onUnlink)) { Text(stringResource(R.string.ringtone_change_contact)) }
    }
    if (!state.using) {
        FilledTonalButton(onClick = tap(actions.onUse)) {
            Text(stringResource(R.string.ringtone_use, state.characterName.orEmpty()))
        }
        return@Column
    }
    if (state.modes.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (mode in state.modes) {
                FilterChip(
                    selected = state.mode == mode,
                    onClick = tap { if (state.mode != mode) actions.onMode(mode) },
                    label = { Text(stringResource(mode.label)) },
                )
            }
        }
        Body(stringResource(state.mode.body))
    }
    if (state.mode == RingtoneMode.FIXED && state.variants.size > 1) {
        val selected = state.fixedVariant ?: state.variants.first().file
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (variant in state.variants) {
                FilterChip(
                    selected = selected == variant.file,
                    onClick = tap { actions.onVariant(variant.file) },
                    label = { Text(variant.label) },
                )
            }
        }
    }
    Text(
        status(state),
        style = MaterialTheme.typography.bodyMedium,
        color =
            if (state.problem !=
                null
            ) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    if (state.problem == RingtoneProblem.ChangedElsewhere) {
        FilledTonalButton(onClick = tap(actions.onTakeOver)) { Text(stringResource(R.string.ringtone_take_over)) }
    }
    if (state.needsPhonePermission) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Body(stringResource(R.string.ringtone_phone_hint), Modifier.weight(1f))
            TextButton(onClick = tap(actions.onAllowPhone)) { Text(stringResource(R.string.ringtone_phone_allow)) }
        }
    }
    Text(
        state.exportTitle?.let { stringResource(R.string.ringtone_whatsapp_hint, it) }
            ?: stringResource(R.string.ringtone_whatsapp_hint_generic),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = tap(actions.onStop)) { Text(stringResource(R.string.ringtone_stop)) }
}

@Composable
private fun status(state: PhoneRingtoneUiState): String =
    when {
        state.problem == RingtoneProblem.ContactMissing -> {
            stringResource(R.string.ringtone_problem_contact_missing)
        }

        state.problem == RingtoneProblem.ChangedElsewhere -> {
            stringResource(R.string.ringtone_problem_changed)
        }

        state.problem == RingtoneProblem.NoPermission -> {
            stringResource(R.string.ringtone_problem_permission)
        }

        state.problem == RingtoneProblem.WriteFailed -> {
            stringResource(R.string.ringtone_problem_write)
        }

        state.syncing && state.ringingWith == null -> {
            stringResource(R.string.ringtone_status_preparing)
        }

        state.problem == RingtoneProblem.RenderFailed && state.ringingWith == null -> {
            stringResource(
                R.string.ringtone_problem_render,
            )
        }

        state.ringingWith != null -> {
            stringResource(R.string.ringtone_status_ringing, state.ringingWith)
        }

        else -> {
            stringResource(R.string.ringtone_status_preparing)
        }
    }

@Composable
private fun Body(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

private val RingtoneMode.label: Int
    get() =
        when (this) {
            RingtoneMode.PACK -> R.string.ringtone_rule_pack
            RingtoneMode.RANDOM -> R.string.ringtone_rule_random
            RingtoneMode.TIME -> R.string.ringtone_rule_time
            RingtoneMode.SEASON -> R.string.ringtone_rule_season
            RingtoneMode.FIXED -> R.string.ringtone_rule_fixed
        }

private val RingtoneMode.body: Int
    get() =
        when (this) {
            RingtoneMode.PACK -> R.string.ringtone_rule_pack_body
            RingtoneMode.RANDOM -> R.string.ringtone_rule_random_body
            RingtoneMode.TIME -> R.string.ringtone_rule_time_body
            RingtoneMode.SEASON -> R.string.ringtone_rule_season_body
            RingtoneMode.FIXED -> R.string.ringtone_rule_fixed_body
        }

@Preview(showBackground = true)
@Composable
private fun PhoneRingtonePreview() {
    CucutoTheme(dynamicColor = false) {
        PhoneRingtoneContent(
            state =
                PhoneRingtoneUiState(
                    loading = false,
                    enabled = true,
                    contactId = 1,
                    characterName = "K.K. Slider",
                    hasCharacter = true,
                    variants = listOf(VariantChip("day.ogg", "Live"), VariantChip("night.ogg", "Radio")),
                    linked = true,
                    linkedName = "Ada",
                    using = true,
                    modes = listOf(RingtoneMode.PACK, RingtoneMode.RANDOM, RingtoneMode.TIME, RingtoneMode.FIXED),
                    ringingWith = "Live",
                    exportTitle = "K.K. Slider · Live",
                ),
            actions = PhoneRingtoneActions(),
            modifier = Modifier.padding(16.dp),
        )
    }
}
