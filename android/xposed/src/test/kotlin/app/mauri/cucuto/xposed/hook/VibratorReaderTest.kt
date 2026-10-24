package app.mauri.cucuto.xposed.hook

import app.mauri.cucuto.core.haptics.HapticCapabilities
import app.mauri.cucuto.core.routing.HapticPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VibratorReaderTest {
    @Test
    fun theContextIsNotAskedBeforeTheVibratorServiceIsRegistered() {
        // The system context caches its VibratorManager: one created before the service exists stays broken.
        var asked = 0
        val reader =
            VibratorReader(context = {
                asked++
                null
            }, serviceRegistered = { false })
        assertNull(reader.read())
        assertEquals(0, asked)
    }

    @Test
    fun readingNeverThrows() {
        assertNull(VibratorReader(context = { null }, serviceRegistered = { true }).read())
        assertNull(VibratorReader(context = { error("no system context yet") }, serviceRegistered = { true }).read())
        assertNull(VibratorReader(context = { null }, serviceRegistered = { error("no ServiceManager") }).read())
    }

    @Test
    fun vibratorInfoIsReadyOnceEveryVibratorIsLoaded() {
        assertTrue(VibratorReader.infoReady(intArrayOf(1)) { true })
        assertTrue(VibratorReader.infoReady(intArrayOf(1, 2)) { true })
        // A NullVibrator (info not loaded yet) for any id means "retry later".
        assertFalse(VibratorReader.infoReady(intArrayOf(1, 2)) { it == 1 })
        assertFalse(VibratorReader.infoReady(null) { true })
        // No vibrator at all is a known answer.
        assertTrue(VibratorReader.infoReady(intArrayOf()) { false })
    }

    @Test
    fun theServiceCheckIsFalseOffTheDevice() {
        // android.jar's stubs have no ServiceManager: the check degrades to "not registered", it never throws.
        assertFalse(vibratorManagerRegistered())
    }

    @Test
    fun describesCapabilitiesOnOneLine() {
        assertEquals(
            "Vibrator: builder, envelopes 16 points 20..1000ms, max 16000ms, amplitude control yes, " +
                "primitives click=0ms tick=0ms low_tick=0ms",
            AlertHooks.describe(HapticCapabilities.GUARANTEED_ENVELOPES, read = true),
        )
        assertEquals(
            "Vibrator unreadable, assuming: no builder, no envelopes, amplitude control no, primitives none",
            AlertHooks.describe(HapticCapabilities(), read = false),
        )
        assertEquals(
            "Vibrator: no builder, no envelopes, amplitude control yes, primitives tick=12ms",
            AlertHooks.describe(
                HapticCapabilities(amplitudeControl = true, primitiveDurationsMs = mapOf(HapticPreset.TICK to 12)),
                read = true,
            ),
        )
    }
}
