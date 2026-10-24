package app.mauri.cucuto.ui.creator

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.R

/**
 * Recording a clip: one big button (a cookie that turns into a square stop while recording), the live level and the
 * time left. The microphone permission is asked on the first tap of Record. Stopping opens the clip editor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecorderPage(
    recorder: RecorderUi,
    onBack: () -> Unit,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recording = recorder.recording
    val level = recorder.levels.lastOrNull() ?: 0f
    val pulse by animateFloatAsState(
        targetValue = if (recording) 1f + level.coerceIn(0f, 1f) * PULSE else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "pulse",
    )
    val colors = MaterialTheme.colorScheme
    val buttonColor = if (recording) colors.error else colors.primary
    val buttonContent = if (recording) colors.onError else colors.onPrimary
    val buttonShape = if (recording) MaterialShapes.Square.toShape() else MaterialShapes.Cookie12Sided.toShape()
    val buttonIcon = if (recording) R.drawable.ic_creator_stop else R.drawable.ic_creator_mic
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.creator_record_title)) },
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
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        ) {
            Text(
                stringResource(if (recording) R.string.creator_record_recording else R.string.creator_record_hint),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val label = stringResource(if (recording) R.string.creator_record_stop else R.string.creator_record_start)
            Surface(
                onClick = if (recording) onStop else onRecord,
                shape = buttonShape,
                color = buttonColor,
                contentColor = buttonContent,
                modifier =
                    Modifier.size(160.dp).scale(pulse).semantics {
                        contentDescription = label
                        role = Role.Button
                    },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(buttonIcon),
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                    )
                }
            }
            LevelMeter(recorder.levels, Modifier.fillMaxWidth().height(56.dp))
            Text(
                stringResource(
                    R.string.creator_record_time,
                    seconds(recorder.elapsedMs),
                    seconds(ClipLimits.RECORDING_MS),
                ),
                style = MaterialTheme.typography.titleLarge,
            )
            LinearWavyProgressIndicator(
                progress = { recorder.elapsedMs.toFloat() / ClipLimits.RECORDING_MS },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Recent levels as bars, newest on the right. */
@Composable
private fun LevelMeter(
    levels: List<Float>,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val bars = LEVEL_BARS
        val gap = 3.dp.toPx()
        val width = (size.width - gap * (bars - 1)) / bars
        val shown = levels.takeLast(bars)
        val offset = bars - shown.size
        shown.forEachIndexed { i, level ->
            // Perceived loudness: a square root lifts quiet speech so the meter moves.
            val h = maxOf(width, size.height * kotlin.math.sqrt(level.coerceIn(0f, 1f)))
            drawRoundRect(
                color = color,
                topLeft = Offset((offset + i) * (width + gap), (size.height - h) / 2),
                size = Size(width, h),
                cornerRadius = CornerRadius(width / 2),
            )
        }
    }
}

private const val LEVEL_BARS = 48
private const val PULSE = 0.15f
