package app.mauri.cucuto.video

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale

/**
 * Renders the explainer video's frames on the JVM (Robolectric Native Graphics, no device), 1920 × 1080 at 60 fps.
 * Every frame is a pure function of its time, so the recorder just sets the time, lets Compose draw one frame and
 * captures it.
 *
 * - [frames] runs only from `./gradlew :video:renderExplainerFrames` ([FRAMES] is the output folder). It writes each
 *   distinct frame once (`png/`), `frames.txt` (the PNG of every video frame), `cues.tsv`, `voice.tsv` and
 *   `video.properties`, read by `tools/video/explainer.main.kts` and `tools/video/voice.main.kts`. With [STILLS]
 *   (`-Pexplainer.stills=6.5,32.8`), it writes only those frames as `still-<time>.png`, to check a scene quickly.
 * - [everyShotRenders] runs in every build: the middle of each shot draws, so a broken scene fails CI, not the
 *   manual video workflow.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-land-xhdpi")
class RenderExplainerTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val time = mutableDoubleStateOf(0.0)
    private val timeline = ExplainerVideo

    @Test
    fun frames() {
        val out = System.getProperty(FRAMES).orEmpty()
        assumeTrue("Only ./gradlew :video:renderExplainerFrames renders the video", out.isNotEmpty())
        val dir = File(out).apply { mkdirs() }
        show()
        val stills =
            System
                .getProperty(STILLS)
                .orEmpty()
                .split(',')
                .mapNotNull { it.trim().toDoubleOrNull() }
        if (stills.isNotEmpty()) {
            for (at in stills) {
                File(dir, "still-${"%.2f".format(Locale.ROOT, at)}.png").outputStream().use {
                    capture(at).compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            return
        }
        val png =
            File(dir, "png").apply {
                deleteRecursively()
                mkdirs()
            }
        val total = Math.round(timeline.duration * FPS).toInt()
        val names = ArrayList<String>(total)
        val seen = HashMap<String, String>()
        val digest = MessageDigest.getInstance("SHA-256")
        var buffer: ByteBuffer? = null
        for (frame in 0 until total) {
            val bitmap = capture(frame.toDouble() / FPS)
            val bytes = buffer?.takeIf { it.capacity() == bitmap.byteCount } ?: ByteBuffer.allocate(bitmap.byteCount)
            buffer = bytes
            bytes.clear()
            bitmap.copyPixelsToBuffer(bytes)
            bytes.flip()
            digest.reset()
            digest.update(bytes)
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            names +=
                seen.getOrPut(hash) {
                    "%05d.png".format(frame).also { name ->
                        File(png, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }
        }
        File(dir, "frames.txt").writeText(names.joinToString("\n", postfix = "\n"))
        File(dir, "cues.tsv").writeText(Exports.cues(timeline))
        File(dir, "voice.tsv").writeText(Exports.voiceScript(timeline))
        File(dir, "video.properties").writeText(
            "fps=$FPS\nframes=$total\nduration=${timeline.duration}\nposter=$POSTER_AT\n" +
                "vibrations=${timeline.vibrations().joinToString(",")}\n",
        )
    }

    @Test
    fun everyShotRenders() {
        show()
        for (placed in timeline.placed) {
            val bitmap = capture(placed.start + placed.shot.duration / 2)
            assertEquals("frame width", WIDTH, bitmap.width)
            assertEquals("frame height", HEIGHT, bitmap.height)
            // Something is drawn besides the cream stage: count distinct colors on a coarse grid.
            val colors = HashSet<Int>()
            for (y in 0 until HEIGHT step GRID) for (x in 0 until WIDTH step GRID) colors += bitmap.getPixel(x, y)
            assertTrue("${placed.shot.id} draws something (${colors.size} colors)", colors.size > MIN_COLORS)
        }
    }

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent { ExplainerFrame(timeline, time.doubleValue) }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun capture(at: Double): Bitmap {
        time.doubleValue = at
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        // A still screen is not drawn again on its own; without this a frame can come out blank (ADR 0060).
        compose.runOnUiThread {
            compose.activity.window.decorView
                .invalidate()
        }
        return compose.onRoot().captureToImage().asAndroidBitmap()
    }

    companion object {
        const val FRAMES = "cucuto.video.frames"
        const val STILLS = "cucuto.video.stills"
        private const val FPS = 60
        private const val WIDTH = 1920
        private const val HEIGHT = 1080
        private const val GRID = 40
        private const val MIN_COLORS = 8
    }
}
