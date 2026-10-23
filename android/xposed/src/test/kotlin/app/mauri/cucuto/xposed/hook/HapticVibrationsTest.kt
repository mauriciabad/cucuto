package app.mauri.cucuto.xposed.hook

import app.mauri.cucuto.core.haptics.HapticCapabilities
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticVibrationsTest {
    private val click = Haptic.Custom(listOf(HapticEvent.Preset(0, HapticPreset.CLICK, 800)))
    private val tick = Haptic.Custom(listOf(HapticEvent.Preset(0, HapticPreset.TICK, 500)))
    private val lowTick = Haptic.Custom(listOf(HapticEvent.Preset(0, HapticPreset.LOW_TICK, 300)))
    private val device = HapticCapabilities.GUARANTEED_ENVELOPES

    private val builds = mutableListOf<Pair<Haptic.Custom, HapticCapabilities>>()

    private fun vibrations(
        capacity: Int = HapticVibrations.CACHE_SIZE,
        build: (Haptic.Custom) -> String? = { "effect:${it.events.first()}" },
    ) = HapticVibrations<String>(
        build = { haptic, capabilities ->
            builds += haptic to capabilities
            build(haptic)
        },
        capacity = capacity,
    )

    @Test
    fun keepOffAndMissingHapticsNeverBuild() {
        val v = vibrations().apply { useCapabilities(device) }
        assertSame(VibrationSwap.Keep, v.swapFor(null))
        assertSame(VibrationSwap.Keep, v.swapFor(Haptic.Keep))
        // The router resolves Auto; one that slips through keeps WhatsApp's vibration.
        assertSame(VibrationSwap.Keep, v.swapFor(Haptic.Auto))
        assertSame(VibrationSwap.Suppress, v.swapFor(Haptic.Off))
        assertTrue(builds.isEmpty())
    }

    @Test
    fun customHapticReplacesTheVibrationWithTheBuiltEffect() {
        val v = vibrations().apply { useCapabilities(device) }
        assertEquals(VibrationSwap.Replace("effect:${click.events.first()}"), v.swapFor(click))
        assertEquals(listOf(click to device), builds)
    }

    @Test
    fun customHapticKeepsTheOriginalUntilCapabilitiesAreKnown() {
        val v = vibrations()
        assertNull(v.capabilities)
        assertSame(VibrationSwap.Keep, v.swapFor(click))
        assertTrue(builds.isEmpty())

        v.useCapabilities(device)
        assertTrue(v.swapFor(click) is VibrationSwap.Replace)
    }

    @Test
    fun onlyTheFirstCapabilitiesCount() {
        val v = vibrations()
        assertTrue(v.useCapabilities(device))
        assertFalse(v.useCapabilities(HapticCapabilities()))
        assertSame(device, v.capabilities)
        v.swapFor(click)
        assertSame(device, builds.single().second)
    }

    @Test
    fun effectsAreBuiltOncePerHaptic() {
        val v = vibrations().apply { useCapabilities(device) }
        val first = v.swapFor(click)
        assertSame(first, v.swapFor(click))
        // An equal haptic from a republished table reuses the effect.
        assertSame(first, v.swapFor(Haptic.Custom(click.events.toList())))
        v.swapFor(tick)
        assertEquals(listOf(click, tick), builds.map { it.first })
    }

    @Test
    fun unbuildableHapticsKeepTheOriginalAndAreNotRetried() {
        val v = vibrations(build = { null }).apply { useCapabilities(device) }
        assertSame(VibrationSwap.Keep, v.swapFor(click))
        assertSame(VibrationSwap.Keep, v.swapFor(click))
        assertEquals(1, builds.size)
    }

    @Test
    fun aThrowingBuildKeepsTheOriginal() {
        val v =
            vibrations(
                build = { throw IllegalStateException("platform says no") },
            ).apply { useCapabilities(device) }
        assertSame(VibrationSwap.Keep, v.swapFor(click))
    }

    @Test
    fun theCacheIsBoundedLeastRecentlyUsedFirst() {
        val v = vibrations(capacity = 2).apply { useCapabilities(device) }
        v.swapFor(click)
        v.swapFor(tick)
        v.swapFor(click) // click is now the most recently used
        v.swapFor(lowTick) // evicts tick
        v.swapFor(click)
        v.swapFor(tick)
        assertEquals(listOf(click, tick, lowTick, tick), builds.map { it.first })
    }

    @Test
    fun warmUpBuildsWithoutCaching() {
        val v = vibrations()
        val fallback = HapticCapabilities(builderSupported = true)
        assertEquals("effect:${click.events.first()}", v.warmUp(click, fallback))
        assertNull(v.capabilities)
        v.useCapabilities(device)
        v.swapFor(click)
        assertEquals(listOf(click to fallback, click to device), builds)
    }

    @Test
    fun warmUpNeverThrows() {
        assertNull(vibrations(build = { throw LinkageError("missing class") }).warmUp(click, device))
    }
}
