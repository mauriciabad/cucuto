package app.mauri.cucuto.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records which classes and methods the main journeys run, so release builds ship them precompiled (#226):
 * `./gradlew :app:generateBaselineProfile` with a prepared emulator on adb writes
 * `app/src/main/generated/baselineProfiles/baseline-prof.txt` (and `startup-prof.txt`). See docs/baseline-profile.md.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    /** App start up to Home: also the Startup Profile, which lays out the DEX file for a faster start. */
    @Test
    fun startup() = rule.collect(packageName = TARGET_PACKAGE, includeInStartupProfile = true) { start() }

    /** Home, the character picker and Packs. */
    @Test
    fun mainJourneys() =
        rule.collect(packageName = TARGET_PACKAGE) {
            start()
            home()
            picker()
            packs()
        }
}
