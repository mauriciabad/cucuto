package app.mauri.cucuto.ui.feedback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos

/**
 * Seconds since [beat] at each frame, for [durationSeconds]; -1 while there is no beat. Read it only in draw or
 * layer blocks (`Canvas`, `graphicsLayer {}`) so a running animation redraws without recomposing.
 *
 * Because it is measured from the beat's own timestamp, a late first frame does not delay the timeline: the
 * visual stays on the sound and the haptic. A beat older than [durationSeconds] shows its end state right away.
 */
@Composable
fun rememberBeatSeconds(
    beat: FeedbackBeat?,
    durationSeconds: Float,
): FloatState {
    val seconds = remember(beat) { mutableFloatStateOf(if (beat == null) NO_BEAT else 0f) }
    LaunchedEffect(beat) {
        if (beat == null) return@LaunchedEffect
        val firstFrame = withFrameNanos { it }
        do {
            val frame =
                withFrameNanos { frameNanos ->
                    frameNanos.also { seconds.floatValue = beat.elapsedSeconds(it) }
                }
            // Safety net: never animate longer than the timeline itself, even with a beat from another time base.
            val running = (frame - firstFrame) / NANOS_PER_SECOND < durationSeconds + 1f
        } while (seconds.floatValue < durationSeconds && running)
        if (seconds.floatValue < durationSeconds) seconds.floatValue = durationSeconds
    }
    return seconds
}

/** [rememberBeatSeconds] value while no beat was fired. */
const val NO_BEAT = -1f

private const val NANOS_PER_SECOND = 1_000_000_000f
