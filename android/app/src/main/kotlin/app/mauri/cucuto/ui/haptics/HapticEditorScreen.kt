package app.mauri.cucuto.ui.haptics

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import app.mauri.cucuto.R
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.routing.ControlPoint
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import app.mauri.cucuto.ui.feedback.Feedback
import app.mauri.cucuto.ui.feedback.LocalFeedback
import app.mauri.cucuto.ui.theme.CucutoTheme
import kotlin.math.roundToInt

/**
 * The haptic editor (brief, screen 7; #32) for [chat]'s custom vibration, or a scratch pad when [chat] is null. Saving
 * stores the pattern as the chat's override and closes the screen ([onDone]); leaving with unsaved changes asks first.
 */
@Composable
fun HapticEditorScreen(
    chat: ChatKey?,
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel =
        hiltViewModel<HapticEditorViewModel, HapticEditorViewModel.Factory>(
            creationCallback = { factory -> factory.create(chat) },
        )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val feedback = LocalFeedback.current
    val done by rememberUpdatedState(onDone)
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.saved) {
        if (state.saved) {
            feedback.fire(Feedback.Success)
            done()
        }
    }
    LaunchedEffect(state.failed) {
        if (state.failed) feedback.fire(Feedback.Error)
    }

    val back = {
        when {
            state.recording != null -> viewModel.stopRecording()
            state.dirty && state.chat != null -> confirmDiscard = true
            else -> onBack()
        }
    }
    val backState = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)
    NavigationBackHandler(
        state = backState,
        isBackEnabled = state.recording != null || (state.dirty && state.chat != null),
        onBackCompleted = back,
    )

    HapticEditorContent(
        state = state,
        actions =
            HapticEditorActions(
                onBack = back,
                onSave = viewModel::save,
                onTool = viewModel::selectTool,
                onTapTimeline = { atMs, tolerance ->
                    if (!viewModel.tapTimeline(atMs, tolerance)) feedback.fire(Feedback.Reject)
                },
                onDragStart = viewModel::select,
                onMoveSelected = viewModel::moveSelected,
                onMovePoint = viewModel::movePoint,
                onSelect = viewModel::select,
                onPreset = viewModel::setPreset,
                onStrength = viewModel::setStrength,
                onSharpness = viewModel::setSharpness,
                onNudge = { index, deltaMs ->
                    val event = state.draft.events.getOrNull(index)
                    if (event != null && !viewModel.move(index, event.atMs + deltaMs)) feedback.fire(Feedback.Reject)
                },
                onAddPoint = viewModel::addPoint,
                onRemovePoint = viewModel::removePoint,
                onDelete = viewModel::delete,
                onUndo = viewModel::undo,
                onClear = viewModel::clear,
                onStartRecording = viewModel::startRecording,
                onPress = viewModel::press,
                onRelease = viewModel::release,
                onStopRecording = { viewModel.stopRecording() },
                onPreview = viewModel::preview,
                onDismissError = viewModel::dismissError,
            ),
        modifier = modifier,
    )

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.haptic_editor_discard_title)) },
            text = { Text(stringResource(R.string.haptic_editor_discard_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onBack()
                }) { Text(stringResource(R.string.haptic_editor_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) {
                    Text(stringResource(R.string.haptic_editor_keep_editing))
                }
            },
        )
    }
}

/** Everything the editor can ask its view model to do, so [HapticEditorContent] stays stateless. */
data class HapticEditorActions(
    val onBack: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onTool: (HapticTool) -> Unit = {},
    val onTapTimeline: (atMs: Int, toleranceMs: Int) -> Unit = { _, _ -> },
    val onDragStart: (Int) -> Unit = {},
    val onMoveSelected: (atMs: Int) -> Unit = {},
    val onMovePoint: (index: Int, point: Int, milliIntensity: Int, durationMs: Int) -> Unit = { _, _, _, _ -> },
    val onSelect: (Int?) -> Unit = {},
    val onPreset: (Int, HapticPreset) -> Unit = { _, _ -> },
    val onStrength: (Int, Int) -> Unit = { _, _ -> },
    val onSharpness: (Int, Int) -> Unit = { _, _ -> },
    val onNudge: (index: Int, deltaMs: Int) -> Unit = { _, _ -> },
    val onAddPoint: (Int) -> Unit = {},
    val onRemovePoint: (index: Int, point: Int) -> Unit = { _, _ -> },
    val onDelete: (Int) -> Unit = {},
    val onUndo: () -> Unit = {},
    val onClear: () -> Unit = {},
    val onStartRecording: () -> Unit = {},
    val onPress: (Long) -> Boolean = { false },
    val onRelease: (Long) -> Unit = {},
    val onStopRecording: () -> Unit = {},
    val onPreview: (withClip: Boolean) -> Unit = {},
    val onDismissError: () -> Unit = {},
)

