package app.mauri.cucuto.video

import android.content.Context
import android.content.res.Configuration
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import app.mauri.cucuto.screenshots.NAVIGATION_BAR
import app.mauri.cucuto.screenshots.ScreenshotTest
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.svg.SvgDecoder
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt
import app.mauri.cucuto.screenshots.STATUS_BAR as STATUS_BAR_HEIGHT

/**
 * Base of the explainer video's frame tests (ADR 0060). They run only from `./gradlew :app:renderExplainerFrames`,
 * which sets [FRAMES_PROPERTY] (where the frames go) and [LANGUAGES_PROPERTY] (`en`, or `en,es`); a normal build skips
 * them. Each test records one scene with a [SceneRecorder].
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal abstract class ExplainerTest(
    protected val language: String,
) {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val defaultLocale = Locale.getDefault()
    private val defaultTimeZone = TimeZone.getDefault()

    protected val strings: VideoStrings get() = VideoStrings.of(language)

    /** The test clock in the `System.nanoTime()` base Compose frame times use under test (ms × 10⁶). */
    protected val nanoTime: () -> Long = { compose.mainClock.currentTime * NANOS_PER_MILLI }

    @OptIn(DelicateCoilApi::class)
    @Before
    fun setUp() {
        val frames = System.getProperty(FRAMES_PROPERTY).orEmpty()
        assumeTrue("Only ./gradlew :app:renderExplainerFrames renders the video", frames.isNotEmpty())
        val languages = System.getProperty(LANGUAGES_PROPERTY, "en").split(',').map { it.trim() }
        assumeTrue("$language is not requested", language in languages)
        TimeZone.setDefault(TimeZone.getTimeZone(ScreenshotTest.SCREENSHOT_TIME_ZONE))
        Locale.setDefault(Locale.forLanguageTag(language))
        // Sprites decode on the test thread, so they are on screen in the first frame.
        SingletonImageLoader.setUnsafe(
            ImageLoader
                .Builder(compose.activity)
                .components { add(SvgDecoder.Factory()) }
                .coroutineContext(Dispatchers.Unconfined)
                .build(),
        )
    }

    @After
    fun tearDown() {
        Locale.setDefault(defaultLocale)
        TimeZone.setDefault(defaultTimeZone)
    }

    /** The folder of [scene]'s frames: `<frames>/<language>/<scene>`, or `<frames>/common/<scene>`. */
    protected fun sceneDir(
        scene: String,
        common: Boolean = false,
    ): File = File(System.getProperty(FRAMES_PROPERTY)!!, if (common) "common/$scene" else "$language/$scene")

    /** A string of the app in the video's language. */
    protected fun str(
        @StringRes id: Int,
        vararg args: Any,
    ): String = localized().getString(id, *args)

    /** A plural string of the app in the video's language. */
    protected fun plural(
        @PluralsRes id: Int,
        count: Int,
    ): String = localized().resources.getQuantityString(id, count, count)

    private fun localized(): Context {
        val configuration =
            Configuration(compose.activity.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language))
            }
        return compose.activity.createConfigurationContext(configuration)
    }

    /**
     * Sets up [content] for a scene from [startS] to [endS] on the timeline, lets [preRollMs] of app time pass (entry
     * animations that happen before the scene starts), then runs [script] (the scene's actions) and records it.
     */
    protected fun scene(
        recorder: SceneRecorder,
        preRollMs: Long = 0,
        phone: Boolean = true,
        script: SceneRecorder.() -> Unit,
        content: @Composable () -> Unit,
    ) {
        compose.mainClock.autoAdvance = false
        if (phone) compose.runOnUiThread { WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false) }
        compose.setContent {
            if (phone) VideoPhone(recorder, language, nanoTime) { content() } else content()
        }
        compose.mainClock.advanceTimeByFrame()
        if (phone) applySystemBarInsets()
        if (preRollMs > 0) compose.mainClock.advanceTimeBy(preRollMs)
        recorder.script()
        recorder.record()
    }

    /** The same fake system bar insets as the screenshot tests, so screens lay out edge to edge. */
    private fun applySystemBarInsets() {
        compose.runOnUiThread {
            val density = compose.activity.resources.displayMetrics.density
            val insets =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(
                        WindowInsetsCompat.Type.statusBars(),
                        Insets.of(0, (STATUS_BAR_HEIGHT.value * density).roundToInt(), 0, 0),
                    ).setInsets(
                        WindowInsetsCompat.Type.navigationBars(),
                        Insets.of(0, 0, 0, (NAVIGATION_BAR.value * density).roundToInt()),
                    ).build()
            val content = compose.activity.findViewById<ViewGroup>(android.R.id.content)
            ViewCompat.dispatchApplyWindowInsets(content.getChildAt(0), insets)
        }
    }

    companion object {
        /** System property with the frames folder; set by `renderExplainerFrames`. */
        const val FRAMES_PROPERTY = "cucuto.video.frames"

        /** System property with the cuts to render: `en` (default) or `en,es`. */
        const val LANGUAGES_PROPERTY = "cucuto.video.languages"

        private const val NANOS_PER_MILLI = 1_000_000L

        /** UI sounds sit 6 dB under the character clips (docs/explainer-video.md, Sound design). */
        const val CLIP_DB = -3.0
        const val UI_DB = -9.0

        const val ASSIGN = "brand/sounds/assign.ogg"
        const val SUCCESS = "brand/sounds/success.ogg"
        const val TAP = "brand/sounds/tap.ogg"

        fun clip(
            pack: String,
            character: String,
            clip: String,
        ) = "packs/$pack/$character/clips/$clip.ogg"

        /** Every test runs once per cut; the test name ends in `[en]` or `[es]`. */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun languages(): List<Array<Any>> = listOf(arrayOf("en"), arrayOf("es"))
    }
}
