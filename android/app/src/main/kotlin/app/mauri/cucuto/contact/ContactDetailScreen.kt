package app.mauri.cucuto.contact

import android.Manifest
import android.graphics.Bitmap
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mauri.cucuto.R
import app.mauri.cucuto.core.data.chats.Chat
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.chats.ChatVoice
import app.mauri.cucuto.core.data.db.DecisionOutcome
import app.mauri.cucuto.core.data.db.GroupPolicy
import app.mauri.cucuto.core.data.voices.VoiceCharacter
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.home.ChatAvatarKey
import app.mauri.cucuto.home.name
import app.mauri.cucuto.ui.chat.GroupPolicySetting
import app.mauri.cucuto.ui.components.VoiceAvatar
import app.mauri.cucuto.ui.components.adaptiveMaxLines
import app.mauri.cucuto.ui.feedback.Feedback
import app.mauri.cucuto.ui.feedback.LocalFeedback
import app.mauri.cucuto.ui.haptics.HapticOverrideSection
import app.mauri.cucuto.ui.motion.SharedElement
import app.mauri.cucuto.ui.ringtone.PhoneCallsRow
import app.mauri.cucuto.ui.ringtone.PhoneRingtoneSection
import app.mauri.cucuto.ui.ringtone.SoundRow
import app.mauri.cucuto.ui.ringtone.SoundRowIcon
import app.mauri.cucuto.ui.theme.CharacterTheme
import app.mauri.cucuto.ui.theme.CucutoTheme
import app.mauri.cucuto.ui.theme.rememberSeedColor
import app.mauri.cucuto.xposed.events.Outcome
import java.io.File

/**
 * Contact detail (brief, screen 4) for [chat]: two rows, "Messages" and "Phone calls", each one tap from its picker
 * (independent choices, ADR 0063), "Simulate message", the clip pool with per-contact toggles, the vibration, the
 * phone ringtone set-up and group policy, and the chat's recent alerts from the decision log.
 */
