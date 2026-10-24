package app.mauri.cucuto.video

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.PriorityQueue
import kotlin.math.ceil
import kotlin.math.roundToLong

/** Frames per second of the video (docs/explainer-video.md: Cucuto's springs look choppy at 30 fps). */
internal const val FPS = 60

/**
 * Test-clock time per video frame. Compose's test clock produces frames every 16 ms and a video frame lasts 16.7 ms,
 * so one clock frame per video frame: the app plays at 96 % speed, which no one can see, and no frame is skipped or
 * doubled (ADR 0060). Everything scheduled on the scene ([SceneRecorder.at], cues, ripples) uses the video's time.
 */
internal const val CLOCK_FRAME_MS = 16L

/** A tap or drag the touch indicator draws: where, and when (video time, ms) the finger went down and up. */
@Immutable
internal data class TouchMark(
    val position: Offset,
    val downMs: Double,
    val upMs: Double,
)

/**
 * Records one scene of the explainer video: a Compose test steps its clock frame by frame while the scene's actions
 * (taps, state changes, navigation) run at their times on the video's timeline, and every frame is captured.
 *
 * Output in [dir] (read by `tools/video/explainer.main.kts`):
 * - `png/<n>.png`: each distinct frame once (holds cost nothing);
 * - `frames.txt`: one line per video frame, the PNG it shows;
 * - `scene.properties`: kind (`phone` or `full`), start and end on the timeline (ms);
 * - `cues.tsv`: the sounds of the scene (time ms, file relative to the repository, gain dB, effect);
 * - `ripples.txt`: when the phone vibrates (time ms), drawn as ripples around the phone outline.
 */