/** Stateless haptic editor, for previews and UI tests. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HapticEditorContent(
    state: HapticEditorState,
    actions: HapticEditorActions,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    val failedMessage = stringResource(R.string.haptic_editor_failed)
    LaunchedEffect(state.failed) {
        if (state.failed) {
            snackbar.showSnackbar(failedMessage)
            actions.onDismissError()
        }
    }
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.haptic_editor_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.navigate_back))
                    }
                },
                actions = {
                    TextButton(onClick = actions.onUndo, enabled = state.draft.canUndo && state.recording == null) {
                        Text(stringResource(R.string.haptic_editor_undo))
                    }
                    if (state.chat != null) {
                        Button(
                            onClick = actions.onSave,
                            enabled = state.canSave,
                            modifier = Modifier.padding(end = 8.dp),
                        ) { Text(stringResource(R.string.haptic_editor_save)) }
                    }
                },
            )
        },
    ) { innerPadding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                val label = stringResource(R.string.haptic_editor_loading)
                LoadingIndicator(Modifier.semantics { contentDescription = label })
            }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.chat != null && state.character == null) {
                Text(
                    stringResource(R.string.haptic_editor_no_voice),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TimelineCard(state, actions)
            if (state.recording == null) {
                ToolRow(state.tool, actions.onTool)
                SelectedEventPanel(state.draft, actions)
            }
            RhythmSection(state.recording, actions)
            PreviewRow(state, actions)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TimelineCard(
    state: HapticEditorState,
    actions: HapticEditorActions,
) {
    val events = state.shownEvents
    val duration = HapticTimeline.durationOf(events)
    var dragging by remember { mutableStateOf(false) }
    // The window follows the pattern, but not while a drag is under way (the finger would slide off its event).
    var window by remember { mutableIntStateOf(timelineWindowMs(duration)) }
    val fitted = if (state.recording != null) HapticTimeline.MAX_MS else timelineWindowMs(duration)
    LaunchedEffect(fitted, dragging) {
        if (!dragging) window = fitted
    }
    val playhead = remember { Animatable(0f) }
    var playing by remember { mutableStateOf(false) }
    LaunchedEffect(state.playback) {
        if (state.playback == 0 || state.playbackMs <= 0) return@LaunchedEffect
        playing = true
        playhead.snapTo(0f)
        playhead.animateTo(state.playbackMs.toFloat(), tween(state.playbackMs, easing = LinearEasing))
        playing = false
    }
    val summary =
        if (events.isEmpty()) {
            stringResource(R.string.haptic_editor_timeline_empty)
        } else {
            pluralStringResource(R.plurals.haptic_editor_timeline_summary, events.size, events.size, seconds(duration))
        }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box {
            HapticTimelineView(
                events = events,
                selected = if (state.recording == null) state.draft.selected else null,
                windowMs = window,
                playhead = if (playing) playhead.value else null,
                interactive = state.recording == null,
                onTap = actions.onTapTimeline,
                onDragStart = {
                    dragging = true
                    actions.onDragStart(it)
                },
                onMoveEvent = { _, atMs -> actions.onMoveSelected(atMs) },
                onMovePoint = actions.onMovePoint,
                onDragEnd = { dragging = false },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(TIMELINE_HEIGHT)
                        .semantics { contentDescription = summary },
            )
            if (events.isEmpty() && state.recording == null) {
                Text(
                    stringResource(R.string.haptic_editor_timeline_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp).clearAndSetSemantics {},
                )
            }
        }
    }
}

@Composable
private fun ToolRow(
    tool: HapticTool,
    onTool: (HapticTool) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.haptic_editor_tools),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            modifier = Modifier.fillMaxWidth(),
        ) {
            val tools = HapticTool.entries
            tools.forEachIndexed { index, entry ->
                ToggleButton(
                    checked = entry == tool,
                    onCheckedChange = { onTool(entry) },
                    shapes = connectedShapes(index, tools.size),
                    modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                ) {
                    Text(stringResource(entry.label), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SelectedEventPanel(
    draft: HapticDraft,
    actions: HapticEditorActions,
) {
    val index = draft.selected
    val event = draft.selectedEvent
    AnimatedContent(targetState = index to (event?.javaClass), label = "selected event") { (shownIndex, _) ->
        val shown = shownIndex?.let(draft.events::getOrNull)
        if (shownIndex == null || shown == null) {
            Text(
                stringResource(R.string.haptic_editor_select_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@AnimatedContent
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { actions.onSelect(shownIndex - 1) }, enabled = shownIndex > 0) {
                        Icon(
                            Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                            stringResource(R.string.haptic_editor_previous_event),
                        )
                    }
                    Text(
                        describe(shown),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f).semantics { heading() },
                        textAlign = TextAlign.Center,
                    )
                    IconButton(
                        onClick = { actions.onSelect(shownIndex + 1) },
                        enabled = shownIndex < draft.events.lastIndex,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            stringResource(R.string.haptic_editor_next_event),
                        )
                    }
                }
                when (shown) {
                    is HapticEvent.Preset -> PresetControls(shownIndex, shown, actions)
                    is HapticEvent.Envelope -> EnvelopeControls(shownIndex, shown, actions)
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { actions.onNudge(shownIndex, -NUDGE_MS) }) {
                        Text(stringResource(R.string.haptic_editor_earlier, NUDGE_MS))
                    }
                    OutlinedButton(onClick = { actions.onNudge(shownIndex, NUDGE_MS) }) {
                        Text(stringResource(R.string.haptic_editor_later, NUDGE_MS))
                    }
                    OutlinedButton(onClick = { actions.onDelete(shownIndex) }) {
                        Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.haptic_editor_delete))
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetControls(
    index: Int,
    event: HapticEvent.Preset,
    actions: HapticEditorActions,
) {
    val presets = HapticPreset.entries
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            modifier = Modifier.fillMaxWidth(),
        ) {
            presets.forEachIndexed { i, preset ->
                ToggleButton(
                    checked = event.preset == preset,
                    onCheckedChange = { actions.onPreset(index, preset) },
                    shapes = connectedShapes(i, presets.size),
                    modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                ) { Text(stringResource(preset.label), maxLines = 1) }
            }
        }
        LabeledSlider(
            label = stringResource(R.string.haptic_editor_strength),
            milli = event.milliScale,
            onChange = { actions.onStrength(index, it) },
        )
    }
}

@Composable
private fun EnvelopeControls(
    index: Int,
    event: HapticEvent.Envelope,
    actions: HapticEditorActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LabeledSlider(
            label = stringResource(R.string.haptic_editor_strength),
            milli = event.points.maxOf { it.milliIntensity },
            onChange = { actions.onStrength(index, it) },
        )
        LabeledSlider(
            label = stringResource(R.string.haptic_editor_sharpness),
            milli = event.points.first().milliSharpness,
            onChange = { actions.onSharpness(index, it) },
        )
        Text(
            stringResource(R.string.haptic_editor_points_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = { actions.onAddPoint(index) },
                enabled = event.points.size < HapticTimeline.MAX_POINTS,
            ) { Text(stringResource(R.string.haptic_editor_add_point)) }
            FilledTonalButton(
                onClick = { actions.onRemovePoint(index, event.points.lastIndex - 1) },
                enabled = event.points.size > 2,
            ) { Text(stringResource(R.string.haptic_editor_remove_point)) }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    milli: Int,
    onChange: (Int) -> Unit,
) {
    val percent = stringResource(R.string.haptic_editor_percent, (milli / 10f).roundToInt())
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(percent, style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = milli / 1000f,
            onValueChange = { onChange(HapticDraft.milli(it)) },
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

@Composable
private fun RhythmSection(
    recording: RhythmRecording?,
    actions: HapticEditorActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.haptic_editor_rhythm_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        if (recording == null) {
            Text(
                stringResource(R.string.haptic_editor_rhythm_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = actions.onStartRecording) {
                Text(stringResource(R.string.haptic_editor_rhythm_start))
            }
        } else {
            RhythmPad(recording, actions)
        }
    }
}

@Composable
private fun RhythmPad(
    recording: RhythmRecording,
    actions: HapticEditorActions,
) {
    val haptics = LocalHapticFeedback.current
    val press by rememberUpdatedState(actions.onPress)
    val release by rememberUpdatedState(actions.onRelease)
    val scale by animateFloatAsState(if (recording.pressed) PRESSED_SCALE else 1f, label = "pad scale")
    val padLabel = stringResource(R.string.haptic_editor_rhythm_pad)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LinearWavyProgressIndicator(
            progress = { recording.elapsedMs / HapticTimeline.MAX_MS.toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            pluralStringResource(
                R.plurals.haptic_editor_rhythm_progress,
                recording.events.size,
                recording.events.size,
                seconds(recording.elapsedMs),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(PAD_HEIGHT)
                    .scale(scale)
                    .background(
                        if (recording.pressed) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                        MaterialTheme.shapes.extraLarge,
                    ).semantics {
                        contentDescription = padLabel
                        role = Role.Button
                    }.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            if (press(down.uptimeMillis)) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            val up = waitForUpOrCancellation()
                            release(up?.uptimeMillis ?: down.uptimeMillis)
                        }
                    },
        ) {
            Text(
                stringResource(R.string.haptic_editor_rhythm_pad_hint),
                style = MaterialTheme.typography.titleMedium,
                color =
                    if (recording.pressed) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp).widthIn(max = 280.dp).clearAndSetSemantics {},
            )
        }
        Button(onClick = actions.onStopRecording) { Text(stringResource(R.string.haptic_editor_rhythm_stop)) }
    }
}

@Composable
private fun PreviewRow(
    state: HapticEditorState,
    actions: HapticEditorActions,
) {
    if (state.recording != null) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(onClick = { actions.onPreview(false) }, enabled = state.draft.haptic != null) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.haptic_editor_play))
        }
        if (state.character != null) {
            FilledTonalButton(onClick = { actions.onPreview(true) }, enabled = state.canPreviewWithClip) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.haptic_editor_play_with_clip, state.character.name))
            }
        }
        TextButton(onClick = actions.onClear, enabled = state.draft.events.isNotEmpty()) {
            Text(stringResource(R.string.haptic_editor_clear))
        }
    }
}

@Composable
private fun connectedShapes(
    index: Int,
    count: Int,
) = when (index) {
    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
    count - 1 -> ButtonGroupDefaults.connectedTrailingButtonShapes()
    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
}

private val HapticTool.label: Int
    get() =
        when (this) {
            HapticTool.Click -> R.string.haptic_preset_click
            HapticTool.Tick -> R.string.haptic_preset_tick
            HapticTool.LowTick -> R.string.haptic_preset_low_tick
            HapticTool.Envelope -> R.string.haptic_tool_envelope
        }

private val HapticPreset.label: Int
    get() =
        when (this) {
            HapticPreset.CLICK -> R.string.haptic_preset_click
            HapticPreset.TICK -> R.string.haptic_preset_tick
            HapticPreset.LOW_TICK -> R.string.haptic_preset_low_tick
        }

/** "Click at 120 ms" or "Swell at 300 ms, 260 ms long". */
@Composable
private fun describe(event: HapticEvent): String =
    when (event) {
        is HapticEvent.Preset -> {
            stringResource(R.string.haptic_editor_preset_at, stringResource(event.preset.label), event.atMs)
        }

        is HapticEvent.Envelope -> {
            stringResource(R.string.haptic_editor_envelope_at, event.atMs, HapticTimeline.lengthOf(event))
        }
    }

/** Milliseconds as seconds with one decimal, for labels ("1.2"). */
private fun seconds(ms: Int): String = "%.1f".format(ms / 1000f)

private val TIMELINE_HEIGHT = 200.dp
private val PAD_HEIGHT = 160.dp
private const val PRESSED_SCALE = 0.96f
private const val NUDGE_MS = 10

@Preview(showBackground = true, heightDp = 1100)
@Composable
private fun HapticEditorPreview() {
    CucutoTheme(dynamicColor = false) {
        HapticEditorContent(
            state =
                HapticEditorState(
                    loading = false,
                    chat = ChatKey("com.whatsapp", "100000000000001@lid"),
                    draft =
                        HapticDraft(
                            events =
                                listOf(
                                    HapticEvent.Preset(0, HapticPreset.CLICK, 800),
                                    HapticEvent.Preset(120, HapticPreset.TICK, 500),
                                    HapticEvent.Envelope(
                                        220,
                                        listOf(ControlPoint(1000, 800, 120), ControlPoint(0, 400, 180)),
                                    ),
                                ),
                            selected = 2,
                        ),
                    dirty = true,
                ),
            actions = HapticEditorActions(),
        )
    }
}