@Composable
fun ContactDetailScreen(
    chat: ChatKey,
    onBack: () -> Unit,
    onChangeCharacter: () -> Unit,
    onEditPattern: () -> Unit,
    modifier: Modifier = Modifier,
    onChooseRingtone: () -> Unit = {},
) {
    val viewModel =
        hiltViewModel<ContactDetailViewModel, ContactDetailViewModel.Factory>(
            creationCallback = { factory -> factory.create(chat) },
        )
    val state by viewModel.state.collectAsStateWithLifecycle()
    // LSPosed's state changes outside Cucuto (enabling the module, a reboot): re-check the path on every return.
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshPath()
        onPauseOrDispose {}
    }
    val title = stringResource(R.string.simulate_notification_title, state.chatName())
    // The module path posts a notification; Android 13+ asks for the permission first (instant once granted).
    val requestPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.simulate(title) }
    ContactDetailContent(
        state = state,
        onBack = onBack,
        onChangeCharacter = onChangeCharacter,
        hapticOverride = { HapticOverrideSection(chat, onEditPattern, Modifier.padding(top = 16.dp)) },
        phoneCalls = { PhoneCallsRow(chat, onChooseRingtone) },
        phoneRingtone = { PhoneRingtoneSection(chat, onChooseRingtone, Modifier.padding(top = 16.dp)) },
        onUnassign = viewModel::unassign,
        onSimulate = {
            if (state.path is SimulatePath.InApp) {
                viewModel.simulate(title)
            } else {
                requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        onPlayClip = viewModel::playClip,
        onToggleClip = viewModel::setClipEnabled,
        onGroupPolicyChange = viewModel::setGroupPolicy,
        onConsumeFailure = viewModel::clearFailure,
        modifier = modifier,
    )
}

/** Stateless contact detail, for previews and UI tests. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailContent(
    state: ContactDetailState,
    onBack: () -> Unit,
    onChangeCharacter: () -> Unit,
    onUnassign: () -> Unit,
    onSimulate: () -> Unit,
    onPlayClip: (ClipRow) -> Unit,
    onToggleClip: (ClipRow, Boolean) -> Unit,
    onGroupPolicyChange: (GroupPolicy) -> Unit,
    onConsumeFailure: () -> Unit,
    modifier: Modifier = Modifier,
    hapticOverride: (@Composable () -> Unit)? = null,
    phoneCalls: (@Composable () -> Unit)? = null,
    phoneRingtone: (@Composable () -> Unit)? = null,
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    val extracted by rememberSeedColor(bitmap)
    val seed = state.character?.seedColor?.let { Color(it) } ?: extracted
    val snackbar = remember { SnackbarHostState() }
    val failedMessage = stringResource(R.string.contact_failed)
    val feedback = LocalFeedback.current
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    val consumeFailure by rememberUpdatedState(onConsumeFailure)
    LaunchedEffect(state.failed) {
        if (state.failed) {
            feedback.fire(Feedback.Error)
            snackbar.showSnackbar(failedMessage)
            consumeFailure()
        }
    }

    CharacterTheme(seed = seed) {
        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        Scaffold(
            modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                MediumFlexibleTopAppBar(
                    title = {
                        Text(
                            state.chatName(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { heading() },
                        )
                    },
                    subtitle = { Text(stringResource(state.kindLabel())) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.navigate_back),
                            )
                        }
                    },
                    actions = {
                        if (state.chat?.voice != null) {
                            IconButton(onClick = { confirmRemove = true }) {
                                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.contact_remove))
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { innerPadding ->
            if (state.loading) {
                Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                    val label = stringResource(R.string.contact_loading)
                    LoadingIndicator(Modifier.semantics { contentDescription = label })
                }
                return@Scaffold
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                    PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = innerPadding.calculateTopPadding(),
                        bottom = innerPadding.calculateBottomPadding() + 24.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "header") { Header(state = state, onBitmap = { bitmap = it }) }
                item(key = "sounds") {
                    SoundsCard(
                        state = state,
                        onChangeCharacter = {
                            feedback.fire(Feedback.Tap)
                            onChangeCharacter()
                        },
                        // Phone calls come from people: groups have no phone ringtone (#52).
                        phoneCalls = phoneCalls.takeIf { state.chat?.isGroup != true && !state.isGroup },
                    )
                }
                item(key = "simulate") { SimulateCard(state, onSimulate) }
                if (state.clips.isNotEmpty()) {
                    clipSection(state, onPlayClip) { clip, on ->
                        feedback.fire(if (on) Feedback.ToggleOn else Feedback.ToggleOff)
                        onToggleClip(clip, on)
                    }
                }
                if (state.character != null) {
                    item(key = "haptic") {
                        if (hapticOverride != null) hapticOverride() else HapticSection(state.hapticSource)
                    }
                }
                // Phone calls come from people: groups have no phone ringtone (#52).
                if (phoneRingtone != null && state.chat?.isGroup == false) {
                    item(key = "phone-ringtone") { phoneRingtone() }
                }
                state.groupPolicy?.let { policy ->
                    item(key = "group") {
                        GroupPolicySetting(policy, onGroupPolicyChange, Modifier.padding(top = 16.dp))
                    }
                }
                decisionSection(state.decisions)
            }
        }
    }

    if (confirmRemove) {
        val name = state.chatName()
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.contact_remove_title)) },
            text = { Text(stringResource(R.string.contact_remove_body, name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemove = false
                        feedback.fire(Feedback.Confirm)
                        onUnassign()
                    },
                ) { Text(stringResource(R.string.contact_remove_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.contact_remove_cancel)) }
            },
        )
    }
}

@Composable
private fun ContactDetailState.chatName(): String =
    chat?.name() ?: stringResource(if (isGroup) R.string.chat_unnamed_group else R.string.chat_unnamed)

@StringRes
private fun ContactDetailState.kindLabel(): Int {
    val business = chatKey.packageName == ChatKey.WHATSAPP_BUSINESS
    return when {
        business && isGroup -> R.string.contact_subtitle_business_group
        business -> R.string.contact_subtitle_business_chat
        isGroup -> R.string.contact_subtitle_group
        else -> R.string.contact_subtitle_chat
    }
}

@Composable
private fun Header(
    state: ContactDetailState,
    onBitmap: (Bitmap) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SharedElement(ChatAvatarKey(state.chatKey)) {
                VoiceAvatar(
                    character = state.character,
                    name = state.chat?.displayName,
                    contentDescription = null,
                    size = AVATAR_SIZE,
                    speaking = state.speaking,
                    onBitmap = onBitmap,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val character = state.character
                when {
                    character != null -> {
                        Text(
                            stringResource(R.string.contact_speaks_as, character.name),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        state.packName?.let {
                            Text(
                                stringResource(R.string.contact_from_pack, it),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    state.voiceMissing -> {
                        Text(
                            stringResource(R.string.contact_voice_missing),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    else -> {
                        Text(
                            stringResource(R.string.contact_no_voice),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The chat's two sounds as rows (ADR 0063): "Messages" (the character whose clips play; opens the character picker)
 * and, for 1:1 chats, "Phone calls" ([phoneCalls], the ringtone; opens the ringtone picker). They are chosen apart.
 */
@Composable
private fun SoundsCard(
    state: ContactDetailState,
    onChangeCharacter: () -> Unit,
    phoneCalls: (@Composable () -> Unit)?,
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
            SoundRow(
                label = stringResource(R.string.contact_row_messages),
                value =
                    when {
                        character != null && state.packName != null -> {
                            stringResource(R.string.contact_row_messages_value, character.name, state.packName)
                        }

                        character != null -> {
                            character.name
                        }

                        state.voiceMissing -> {
                            stringResource(R.string.contact_row_messages_missing)
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
                onClick = onChangeCharacter,
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
            if (phoneCalls != null) {
                HorizontalDivider(Modifier.padding(horizontal = 12.dp))
                phoneCalls()
            }
        }
    }
}

@Composable
private fun SimulateCard(
    state: ContactDetailState,
    onSimulate: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val text = stringResource(R.string.contact_simulate)
            // The visible text first (voice access users say it), then what it does; read once, not twice.
            val label =
                stringResource(R.string.a11y_label_and_hint, text, stringResource(R.string.contact_simulate_label))
            // No UI haptic or sound on this button: the only vibration felt is the chat's own.
            Button(
                onClick = onSimulate,
                enabled = state.canSimulate && state.simulation != SimulationStatus.Working,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { contentDescription = label },
            ) {
                Icon(Icons.Rounded.Notifications, contentDescription = null)
                Text(text, Modifier.padding(start = 8.dp).clearAndSetSemantics {})
            }
            Text(
                text = pathText(state),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            simulationText(state)?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

@Composable
private fun pathText(state: ContactDetailState): String =
    when {
        !state.canSimulate -> {
            stringResource(R.string.contact_simulate_needs_voice)
        }

        else -> {
            when (val path = state.path) {
                null -> {
                    stringResource(R.string.contact_simulate_checking)
                }

                SimulatePath.Module -> {
                    stringResource(R.string.contact_simulate_module)
                }

                is SimulatePath.InApp -> {
                    stringResource(
                        when (path.reason) {
                            InAppReason.ModuleOff -> R.string.contact_simulate_inapp_off
                            InAppReason.RebootNeeded -> R.string.contact_simulate_inapp_reboot
                            InAppReason.HooksMissing -> R.string.contact_simulate_inapp_hooks
                            InAppReason.NotConfirmed -> R.string.contact_simulate_inapp_unconfirmed
                        },
                    )
                }
            }
        }
    }

@Composable
private fun simulationText(state: ContactDetailState): String? =
    when (val simulation = state.simulation) {
        SimulationStatus.Idle, SimulationStatus.Working -> {
            null
        }

        is SimulationStatus.Sent -> {
            val report = state.report
            when {
                report == null -> {
                    stringResource(R.string.contact_sim_sent)
                }

                report.outcome == DecisionOutcome.SWAPPED -> {
                    stringResource(R.string.contact_sim_report, report.clipName ?: "")
                }

                else -> {
                    stringResource(R.string.contact_sim_report_whatsapp, stringResource(reason(report.outcome)))
                }
            }
        }

        is SimulationStatus.PlayedInApp -> {
            val haptic =
                when (simulation.haptic) {
                    HapticPlayback.Custom -> R.string.contact_sim_haptic_custom
                    HapticPlayback.WhatsApp -> R.string.contact_sim_haptic_whatsapp
                    HapticPlayback.Off -> R.string.contact_sim_haptic_off
                }
            stringResource(R.string.contact_sim_inapp, simulation.clipName, stringResource(haptic))
        }

        is SimulationStatus.WhatsAppTone -> {
            stringResource(R.string.contact_sim_whatsapp_tone, stringResource(reason(simulation.outcome)))
        }

        is SimulationStatus.TooSoon -> {
            stringResource(R.string.contact_sim_too_soon, simulation.seconds)
        }

        SimulationStatus.Blocked -> {
            stringResource(R.string.contact_sim_blocked)
        }

        SimulationStatus.Failed -> {
            stringResource(R.string.contact_sim_failed)
        }
    }

@StringRes
private fun reason(outcome: Outcome): Int =
    when (outcome) {
        Outcome.SWAPPED, Outcome.UNASSIGNED -> R.string.contact_reason_unassigned
        Outcome.DISABLED -> R.string.contact_reason_disabled
        Outcome.NO_KEY -> R.string.contact_reason_no_key
        Outcome.ERROR -> R.string.contact_reason_error
    }

@StringRes
private fun reason(outcome: DecisionOutcome): Int =
    when (outcome) {
        DecisionOutcome.SWAPPED, DecisionOutcome.UNASSIGNED -> R.string.contact_reason_unassigned
        DecisionOutcome.DISABLED -> R.string.contact_reason_disabled
        DecisionOutcome.NO_KEY -> R.string.contact_reason_no_key
        DecisionOutcome.ERROR -> R.string.contact_reason_error
    }

private fun LazyListScope.clipSection(
    state: ContactDetailState,
    onPlay: (ClipRow) -> Unit,
    onToggle: (ClipRow, Boolean) -> Unit,
) {
    item(key = "clips-header") {
        SectionHeader(R.string.contact_clips_header, R.string.contact_clips_hint)
    }
    items(state.clips, key = { "clip/${it.path}" }) { clip ->
        val lastOn = stringResource(R.string.contact_clip_last)
        val playing = state.playingClip == clip.path
        val playingLabel = stringResource(R.string.a11y_playing)
        ListItem(
            headlineContent = { Text(clip.name, maxLines = adaptiveMaxLines(1), overflow = TextOverflow.Ellipsis) },
            // "Playing" in words, not only the button's color.
            supportingContent = if (playing) ({ Text(playingLabel) }) else null,
            leadingContent = {
                val playLabel = stringResource(R.string.contact_clip_play, clip.name)
                val playModifier = Modifier.semantics { if (playing) stateDescription = playingLabel }
                if (playing) {
                    FilledTonalIconButton(onClick = { onPlay(clip) }, modifier = playModifier) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = playLabel)
                    }
                } else {
                    IconButton(onClick = { onPlay(clip) }, modifier = playModifier) {
                        Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = playLabel,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            trailingContent = { Switch(checked = clip.enabled, onCheckedChange = null, enabled = clip.canToggle) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            // The whole row toggles the clip, so TalkBack reads its name with the switch (the play button stays apart).
            modifier =
                Modifier
                    .toggleable(
                        value = clip.enabled,
                        enabled = clip.canToggle,
                        role = Role.Switch,
                        onValueChange = { onToggle(clip, it) },
                    ).semantics { if (!clip.canToggle) stateDescription = lastOn },
        )
    }
}

/**
 * The chat's vibration, read-only: what previews and tests show. The app passes the per-contact haptic override card
 * (#33, [HapticOverrideSection]) instead.
 */
@Composable
private fun HapticSection(source: HapticSource) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeader(R.string.contact_haptic_header, null)
        Text(
            stringResource(
                when (source) {
                    HapticSource.Character -> R.string.contact_haptic_character
                    HapticSource.FollowVoice -> R.string.contact_haptic_follow
                    HapticSource.Off -> R.string.contact_haptic_off
                    HapticSource.Custom -> R.string.contact_haptic_custom
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            stringResource(R.string.contact_haptic_editor),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun LazyListScope.decisionSection(decisions: List<DecisionRow>) {
    item(key = "decisions-header") { SectionHeader(R.string.contact_decisions_header, null) }
    if (decisions.isEmpty()) {
        item(key = "decisions-empty") {
            Text(
                stringResource(R.string.contact_decisions_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    items(decisions, key = { "decision/${it.id}" }) { DecisionItem(it) }
}

@Composable
private fun DecisionItem(row: DecisionRow) {
    val headline =
        when (row.outcome) {
            DecisionOutcome.SWAPPED -> {
                row.clipName?.let { stringResource(R.string.contact_decision_played, it) }
                    ?: stringResource(R.string.contact_decision_played_voice)
            }

            DecisionOutcome.UNASSIGNED -> {
                stringResource(R.string.contact_decision_unassigned)
            }

            DecisionOutcome.DISABLED -> {
                stringResource(R.string.contact_decision_disabled)
            }

            DecisionOutcome.NO_KEY -> {
                stringResource(R.string.contact_decision_no_key)
            }

            DecisionOutcome.ERROR -> {
                stringResource(R.string.contact_decision_error)
            }
        }
    val time =
        DateUtils
            .getRelativeTimeSpanString(row.timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            .toString()
    val kind =
        stringResource(if (row.simulated) R.string.contact_decision_simulated else R.string.contact_decision_message)
    val overline =
        listOfNotNull(time, kind, if (row.perSender) stringResource(R.string.contact_decision_per_sender) else null)
            .joinToString(" · ")
    ListItem(
        overlineContent = { Text(overline) },
        headlineContent = { Text(headline) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.semantics(mergeDescendants = true) {},
    )
}

@Composable
private fun SectionHeader(
    @StringRes title: Int,
    @StringRes hint: Int?,
) {
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
        if (hint != null) {
            Text(
                text = stringResource(hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val AVATAR_SIZE = 96.dp

@Preview(showBackground = true)
@Composable
private fun ContactDetailPreview() {
    CucutoTheme(dynamicColor = false) {
        ContactDetailContent(
            state = previewContactDetailState(),
            onBack = {},
            onChangeCharacter = {},
            onUnassign = {},
            onSimulate = {},
            onPlayClip = {},
            onToggleClip = { _, _ -> },
            onGroupPolicyChange = {},
            onConsumeFailure = {},
        )
    }
}

/** A made-up chat with a voice, for previews and tests. */
internal fun previewContactDetailState(): ContactDetailState {
    val key = ChatKey("com.whatsapp", "100000000000001@lid")
    val packId = "app.mauri.demo.critters"
    val paths = listOf("characters/frog/clips/ribbit.ogg", "characters/frog/clips/croak.ogg")
    val frog =
        VoiceCharacter(
            packId = packId,
            id = "frog",
            name = "Bleep the frog",
            sprite = null,
            spriteAnimated = null,
            seedColor = 0xFF4CAF50.toInt(),
            clips = paths.map { File("/packs/$packId/1.0.0/$it") },
            haptic = Haptic.Keep,
            clipPaths = paths,
        )
    val settings = ChatSettings(disabledClips = emptyList(), hapticOverride = null)
    return ContactDetailState(
        chatKey = key,
        loading = false,
        chat = Chat(key, 1, "Ada", false, ChatVoice(packId, "frog"), 0L, 3),
        character = frog,
        packName = "Chiptune critters",
        settings = settings,
        clips = clipRows(frog, settings.disabledClips),
        decisions =
            listOf(
                DecisionRow(2, 0L, DecisionOutcome.SWAPPED, "Ribbit", simulated = true, perSender = false),
                DecisionRow(1, 0L, DecisionOutcome.SWAPPED, "Croak", simulated = false, perSender = false),
            ),
        path = SimulatePath.InApp(InAppReason.RebootNeeded),
    )
}
