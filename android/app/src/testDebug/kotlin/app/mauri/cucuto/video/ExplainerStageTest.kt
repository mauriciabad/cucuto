package app.mauri.cucuto.video

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.ui.components.CuckooDoor
import app.mauri.cucuto.ui.feedback.Feedback
import app.mauri.cucuto.ui.feedback.FeedbackBeat
import app.mauri.cucuto.ui.theme.CucutoTheme
import coil3.compose.AsyncImage
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.robolectric.annotation.Config
import java.io.File

/**
 * The explainer video's full-frame pictures, drawn at 1920 × 1080: the stage behind the phone shots (cream, sound
 * rings, the phone's outline, the vibration ripples), the door (shot 2) and the end card (shot 10).
 */
@Config(qualifiers = "w960dp-h540dp-land-xhdpi")
internal class ExplainerStageTest(
    language: String,
) : ExplainerTest(language) {
    /** The still stage behind every phone shot, and where the screen goes on it (`layout.properties`). */
    @Test
    fun stage() {
        assumeFirstLanguage()
        val dir = sceneDir("stage", common = true)
        // One recorded frame (the recorder steps the clock before it captures), kept as the still background.
        val recorder = SceneRecorder(compose, dir, "still", 0, STILL_MS)
        scene(recorder, phone = false, script = {}) { Canvas(Modifier.fillMaxSize()) { phoneStage() } }
        File(
            dir,
            "png/${File(dir, "frames.txt").readLines().first()}",
        ).copyTo(File(dir, "background.png"), overwrite = true)
        File(dir, "layout.properties").writeText(StageLayout.properties())
    }

    /**
     * The phone vibrating: two ripples, 120 ms apart (the frog's haptic is two clicks), spreading from the outline.
     * `explainer.main.kts` lays this sequence over the stage at each ripple time of the phone scenes.
     */
    @Test
    fun ripple() {
        assumeFirstLanguage()
        val recorder = SceneRecorder(compose, sceneDir("ripple", common = true), "overlay", 0, RIPPLE_MS)
        scene(recorder, phone = false, script = {}) {
            Canvas(Modifier.fillMaxSize()) {
                val t = recorder.videoTime.doubleValue.toFloat()
                phoneStage(listOf(t / RIPPLE_LIFE_MS, (t - RIPPLE_GAP_MS) / RIPPLE_LIFE_MS))
            }
        }
    }

    /** Shot 2, 5–9 s: the brand's signature, the door pops open at 5.45 s, the bird calls, the wordmark fades in. */
    @Test
    fun shot02TheDoor() {
        assumeFirstLanguage()
        val recorder = SceneRecorder(compose, sceneDir("02-the-door", common = true), "full", 5_000, 9_000)
        var beat by mutableStateOf<FeedbackBeat?>(null)
        var wordmark by mutableStateOf(false)
        scene(recorder, phone = false, script = {
            at(5.45 - FRAME) { beat = FeedbackBeat(Feedback.DoorOpen, nanoTime()) }
            cue(5.45, ASSIGN, UI_DB)
            at(6.0) { wordmark = true }
        }) {
            Stage(ringsCenter = Offset(StageLayout.WIDTH / 2f, 470f)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CuckooDoor(beat = beat, modifier = Modifier.height(300.dp))
                    Spacer(Modifier.height(24.dp))
                    Reveal(wordmark) { Brand("logo/cucuto-wordmark-light.svg", Modifier.height(WORDMARK_HEIGHT)) }
                }
            }
        }
    }

    /** Shot 10, 53–58 s: the logo, the line, the address and the fine print, in the cut's language. */
    @Test
    fun shot10EndCard() {
        val recorder = SceneRecorder(compose, sceneDir("10-end-card"), "full", 53_000, 58_000)
        var shown by mutableIntStateOf(0)
        scene(recorder, phone = false, script = {
            at(53.2 - FRAME) { shown = 1 }
            cue(53.2, ASSIGN, UI_DB)
            at(53.5) { shown = 2 }
            at(53.8) { shown = 3 }
            at(54.1) { shown = 4 }
        }) {
            Stage(ringsCenter = Offset(StageLayout.WIDTH / 2f, StageLayout.HEIGHT / 2f)) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(horizontal = 48.dp),
                ) {
                    Reveal(
                        shown >= 1,
                        scale = true,
                    ) { Brand("logo/cucuto-logo-light.svg", Modifier.height(LOGO_HEIGHT)) }
                    Spacer(Modifier.height(8.dp))
                    Reveal(shown >= 2) {
                        Text(
                            strings.tagline,
                            style = MaterialTheme.typography.displaySmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Reveal(shown >= 3) {
                        Text(
                            "cucuto.mauri.app",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Reveal(shown >= 4) {
                        Text(
                            strings.finePrint,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }

    /** Common pictures (stage, door) are drawn once, in the first requested cut. */
    private fun assumeFirstLanguage() {
        val first =
            System
                .getProperty(LANGUAGES_PROPERTY, "en")
                .split(',')
                .first()
                .trim()
        assumeTrue("Drawn once, in the first cut", language == first)
    }

    @Composable
    private fun Stage(
        ringsCenter: Offset,
        content: @Composable () -> Unit,
    ) {
        CucutoTheme(darkTheme = false, dynamicColor = false) {
            Box(
                Modifier.fillMaxSize().drawBehind { stageBackground(ringsCenter) },
                contentAlignment = Alignment.Center,
            ) { content() }
        }
    }

    /** Fades and slides [content] in on a spring when [visible]; it keeps its place while hidden, so nothing moves. */
    @Composable
    private fun Reveal(
        visible: Boolean,
        scale: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        val progress by animateFloatAsState(if (visible) 1f else 0f, MaterialTheme.motionScheme.slowSpatialSpec())
        Box(
            Modifier.graphicsLayer {
                alpha = progress.coerceIn(0f, 1f)
                translationY = (1 - progress) * REVEAL_OFFSET.toPx()
                if (scale) {
                    scaleX = REVEAL_SCALE + (1 - REVEAL_SCALE) * progress
                    scaleY = scaleX
                }
            },
        ) { content() }
    }

    /** A file of `brand/`, drawn as is. */
    @Composable
    private fun Brand(
        path: String,
        modifier: Modifier = Modifier,
    ) {
        AsyncImage(
            model = File(BRAND_DIR, path),
            contentDescription = null,
            contentScale = ContentScale.FillHeight,
            modifier = modifier,
        )
    }

    private companion object {
        const val FRAME = 1.0 / FPS
        const val STILL_MS = 17L
        const val RIPPLE_MS = 800L
        const val RIPPLE_LIFE_MS = 650f
        const val RIPPLE_GAP_MS = 120f
        val WORDMARK_HEIGHT = 72.dp
        val LOGO_HEIGHT = 120.dp
        val REVEAL_OFFSET = 24.dp
        const val REVEAL_SCALE = 0.8f

        /** The brand sources, relative to the module directory (the working directory of Gradle's unit tests). */
        val BRAND_DIR = File("../../brand")
    }
}
