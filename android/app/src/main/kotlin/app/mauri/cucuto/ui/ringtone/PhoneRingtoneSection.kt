package app.mauri.cucuto.ui.ringtone

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
    /** Opens the ringtone picker (#53): character, versions and rule. */
    val onChoose: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onTakeOver: () -> Unit = {},
    val onAllowPhone: () -> Unit = {},
)

/** The phone ringtone view model of [chat], shared by [PhoneCallsRow] and [PhoneRingtoneSection]. */
@Composable
private fun phoneRingtoneViewModel(chat: ChatKey): PhoneRingtoneViewModel =
    hiltViewModel<PhoneRingtoneViewModel, PhoneRingtoneViewModel.Factory>(
        key = "phone-ringtone:${chat.packageName}:${chat.conversationKey}",
        creationCallback = { factory -> factory.create(chat) },
    )

/**
 * The phone ringtone (#52) of [chat], as a self-contained card for the contact detail screen, under the "Phone calls"
 * row ([PhoneCallsRow]) that shows and changes what rings: turn the feature on (with the contacts permission explained
 * first), link the Android contact (suggested by phone number, or picked), and see what rings. Nothing to set up
 * while no character with ringtones is chosen.
 */
@Composable
fun PhoneRingtoneSection(
    chat: ChatKey,
    onChoose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = phoneRingtoneViewModel(chat)
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
                onChoose = onChoose,
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
    // What rings is the "Phone calls" row's: this card only sets it up, once there is something to ring.
    if (state.loading || state.contactId == null || !state.hasRingtone) return
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
            when {
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

/**
 * The "Phone calls" row of contact detail (ADR 0063): the character that rings when [chat] phones, one tap from the
 * ringtone picker ([onChoose]), and a play button that previews the version that rings now from its start (#246).
 * Shares [PhoneRingtoneSection]'s view model.
 */
@Composable
fun PhoneCallsRow(
    chat: ChatKey,
    onChoose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = phoneRingtoneViewModel(chat)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val feedback = LocalFeedback.current
    // A preview never plays on behind another app or a locked screen.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.stopPreview() }
    PhoneCallsRowContent(
        state = state,
        onChoose = {
            feedback.fire(Feedback.Tap)
            viewModel.stopPreview()
            onChoose()
        },
        onTogglePreview = {
            feedback.fire(Feedback.Tap)
            viewModel.togglePreview()
        },
        onStopPreview = {
            feedback.fire(Feedback.Tap)
            viewModel.stopPreview()
        },
        modifier = modifier,
    )
}

/** Stateless "Phone calls" row, for previews and UI tests. */
@Composable
fun PhoneCallsRowContent(
    state: PhoneRingtoneUiState,
    onChoose: () -> Unit,
    onTogglePreview: () -> Unit,
    modifier: Modifier = Modifier,
    onStopPreview: () -> Unit = {},
) {
    val character = state.ringingCharacter
    val name = state.characterName.takeIf { state.hasRingtone }
    val value =
        when {
            state.loading -> ""
            name == null -> stringResource(R.string.ringtone_row_none)
            else -> stringResource(R.string.ringtone_row_value, name, rule(state))
        }
    val relation =
        when {
            name == null || state.loading -> null
            state.mixed -> stringResource(R.string.ringtone_row_own)
            state.kept && state.voiceName != null -> stringResource(R.string.ringtone_row_kept, state.voiceName)
            state.kept -> null
            else -> stringResource(R.string.ringtone_row_follows)
        }
    SoundRow(
        label = stringResource(R.string.contact_row_calls),
        value = value,
        detail = relation,
        clickLabel = stringResource(R.string.ringtone_choose),
        onClick = onChoose,
        leading = {
            if (character != null && name != null) {
                RecordCover(character, size = 48.dp, pulledOut = state.preview != null, spinning = state.previewing)
            } else {
                SoundRowIcon(CallIcon)
            }
        },
        trailing = {
            if (state.previewSound != null && name != null) {
                RingtonePlayButton(
                    preview = state.preview,
                    name = name,
                    onToggle = onTogglePreview,
                    onStop = onStopPreview,
                )
            }
        },
        modifier = modifier,
    )
}

/** How the version is picked, as the row says it. */
@Composable
private fun rule(state: PhoneRingtoneUiState): String =
    when {
        state.fixedLabel != null -> stringResource(R.string.ringtone_summary_fixed, state.fixedLabel!!)
        state.modes.isEmpty() -> stringResource(R.string.ringtone_summary_single)
        else -> stringResource(state.mode.label)
    }

/**
 * One row of contact detail's "Sounds" card ("Messages", "Phone calls"): what it is, what is chosen, and one tap to
 * change it ([onClick]); [trailing] holds an extra action such as a preview button.
 */
@Composable
fun SoundRow(
    label: String,
    value: String,
    clickLabel: String,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    detailColor: Color = MaterialTheme.colorScheme.primary,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth().heightIn(min = 72.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier =
                Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.large)
                    .clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            leading()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(value, style = MaterialTheme.typography.titleMedium)
                if (detail != null) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = detailColor)
                }
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        trailing()
    }
}

/** A row's icon in a 48 dp tonal circle, where a character picture would go. */
@Composable
fun SoundRowIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.size(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

/** Material "call" (rounded), missing from `material-icons-core`; path data from Material Icons (Apache-2.0). */
internal val CallIcon: ImageVector by lazy {
    ImageVector
        .Builder("Call", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData =
                addPathNodes(
                    "M19.23 15.26l-2.54-.29c-.61-.07-1.21.14-1.64.57l-1.84 1.84c-2.83-1.44-5.15-3.75-6.59-6.59" +
                        "l1.85-1.85c.43-.43.64-1.03.57-1.64l-.29-2.52c-.12-1.01-.97-1.77-1.99-1.77H5.03c-1.13 0" +
                        "-2.07.94-2 2.07.53 8.54 7.36 15.36 15.89 15.89 1.13.07 2.07-.87 2.07-2v-1.73c.01-1.01" +
                        "-.75-1.86-1.76-1.98z",
                ),
            fill = SolidColor(Color.Black),
        ).build()
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
