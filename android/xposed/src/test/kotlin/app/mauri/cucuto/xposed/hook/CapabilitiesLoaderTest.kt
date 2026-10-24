package app.mauri.cucuto.xposed.hook

import app.mauri.cucuto.core.haptics.HapticCapabilities
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilitiesLoaderTest {
    private val device = HapticCapabilities.GUARANTEED_ENVELOPES
    private val generic = HapticCapabilities(builderSupported = true)

    /** A fake background thread: tasks wait in a queue with their delay until [runNext]. */
    private val posted = ArrayDeque<Pair<Long, () -> Unit>>()
    private val delays = ArrayList<Long>()
    private val used = ArrayList<Pair<HapticCapabilities, Boolean>>()

    private fun runNext() {
        val (delay, task) = posted.removeFirst()
        delays += delay
        task()
    }

    private fun loader(
        read: () -> HapticCapabilities?,
        use: (HapticCapabilities, Boolean) -> Unit = { c, r -> used += c to r },
        maxAttempts: Int = 5,
    ) = CapabilitiesLoader(
        read = read,
        use = use,
        post = { delay, task -> posted.addLast(delay to task) },
        fallback = { generic },
        firstDelayMillis = 2_000,
        retryMillis = 1_000,
        maxAttempts = maxAttempts,
    )

    @Test
    fun startOnlySchedulesTheFirstReadAfterTheDelay() {
        var reads = 0
        loader(read = {
            reads++
            device
        }).start()

        assertEquals(0, reads)
        assertEquals(listOf(2_000L), posted.map { it.first })
        runNext()
        assertEquals(1, reads)
        assertEquals(listOf(device to true), used)
        assertTrue(posted.isEmpty())
    }

    @Test
    fun retriesWhileTheServiceIsNotReadyThenUsesTheRead() {
        val answers = ArrayDeque(listOf(null, null, device))
        loader(read = { answers.removeFirst() }).start()

        while (posted.isNotEmpty()) runNext()
        assertEquals(listOf(2_000L, 1_000L, 1_000L), delays)
        assertEquals(listOf(device to true), used)
    }

    @Test
    fun aThrowingReadCountsAsNotReady() {
        var reads = 0
        loader(read = {
            if (reads++ == 0) error("vibrator service not up")
            device
        }).start()

        while (posted.isNotEmpty()) runNext()
        assertEquals(2, reads)
        assertEquals(listOf(device to true), used)
    }

    @Test
    fun retriesAreBoundedAndEndOnTheFallback() {
        var reads = 0
        loader(read = {
            reads++
            null
        }, maxAttempts = 4).start()

        while (posted.isNotEmpty()) runNext()
        assertEquals(4, reads)
        assertEquals(listOf(generic to false), used)
    }

    @Test
    fun neitherAFailingConsumerNorAFailingSchedulerEscapes() {
        loader(read = { device }, use = { _, _ -> error("boom") }).start()
        runNext()

        CapabilitiesLoader(read = { device }, use = { _, _ -> }, post = { _, _ -> error("no thread") }).start()
    }

    @Test
    fun customHapticsKeepWhatsAppsVibrationUntilTheBackgroundReadLands() {
        val click = Haptic.Custom(listOf(HapticEvent.Preset(atMs = 0, preset = HapticPreset.CLICK)))
        val vibrations = HapticVibrations<String>(build = { _, _ -> "effect" })
        val answers = ArrayDeque(listOf(null, device))
        loader(read = { answers.removeFirst() }, use = { c, _ -> vibrations.useCapabilities(c) }).start()

        // Alerts before the read (the path only does a volatile read): WhatsApp's vibration plays.
        assertSame(VibrationSwap.Keep, vibrations.swapFor(click))
        runNext()
        assertSame(VibrationSwap.Keep, vibrations.swapFor(click))
        runNext()
        assertSame(device, vibrations.capabilities)
        assertEquals(VibrationSwap.Replace("effect"), vibrations.swapFor(click))
    }
}
