package app.mauri.cucuto.ui.creator

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.R
import app.mauri.cucuto.core.audio.PcmAudio
import app.mauri.cucuto.core.audio.dsp.Waveform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cutting a clip: the waveform with the selection, a range slider for its start and end (zoom in for precise cuts in a
 * long file), play the selection, trim silence and even out loudness (both on by default, like packer's options), a
 * label, and the length check: 0.2 to 30 s, with a gentle hint above 5 s.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClipEditorPage(
    editor: ClipEditorUi,
    onBack: () -> Unit,
    onRange: (Long, Long) -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onPlay: () -> Unit,
    onTrimSilence: (Boolean) -> Unit,
    onNormalize: (Boolean) -> Unit,
    onLabel: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.creator_clip_title)) },
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
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (editor.truncated) {
                Note(stringResource(R.string.creator_clip_truncated))
            }
            WaveformView(editor, Modifier.fillMaxWidth().height(120.dp))
            val view = editor.viewStartMs..editor.viewEndMs
            val span = (editor.viewEndMs - editor.viewStartMs).coerceAtLeast(1)
            RangeSlider(
                value = editor.startMs.coerceIn(view).toFloat()..editor.endMs.coerceIn(view).toFloat(),
                onValueChange = { range -> onRange(range.start.toLong(), range.endInclusive.toLong()) },
                valueRange = editor.viewStartMs.toFloat()..(editor.viewStartMs + span).toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalIconButton(onClick = onPlay) {
                    if (editor.playheadMs != null) {
                        Icon(
                            painterResource(R.drawable.ic_creator_stop),
                            contentDescription = stringResource(R.string.creator_clip_stop),
                        )
                    } else {
                        Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(R.string.creator_clip_play_selection),
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.creator_clip_selection, seconds(editor.selectionMs)),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.creator_clip_from_to, seconds(editor.startMs), seconds(editor.endMs)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onZoomIn) {
                    Icon(
                        painterResource(R.drawable.ic_creator_zoom_in),
                        contentDescription = stringResource(R.string.creator_clip_zoom_in),
                    )
                }
                IconButton(onClick = onZoomOut, enabled = editor.zoomed) {
                    Icon(
                        painterResource(R.drawable.ic_creator_zoom_out),
                        contentDescription = stringResource(R.string.creator_clip_zoom_out),
                    )
                }
            }
            LengthNote(editor)

            ListItem(
                headlineContent = { Text(stringResource(R.string.creator_clip_trim)) },
                supportingContent = { Text(stringResource(R.string.creator_clip_trim_hint)) },
                trailingContent = { Switch(checked = editor.trimSilence, onCheckedChange = onTrimSilence) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.creator_clip_normalize)) },
                supportingContent = { Text(stringResource(R.string.creator_clip_normalize_hint)) },
                trailingContent = { Switch(checked = editor.normalize, onCheckedChange = onNormalize) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            )
            OutlinedTextField(
                value = editor.label,
                onValueChange = onLabel,
                label = { Text(stringResource(R.string.creator_clip_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            editor.problem?.let { Note(problemText(it), warning = true) }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (editor.saving) {
                    LoadingIndicator(Modifier.size(48.dp))
                } else {
                    Button(
                        onClick = onSave,
                        enabled = editor.selectionMs >= ClipLimits.MIN_MS,
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                        Text(
                            stringResource(R.string.creator_clip_save),
                            Modifier.padding(start = ButtonDefaults.IconSpacing),
                        )
                    }
                }
            }
        }
    }
}

/** The length rule for the current selection. Trimming silence can only make the clip shorter. */
@Composable
private fun LengthNote(editor: ClipEditorUi) {
    val ms = editor.selectionMs
    when {
        ms < ClipLimits.MIN_MS -> {
            Note(stringResource(R.string.creator_clip_too_short), warning = true)
        }

        ms > ClipLimits.MAX_MS && !editor.trimSilence -> {
            Note(
                stringResource(R.string.creator_clip_too_long),
                warning = true,
            )
        }

        ms > ClipLimits.MAX_MS -> {
            Note(stringResource(R.string.creator_clip_maybe_too_long))
        }

        ms > ClipLimits.HINT_MS -> {
            Note(stringResource(R.string.creator_long_clip_hint))
        }
    }
}

@Composable
private fun Note(
    text: String,
    modifier: Modifier = Modifier,
    warning: Boolean = false,
) {
    val color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            if (warning) Icons.Rounded.Warning else Icons.Rounded.Info,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(18.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun problemText(problem: ClipProblem): String =
    when (problem) {
        is ClipProblem.OutOfRange -> {
            if (problem.durationMs < ClipLimits.MIN_MS) {
                stringResource(R.string.creator_clip_result_short, seconds(problem.durationMs))
            } else {
                stringResource(R.string.creator_clip_result_long, seconds(problem.durationMs))
            }
        }

        ClipProblem.Silent -> {
            stringResource(R.string.creator_clip_result_silent)
        }

        ClipProblem.Failed -> {
            stringResource(R.string.creator_clip_result_failed)
        }
    }

/** The visible part of the source as peaks, the selection highlighted and the playhead while playing. */
@Composable
private fun WaveformView(
    editor: ClipEditorUi,
    modifier: Modifier = Modifier,
) {
    val peaks by produceState(FloatArray(0), editor.source, editor.viewStartMs, editor.viewEndMs) {
        value = withContext(Dispatchers.Default) { visiblePeaks(editor.source, editor.viewStartMs, editor.viewEndMs) }
    }
    val selected = MaterialTheme.colorScheme.primary
    val unselected = MaterialTheme.colorScheme.outlineVariant
    val selection = MaterialTheme.colorScheme.primaryContainer
    val playhead = MaterialTheme.colorScheme.tertiary
    val description = stringResource(R.string.creator_clip_waveform)
    Canvas(modifier.semantics { contentDescription = description }) {
        val span = (editor.viewEndMs - editor.viewStartMs).coerceAtLeast(1).toFloat()

        fun x(ms: Long) = (ms - editor.viewStartMs) / span * size.width
        val left = x(editor.startMs).coerceIn(0f, size.width)
        val right = x(editor.endMs).coerceIn(0f, size.width)
        drawRect(selection, topLeft = Offset(left, 0f), size = Size(right - left, size.height))
        if (peaks.isNotEmpty()) {
            val bar = size.width / peaks.size
            val middle = size.height / 2
            peaks.forEachIndexed { i, peak ->
                val cx = i * bar + bar / 2
                val h = maxOf(1.dp.toPx(), peak * size.height * 0.9f)
                drawLine(
                    color = if (cx in left..right) selected else unselected,
                    start = Offset(cx, middle - h / 2),
                    end = Offset(cx, middle + h / 2),
                    strokeWidth = maxOf(1f, bar * 0.6f),
                )
            }
        }
        editor.playheadMs?.let { ms ->
            val px = x(ms)
            drawLine(playhead, Offset(px, 0f), Offset(px, size.height), strokeWidth = 2.dp.toPx())
        }
    }
}

private fun visiblePeaks(
    source: PcmAudio,
    startMs: Long,
    endMs: Long,
): FloatArray {
    val from = source.frameAt(startMs)
    val to = source.frameAt(endMs)
    if (to <= from) return FloatArray(0)
    return Waveform.peaks(source, WAVEFORM_BARS, from, to)
}

private const val WAVEFORM_BARS = 160
