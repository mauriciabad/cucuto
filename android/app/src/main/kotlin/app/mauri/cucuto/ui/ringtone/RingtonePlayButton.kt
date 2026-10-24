package app.mauri.cucuto.ui.ringtone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.R

/**
 * Play, pause and stop for a ringtone preview (#246, owner update 2026-10-24 on #242): [preview] is this ringtone's
 * preview when it is ringing or paused, else null. A ring around the button shows how far round the ring cycle it is;
 * the preview rings like the call until the user stops it.
 */
@Composable
fun RingtonePlayButton(
    preview: PreviewPlaying<*>?,
    name: String,
    onToggle: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val active = preview != null
    val ringing = active && !preview.paused
    // The ring goes round once per ring file, frame by frame while it rings; it holds still when paused.
    var now by remember { mutableLongStateOf(PreviewClock.nowMs()) }
    LaunchedEffect(preview?.id, ringing, preview?.cycleMs) {
        now = PreviewClock.nowMs()
        while (ringing && preview?.cycleMs != null) withFrameMillis { now = PreviewClock.nowMs() }
    }
    val progress = preview?.progressAt(now) ?: 0f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier,
    ) {
        if (active) {
            IconButton(onClick = onStop) {
                Icon(StopIcon, contentDescription = stringResource(R.string.ringtone_picker_stop, name))
            }
        }
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(52.dp)) {
            if (active) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier =
                        Modifier.size(52.dp).semantics {
                            progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                        },
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    strokeWidth = 3.dp,
                )
            }
            val label =
                stringResource(
                    if (ringing) R.string.ringtone_picker_pause else R.string.ringtone_picker_play,
                    name,
                )
            // "Playing" or "Paused" in words, not "on" and "off" (#226).
            val stateLabel =
                when {
                    ringing -> stringResource(R.string.a11y_playing)
                    active -> stringResource(R.string.a11y_paused)
                    else -> null
                }
            FilledTonalIconToggleButton(
                checked = ringing,
                onCheckedChange = { onToggle() },
                enabled = enabled,
                modifier =
                    Modifier.semantics {
                        contentDescription = label
                        if (stateLabel != null) stateDescription = stateLabel
                    },
            ) {
                Icon(if (ringing) PauseIcon else Icons.Rounded.PlayArrow, contentDescription = null)
            }
        }
    }
}

/** Material "stop" (rounded), missing from `material-icons-core`; path data from Material Icons (Apache-2.0). */
internal val StopIcon: ImageVector by lazy {
    ImageVector
        .Builder("Stop", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = addPathNodes("M8 6h8c1.1 0 2 .9 2 2v8c0 1.1-.9 2-2 2H8c-1.1 0-2-.9-2-2V8c0-1.1.9-2 2-2z"),
            fill = SolidColor(Color.Black),
        ).build()
}

/** Material "pause" (rounded), missing from `material-icons-core`; path data from Material Icons (Apache-2.0). */
internal val PauseIcon: ImageVector by lazy {
    ImageVector
        .Builder("Pause", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData =
                addPathNodes(
                    "M8 19c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2s-2 .9-2 2v10c0 1.1.9 2 2 2zm6-12v10c0 1.1.9 2 2 2s2-.9 2-2" +
                        "V7c0-1.1-.9-2-2-2s-2 .9-2 2z",
                ),
            fill = SolidColor(Color.Black),
        ).build()
}
