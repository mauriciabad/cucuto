package app.mauri.cucuto.screenshots

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import app.mauri.cucuto.R
import app.mauri.cucuto.ui.theme.CucutoTheme
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.svg.SvgDecoder
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Base class of the screenshot tests (ADR 0032). Each screen is one small subclass with one test that calls
 * [screenshot] with the screen's stateless content and the made-up [DemoContent]:
 *
 * ```kotlin
 * class PacksScreenshotTest(variant: Variant) : ScreenshotTest(variant) {
 *     @Test
 *     fun packs() = screenshot("packs") { PacksContent(state = …, onBack = {}, …) }
 * }
 * ```
 *
 * Every test runs once per [Variant] with the brand colors (dynamic color off), on a 411 × 891 dp phone with a
 * drawn status bar and gesture handle ([SystemBars]) and matching system bar insets, so screens lay out edge to edge
 * as on a device. Animations run on the test clock and settle before the capture.
 *
 * - `./gradlew :app:recordRoborazziDebug` writes the golden images to `src/testDebug/screenshots/`.
 * - `./gradlew :app:verifyRoborazziDebug` (and CI) fails when a screen no longer matches its golden image.
 * - `./gradlew :app:exportWebScreenshots` also writes the website's images (the tests with a `web` name) to
 *   `web/src/lib/assets/images/screenshots/`, in English and, once the app is translated, in Spanish (`es/`).
 *
 * Screens still to cover, one subclass each, as soon as they are on `main`:
 * - TODO(#153): the app shell (navigation bar with Home, Packs, Discover and Settings) around Home.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotTest.DEVICE)
abstract class ScreenshotTest(
    private val variant: Variant,
) {
    /**
     * Theme and language of one run. English has golden images; Spanish runs only for the website export (the
     * `/es/` pages), and only once the app has Spanish strings: otherwise it is skipped.
     */
    enum class Variant(
        val dark: Boolean,
        val language: String,
    ) {
        Light(dark = false, language = "en"),
        Dark(dark = true, language = "en"),
        LightEs(dark = false, language = "es"),
        DarkEs(dark = true, language = "es"),
        ;

        val theme: String get() = if (dark) "dark" else "light"
        val isEnglish: Boolean get() = language == "en"
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val defaultLocale = Locale.getDefault()
    private val defaultTimeZone = TimeZone.getDefault()

    /**
     * Decodes sprites and covers on the test thread, so they are on screen before the capture, and pins the time
     * zone, so absolute dates and times look the same on every machine.
     */
    @OptIn(DelicateCoilApi::class)
    @Before
    fun deterministicEnvironment() {
        TimeZone.setDefault(TimeZone.getTimeZone(SCREENSHOT_TIME_ZONE))
        SingletonImageLoader.setUnsafe(
            ImageLoader
                .Builder(compose.activity)
                .components { add(SvgDecoder.Factory()) }
                .coroutineContext(Dispatchers.Unconfined)
                .build(),
        )
    }

    @After
    fun restoreEnvironment() {
        Locale.setDefault(defaultLocale)
        TimeZone.setDefault(defaultTimeZone)
    }

    /**
     * Renders [content] and captures the whole screen, dialogs and sheets included, as `<name>-light.png` or
     * `<name>-dark.png`. [web] is the website's name for the image, written only by `exportWebScreenshots`.
     * [settleMillis] of test-clock time pass before the capture: enough for entry animations and sheets to rest.
     */
    @OptIn(ExperimentalRoborazziApi::class)
    protected fun screenshot(
        name: String,
        web: String? = null,
        settleMillis: Long = SETTLE_MILLIS,
        content: @Composable () -> Unit,
    ) {
        val exportDir = System.getProperty(WEB_EXPORT_PROPERTY).orEmpty()
        val locale = Locale.forLanguageTag(variant.language)
        if (!variant.isEnglish) {
            assumeTrue("$variant runs only for the website export", web != null && exportDir.isNotEmpty())
            assumeTrue("The app has no ${variant.language} strings yet", isTranslated(locale))
            // Dates and numbers ("2 hours ago", "298 kB") follow the default locale.
            Locale.setDefault(locale)
        }

        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false) }
        compose.setContent { DeviceFrame(variant.dark, locale) { content() } }
        compose.mainClock.advanceTimeByFrame()
        applySystemBarInsets()
        compose.mainClock.advanceTimeBy(settleMillis)
        compose.waitForIdle()

        if (variant.isEnglish) captureScreenRoboImage("$name-${variant.theme}.png", goldenOptions)
        if (web != null && exportDir.isNotEmpty()) {
            val folder = if (variant.isEnglish) File(exportDir) else File(exportDir, variant.language)
            captureScreenRoboImage(File(folder, "$web-${variant.theme}.png"), webOptions)
        }
    }

    /** True when the app's strings exist in [locale] (checked on the home screen's main action). */
    private fun isTranslated(locale: Locale): Boolean {
        val activity = compose.activity
        val translated = activity.localized(locale).getString(R.string.home_fab)
        return translated != activity.localized(Locale.ENGLISH).getString(R.string.home_fab)
    }

    /** Gives the screen a status bar and a gesture navigation bar, like a phone drawing edge to edge. */
    private fun applySystemBarInsets() {
        compose.runOnUiThread {
            val density = compose.activity.resources.displayMetrics.density
            val insets =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(
                        WindowInsetsCompat.Type.statusBars(),
                        Insets.of(0, (STATUS_BAR.value * density).roundToInt(), 0, 0),
                    ).setInsets(
                        WindowInsetsCompat.Type.navigationBars(),
                        Insets.of(0, 0, 0, (NAVIGATION_BAR.value * density).roundToInt()),
                    ).build()
            val content = compose.activity.findViewById<ViewGroup>(android.R.id.content)
            ViewCompat.dispatchApplyWindowInsets(content.getChildAt(0), insets)
        }
    }

    companion object {
        /** A 411 × 891 dp phone (Pixel class) at xhdpi: 822 × 1782 px, halved for the golden images. */
        const val DEVICE = "w411dp-h891dp-xhdpi"

        /** System property with the folder for the website's images; set by `exportWebScreenshots`. */
        const val WEB_EXPORT_PROPERTY = "cucuto.screenshots.web"

        /** Time zone of every screenshot; [DemoContent] fixes absolute times (backups) in it. */
        const val SCREENSHOT_TIME_ZONE = "Europe/Madrid"

        private const val SETTLE_MILLIS = 3_000L
        private const val GOLDEN_SCALE = 0.5
        private const val WEB_SCALE = 0.6

        /** Every test runs in each variant; the test name ends in `[Light]`, `[Dark]`, `[LightEs]` or `[DarkEs]`. */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants(): List<Array<Any>> = Variant.entries.map { arrayOf(it) }

        /**
         * Golden images at half resolution (411 × 891 px), so they stay small in Git, compared with Roborazzi's
         * defaults: any visible pixel change fails. Robolectric Native Graphics renders the same bytes on macOS and
         * Linux (checked on 2026-10-24, ADR 0032), so goldens recorded on a Mac verify in CI.
         */
        private val goldenOptions by lazy {
            RoborazziOptions(recordOptions = RoborazziOptions.RecordOptions(resizeScale = GOLDEN_SCALE))
        }

        /** Website images: always written, 493 px wide (twice the largest slot on the home page). */
        @OptIn(ExperimentalRoborazziApi::class)
        private val webOptions by lazy {
            RoborazziOptions(
                taskType = RoborazziTaskType.Record,
                recordOptions = RoborazziOptions.RecordOptions(resizeScale = WEB_SCALE),
            )
        }
    }
}

/** [this] context with [locale] for its resources; still wraps the activity, so code that looks for it finds it. */
private fun Context.localized(locale: Locale): Context {
    val configuration = Configuration(resources.configuration).apply { setLocale(locale) }
    return ContextThemeWrapper(this, theme).apply { applyOverrideConfiguration(configuration) }
}

/** The screen in the requested theme and language, with the system bars drawn over it. */
@Composable
private fun DeviceFrame(
    dark: Boolean,
    locale: Locale,
    content: @Composable () -> Unit,
) {
    // Code that asks the configuration (isSystemInDarkTheme, stringResource) sees the same theme and language.
    val context = LocalContext.current
    val configuration =
        Configuration(LocalConfiguration.current).apply {
            uiMode =
                (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            setLocale(locale)
        }
    val localized = ContextThemeWrapper(context, context.theme).apply { applyOverrideConfiguration(configuration) }
    CompositionLocalProvider(LocalConfiguration provides configuration, LocalContext provides localized) {
        CucutoTheme(darkTheme = dark, dynamicColor = false) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                content()
                SystemBars()
            }
        }
    }
}