internal class SceneRecorder(
    private val compose: AndroidComposeTestRule<*, out ComponentActivity>,
    private val dir: File,
    private val kind: String,
    val startMs: Long,
    val endMs: Long,
) {
    private class Action(
        val atMs: Double,
        val order: Int,
        val run: () -> Unit,
    )

    private val actions = PriorityQueue<Action>(compareBy<Action>({ it.atMs }, { it.order }))
    private var order = 0
    private val cues = mutableListOf<String>()
    private val ripples = mutableListOf<Long>()

    /** Video time (ms) of the frame being drawn: the touch indicator and stand-ins animate from it. */
    val videoTime = mutableDoubleStateOf(startMs.toDouble())

    /** Touches on screen, for the touch indicator ("Show taps"). */
    val touches = mutableStateListOf<TouchMark>()

    /** Runs [action] right before the first frame at or after [seconds] on the timeline. */
    fun at(
        seconds: Double,
        action: () -> Unit,
    ) {
        actions += Action(seconds * MILLIS, order++, action)
    }

    /** Plays [file] (relative to the repository) at [seconds]; [effect] `octave-down` makes the plain ping. */
    fun cue(
        seconds: Double,
        file: String,
        gainDb: Double,
        effect: String = "",
    ) {
        cues += listOf((seconds * MILLIS).roundToLong(), file, gainDb, effect).joinToString("\t")
    }

    /** The phone vibrates at [seconds]: ripples around its outline. */
    fun ripple(seconds: Double) {
        ripples += (seconds * MILLIS).roundToLong()
    }

    /** A tap on [node] at [seconds]: finger down, then up [holdMs] later (the click), with the touch indicator. */
    fun tap(
        seconds: Double,
        holdMs: Long = TAP_HOLD_MS,
        node: () -> SemanticsNodeInteraction,
    ) {
        var target: SemanticsNodeInteraction? = null
        at(seconds) {
            val interaction = node()
            target = interaction
            // Touch input takes the node's own coordinates; the indicator draws in the root's.
            val center = interaction.fetchSemanticsNode().boundsInRoot.center
            touches += TouchMark(center, seconds * MILLIS, seconds * MILLIS + holdMs)
            interaction.performTouchInput { down(this.center) }
        }
        at(seconds + holdMs / MILLIS) { target?.performTouchInput { up() } }
    }

    /**
     * A drag on [node] from [from] to [to] (fractions of its size) over [durationMs], one move per frame, as a finger
     * scrolling a list.
     */
    fun drag(
        seconds: Double,
        durationMs: Long,
        from: Offset,
        to: Offset,
        node: () -> SemanticsNodeInteraction,
    ) {
        var target: SemanticsNodeInteraction? = null
        var origin = Offset.Zero
        var size = Offset.Zero

        fun local(fraction: Float) =
            Offset(
                size.x * (from.x + (to.x - from.x) * fraction),
                size.y * (from.y + (to.y - from.y) * fraction),
            )
        at(seconds) {
            val interaction = node()
            target = interaction
            // Touch input takes the node's own coordinates; the indicator draws in the root's.
            val bounds = interaction.fetchSemanticsNode().boundsInRoot
            origin = bounds.topLeft
            size = Offset(bounds.width, bounds.height)
            touches += TouchMark(origin + local(0f), seconds * MILLIS, seconds * MILLIS + durationMs)
            interaction.performTouchInput { down(local(0f)) }
        }
        val steps = ceil(durationMs * FPS / MILLIS).toInt()
        for (step in 1..steps) {
            val fraction = step / steps.toFloat()
            at(seconds + durationMs / MILLIS * fraction) {
                // The indicator follows the finger: the newest mark is this drag.
                touches[touches.lastIndex] = touches.last().copy(position = origin + local(fraction))
                target?.performTouchInput { moveTo(local(fraction), delayMillis = 0) }
            }
        }
        at(seconds + durationMs / MILLIS) { target?.performTouchInput { up() } }
    }

    /** Steps the clock through the scene, running the actions and capturing every frame. */
    fun record() {
        val png =
            File(dir, "png").apply {
                deleteRecursively()
                mkdirs()
            }
        val origin = compose.mainClock.currentTime
        val total = ((endMs - startMs) * FPS / MILLIS.toLong()).toInt()
        val names = ArrayList<String>(total)
        val seen = HashMap<String, String>()
        val digest = MessageDigest.getInstance("SHA-256")
        var buffer: ByteBuffer? = null
        for (frame in 0 until total) {
            val time = startMs + frame * MILLIS / FPS
            videoTime.doubleValue = time
            while (actions.isNotEmpty() && actions.peek()!!.atMs <= time) actions.poll()!!.run()
            Snapshot.sendApplyNotifications()
            val target = origin + frame * CLOCK_FRAME_MS
            val delta = target - compose.mainClock.currentTime
            if (delta > 0) compose.mainClock.advanceTimeBy(delta, ignoreFrameDuration = true)
            // A still screen is not drawn again on its own; without this, a scene's first frame can come out blank.
            compose.runOnUiThread {
                compose.activity.window.decorView
                    .invalidate()
            }
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val bytes = buffer?.takeIf { it.capacity() == bitmap.byteCount } ?: ByteBuffer.allocate(bitmap.byteCount)
            buffer = bytes
            bytes.clear()
            bitmap.copyPixelsToBuffer(bytes)
            bytes.flip()
            digest.reset()
            digest.update(bytes)
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val name =
                seen.getOrPut(hash) {
                    "%05d.png".format(frame).also { file ->
                        File(png, file).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }
            names += name
        }
        File(dir, "frames.txt").writeText(names.joinToString("\n", postfix = "\n"))
        File(dir, "scene.properties").writeText("kind=$kind\nstart=$startMs\nend=$endMs\nframes=$total\n")
        File(dir, "cues.tsv").writeText(cues.joinToString("") { "$it\n" })
        File(dir, "ripples.txt").writeText(ripples.joinToString("") { "$it\n" })
    }

    companion object {
        private const val MILLIS = 1000.0
        private const val TAP_HOLD_MS = 90L
    }
}
