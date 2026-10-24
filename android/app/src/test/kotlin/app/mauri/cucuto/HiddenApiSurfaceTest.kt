package app.mauri.cucuto

import app.mauri.cucuto.testing.ClassGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app process must never touch the vibration APIs that are public only from API 37.2: on the owner's 37.1 build
 * they exist but are blocked hidden API for apps, and every access logs a `hiddenapi` denial (issue #139). Only the
 * module, inside `system_server`, may use them, through `:core:haptics`' `HapticEffects`.
 *
 * Walks the static class graph from every class of the app (and the public `:core:haptics` entry points) through our
 * own code, and checks that nothing reachable refers to that surface, even by reflection, or reaches the module-only
 * `HapticEffects`/`BuilderEffects`. Static references are a superset of what can load at runtime, so a pass is
 * conclusive. The `:xposed` hook classes ship in the same APK but load only in `system_server`; the app reaches the
 * few shared ones it needs (targets, events) and must not reach the hook.
 */
class HiddenApiSurfaceTest {
    private val graph = ClassGraph()

    @Test
    fun theAppNeverReachesTheHiddenVibrationApis() {
        val seeds =
            graph.classesBeside(APP_CLASS) + graph.classesBeside(HILT_APP_CLASS) + PUBLIC_HAPTICS +
                HAPTICS_PACKAGE_SEEDS
        assertTrue("app classes not found on the test classpath", seeds.size > 50)
        val reachable = graph.reachableFrom(seeds.distinct())
        assertTrue(
            "the walk must reach the app's haptic code",
            reachable.keys.containsAll(listOf(PUBLIC_EFFECTS, "app/mauri/cucuto/voice/MediaVoicePreview")),
        )

        val problems =
            reachable.keys.flatMap { name ->
                val path = graph.pathTo(name, reachable).joinToString(" → ")
                buildList {
                    if (name in MODULE_ONLY) add("$path is module-only")
                    graph.refs(name)?.let { refs -> violations(refs).forEach { add("$path uses $it") } }
                }
            }
        assertEquals(
            "Hidden vibration API reachable from the app process:\n" + problems.joinToString("\n"),
            0,
            problems.size,
        )
    }

    @Test
    fun theWalkFindsTheModulesBuilderPath() {
        // The detector works: from the module's entry point it finds the Builder, its probe and the hidden classes.
        val reachable = graph.reachableFrom(listOf(MODULE_EFFECTS))
        assertTrue(BUILDER_EFFECTS in reachable)
        val found = reachable.keys.flatMap { name -> graph.refs(name)?.let(::violations).orEmpty() }.toSet()
        assertTrue(found.toString(), "class android/os/VibrationEffect\$Builder" in found)
        assertTrue(found.toString(), "class android/os/VibrationEffect\$Preset" in found)
        assertTrue(found.toString(), "class android/os/VibrationEffect\$Envelope" in found)
        assertTrue(found.toString(), "reflection \"android.os.VibrationEffect\$Builder\"" in found)
    }

    private fun violations(refs: ClassGraph.ClassRefs): List<String> =
        buildList {
            refs.classes.filter { it in HIDDEN_CLASSES }.forEach { add("class $it") }
            refs.members.filter { it in HIDDEN_MEMBERS }.forEach { add("member $it") }
            refs.strings
                .filter { s -> HIDDEN_CLASSES.any { s == it.replace('/', '.') } }
                .forEach { add("reflection \"$it\"") }
        }

    private companion object {
        const val APP_CLASS = "app/mauri/cucuto/MainActivity"
        const val HILT_APP_CLASS = "app/mauri/cucuto/Hilt_CucutoApplication"
        const val PUBLIC_EFFECTS = "app/mauri/cucuto/core/haptics/PublicHapticEffects"
        const val MODULE_EFFECTS = "app/mauri/cucuto/core/haptics/HapticEffects"
        const val BUILDER_EFFECTS = "app/mauri/cucuto/core/haptics/BuilderEffects"
        val PUBLIC_HAPTICS = listOf(PUBLIC_EFFECTS)

        /** The rest of `:core:haptics` the app may use: capabilities, the planner and auto haptics. */
        val HAPTICS_PACKAGE_SEEDS =
            listOf(
                "app/mauri/cucuto/core/haptics/HapticCapabilities",
                "app/mauri/cucuto/core/haptics/HapticResolutionKt",
                "app/mauri/cucuto/core/haptics/auto/AutoHaptics",
            )

        val MODULE_ONLY = setOf(MODULE_EFFECTS, BUILDER_EFFECTS)

        /**
         * What the 37.2 SDK adds to the vibration API over 37.0 (compare the two `android.jar`s): hidden on 37.1.
         * `VibrationEffect.Builder`, `Envelope`, `Event`, `Preset` and `VibrationParseException`, plus four members.
         */
        val HIDDEN_CLASSES =
            setOf("Builder", "Envelope", "Event", "Preset", "VibrationParseException")
                .map { "android/os/VibrationEffect\$$it" }
                .toSet()
        val HIDDEN_MEMBERS =
            setOf(
                "android/os/VibrationEffect.getEvents",
                "android/os/VibrationEffect.parseFromXml",
                "android/os/Vibrator.getPresetDuration",
                "android/os/Vibrator.isPresetSupported",
            )
    }
}
