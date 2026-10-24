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
    /** Opens the ringtone picker (#53): character, versions and rule, Android default or Leave as is. */
    val onChoose: () -> Unit = {},
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
 * The phone ringtone set-up (#52) of [chat], as a self-contained card for the contact detail screen, right under the
 * Sounds card whose "Phone calls" row ([PhoneCallsRow]) shows and changes what rings. The card shows up only when it
 * asks for something (#348, ADR 0073): turn the feature on (with the contacts permission explained first), link the
 * Android contact (suggested by phone number, or picked), use the ringtone, allow the phone permission, or fix a
 * problem.
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
    // What rings is the "Phone calls" row's: this card only asks for what is missing (#348), once there is something
    // to ring (or a ringtone of the contact's own to clear for Android default, ADR 0073).
    if (state.loading || state.contactId == null || !state.needsAction) return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.contact_row_calls),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            when {
                !state.enabled -> {
                    Body(
                        stringResource(
                            if (state.isDefault) R.string.ringtone_default_off_body else R.string.ringtone_off_body,
                        ),
                    )
                    FilledTonalButton(
                        onClick = tap(actions.onTurnOn),
                    ) { Text(stringResource(R.string.ringtone_turn_on)) }
                }

                !state.linked -> {
                    LinkPrompt(state, actions, ::tap)
                }

                else -> {
                    NeedsAction(state, actions, ::tap)
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

/** Linked: using the ringtone, the phone permission, or a problem (the contact and its "Change" sit under the row). */
@Composable
private fun NeedsAction(
    state: PhoneRingtoneUiState,
    actions: PhoneRingtoneActions,
    tap: (() -> Unit) -> () -> Unit,
) = Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    if (!state.using) {
        FilledTonalButton(onClick = tap(actions.onUse)) {
            Text(stringResource(R.string.ringtone_use, state.characterName.orEmpty()))
        }
        return@Column
    }
    state.shownProblem?.let { problem ->
        Text(
            problemText(problem),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (problem == RingtoneProblem.ChangedElsewhere) {
            FilledTonalButton(onClick = tap(actions.onTakeOver)) { Text(stringResource(R.string.ringtone_take_over)) }
        }
    }
    if (state.needsPhonePermission) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Body(stringResource(R.string.ringtone_phone_hint), Modifier.weight(1f))
            TextButton(onClick = tap(actions.onAllowPhone)) { Text(stringResource(R.string.ringtone_phone_allow)) }
        }
    }
}

/**
 * The "Phone calls" row of contact detail (ADR 0063), the one place to see and change what rings when [chat] calls you
 * (#348): the character with its rule (or "Android default", or "Left as is", ADR 0073), one tap from the ringtone
 * picker ([onChoose]), whose "Which version rings" row opens the rules screen (#347); a play button that previews the
 * version that rings now from its start (#246); and, once linked, the Android contact with "Change" and the WhatsApp
 * hint. Shares [PhoneRingtoneSection]'s view model.
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
    // "Change" picks another Android contact and keeps the current link until one is picked (#369): clearing it at
    // once left the chat "Not linked yet" when the user backed out.
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
            uri?.let { viewModel.linkPicked(it.toString()) }
        }
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
        onChangeContact = {
            feedback.fire(Feedback.Tap)
            viewModel.stopPreview()
            runCatching { picker.launch(null) }
        },
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
    onChangeContact: () -> Unit = {},
) {
    val character = state.ringingCharacter
    val name =
        state.characterName.takeIf {
            state.hasRingtone && !state.isDefault && !state.isUnchanged && !state.setInContacts
        }
    val value =
        when {
            state.loading -> ""
            state.setInContacts -> stringResource(R.string.ringtone_row_contacts)
            state.isDefault -> stringResource(R.string.ringtone_row_default)
            state.isUnchanged -> stringResource(R.string.ringtone_row_unchanged)
            name == null -> stringResource(R.string.ringtone_row_none)
            else -> stringResource(R.string.ringtone_row_value, name, rule(state))
        }
    val relation =
        when {
            state.loading -> null
            state.setInContacts -> stringResource(R.string.ringtone_row_contacts_detail)
            state.isDefault -> stringResource(R.string.ringtone_row_default_detail)
            state.isUnchanged -> stringResource(R.string.ringtone_row_unchanged_detail)
            name == null -> null
            state.mixed -> stringResource(R.string.ringtone_row_own)
            state.kept && state.voiceName != null -> stringResource(R.string.ringtone_row_kept, state.voiceName)
            state.kept -> null
            else -> stringResource(R.string.ringtone_row_follows)
        }
    Column(modifier) {
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
                    SoundRowIcon(state.choice?.icon ?: CallIcon)
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
        )
        // The linked Android contact and the WhatsApp hint, once there is a contact to ring (#348). The rule is the
        // picker's and the rules screen's (#347).
        if (state.showsLink) LinkLines(state, onChangeContact)
    }
}

/** "Android contact: Ada · Change" and, for a ringtone of Cucuto's, how to hear it on WhatsApp calls too (#145). */
@Composable
private fun LinkLines(
    state: PhoneRingtoneUiState,
    onChangeContact: () -> Unit,
) {
    Column(Modifier.padding(start = 76.dp, end = 12.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.linkedName?.let { stringResource(R.string.ringtone_linked, it) }
                    ?: stringResource(R.string.ringtone_linked_unnamed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onChangeContact) {
                Text(stringResource(R.string.ringtone_change_contact))
            }
        }
        if (!state.isDefault && state.using && state.hasRingtone && !state.setInContacts) {
            Text(
                state.exportTitle?.let { stringResource(R.string.ringtone_whatsapp_hint, it) }
                    ?: stringResource(R.string.ringtone_whatsapp_hint_generic),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
private fun problemText(problem: RingtoneProblem): String =
    stringResource(
        when (problem) {
            RingtoneProblem.ContactMissing -> {
                R.string.ringtone_problem_contact_missing
            }

            RingtoneProblem.ChangedElsewhere -> {
                R.string.ringtone_problem_changed
            }

            RingtoneProblem.NoPermission -> {
                R.string.ringtone_problem_permission
            }

            RingtoneProblem.WriteFailed -> {
                R.string.ringtone_problem_write
            }

            RingtoneProblem.RenderFailed, RingtoneProblem.NotLinked, RingtoneProblem.NoRingtones -> {
                R.string.ringtone_problem_render
            }
        },
    )

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
