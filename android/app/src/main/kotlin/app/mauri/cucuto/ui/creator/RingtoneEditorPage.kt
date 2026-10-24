package app.mauri.cucuto.ui.creator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.R
import app.mauri.cucuto.packspec.authoring.LoopMode
import app.mauri.cucuto.packspec.authoring.RingtoneForm
import app.mauri.cucuto.ui.components.ChoiceButtonGroup
import app.mauri.cucuto.ui.ringtone.rules.rememberRuleText
import kotlin.math.roundToInt

/**
 * One ringtone version (#322, ADR 0049): its name, when it rings (the shared condition editor, without the clip-only
 * fields), how it loops (the whole song, once, or an intro and then a part), its fade-in and how often "random per
 * call" picks it. The form is the shared [RingtoneForm]; Save stays off while it has problems.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RingtoneEditorPage(
    editor: RingtoneEditorUi,
    playing: Boolean,
    onBack: () -> Unit,
    onChange: (RingtoneForm) -> Unit,
    onPlay: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val form = editor.form
    val rules = rememberRuleText()
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.creator_ringtone_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
            )
        },
        bottomBar = { SaveBar(enabled = editor.problems.isEmpty(), onSave = onSave) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalIconButton(onClick = onPlay) {
                    if (playing) {
                        Icon(
                            painterResource(R.drawable.ic_creator_stop),
                            contentDescription = stringResource(R.string.creator_ringtone_stop),
                        )
                    } else {
                        Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(R.string.creator_ringtone_play),
                        )
                    }
                }
                OutlinedTextField(
                    value = form.label,
                    onValueChange = { onChange(form.copy(label = it)) },
                    label = { Text(stringResource(R.string.creator_ringtone_label)) },
                    supportingText =
                        editor.durationMs.takeIf { it > 0 }?.let {
                            { Text(stringResource(R.string.creator_seconds, seconds(it))) }
                        },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.weight(1f),
                )
            }

            EditorSection(
                stringResource(R.string.creator_ringtone_when),
                stringResource(R.string.creator_ringtone_when_hint),
            ) {
                Text(
                    rules.condition(form.condition.toWhen(clip = false))?.let {
                        stringResource(R.string.ringtone_rules_rings, it)
                    } ?: stringResource(R.string.ringtone_rules_always_rings),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            ConditionFields(
                form = form.condition,
                clip = false,
                onChange = { onChange(form.copy(condition = it)) },
                rules = rules,
            )

            EditorSection(stringResource(R.string.creator_ringtone_loop), stringResource(loopHint(form.loop))) {
                ChoiceButtonGroup(
                    options = LoopMode.entries,
                    selected = form.loop,
                    onSelect = { onChange(form.copy(loop = it)) },
                    label = { stringResource(loopLabel(it)) },
                )
                if (form.loop == LoopMode.SECTION) LoopSection(editor, onChange)
            }

            EditorSection(
                stringResource(R.string.creator_ringtone_fade, seconds(form.fadeInMs.toLong())),
                stringResource(R.string.creator_ringtone_fade_hint),
            ) {
                Slider(
                    value = form.fadeInMs.toFloat(),
                    onValueChange = { onChange(form.copy(fadeInMs = (it / FADE_STEP).roundToInt() * FADE_STEP)) },
                    valueRange = 0f..RingtoneForm.MAX_FADE_IN_MS.toFloat(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            EditorSection(
                stringResource(R.string.creator_ringtone_weight),
                stringResource(R.string.creator_ringtone_weight_hint),
            ) {
                ChoiceButtonGroup(
                    options = RINGTONE_WEIGHTS.map { it.first },
                    selected = RINGTONE_WEIGHTS.minBy { kotlin.math.abs(it.first - form.weight) }.first,
                    onSelect = { onChange(form.copy(weight = it)) },
                    label = { weight -> stringResource(RINGTONE_WEIGHTS.first { it.first == weight }.second) },
                )
            }

            ProblemList(editor.problems)
        }
    }
}

/** The intro and the looping part: a range slider over the song, or millisecond fields when its length is unknown. */
@Composable
private fun LoopSection(
    editor: RingtoneEditorUi,
    onChange: (RingtoneForm) -> Unit,
) {
    val form = editor.form
    val duration = editor.durationMs
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (duration > 0) {
            val end = (form.loopEndMs?.toLong() ?: duration).coerceIn(0, duration)
            val start = form.loopStartMs.toLong().coerceIn(0, end)
            RangeSlider(
                value = start.toFloat()..end.toFloat(),
                onValueChange = { range ->
                    val newEnd = range.endInclusive.toLong()
                    onChange(
                        form.copy(
                            loopStartMs = range.start.roundToInt(),
                            loopEndMs = if (newEnd >= duration - END_SNAP_MS) null else newEnd.toInt(),
                        ),
                    )
                },
                valueRange = 0f..duration.toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.creator_ringtone_loop_range, seconds(start), seconds(start), seconds(end)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(
                    value = form.loopStartMs,
                    label = stringResource(R.string.creator_ringtone_loop_start_ms),
                    onValue = { onChange(form.copy(loopStartMs = it)) },
                    modifier = Modifier.weight(1f),
                )
                NumberField(
                    value = form.loopEndMs ?: 0,
                    label = stringResource(R.string.creator_ringtone_loop_end_ms),
                    onValue = { onChange(form.copy(loopEndMs = it.takeIf { ms -> ms > 0 })) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun loopLabel(mode: LoopMode): Int =
    when (mode) {
        LoopMode.WHOLE -> R.string.creator_ringtone_loop_whole
        LoopMode.ONCE -> R.string.creator_ringtone_loop_once
        LoopMode.SECTION -> R.string.creator_ringtone_loop_section
    }

private fun loopHint(mode: LoopMode): Int =
    when (mode) {
        LoopMode.WHOLE -> R.string.creator_ringtone_loop_whole_hint
        LoopMode.ONCE -> R.string.creator_ringtone_loop_once_hint
        LoopMode.SECTION -> R.string.creator_ringtone_loop_section_hint
    }

/** Weights offered for "random per call", as for clips. */
private val RINGTONE_WEIGHTS =
    listOf(
        0.5 to R.string.creator_weight_rare,
        1.0 to R.string.creator_weight_normal,
        2.0 to R.string.creator_weight_often,
        4.0 to R.string.creator_weight_very_often,
    )

private const val FADE_STEP = 100

/** A loop end this close to the end of the song is "the end of the file" (no `endMs`). */
private const val END_SNAP_MS = 50L
