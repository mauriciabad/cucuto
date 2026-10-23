package app.mauri.cucuto.xposed.hook

import android.content.Context
import app.mauri.cucuto.core.haptics.HapticCapabilities
import app.mauri.cucuto.core.routing.HapticPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VibratorReaderTest {
    @Suppress("unused")
    private open class BaseHelper {
        private val mContext: Context? = null
    }

    @Suppress("unused")
    private class DerivedHelper : BaseHelper() {
        private val mOtherContext: Context? = null
    }

    @Suppress("unused")
    private class RenamedHelper {
        private val name: String = "helper"
        private val context: Context? = null
    }

    @Suppress("unused")
    private class NoContextHelper {
        private val name: String = "helper"
    }

    @Test
    fun findsTheHelpersContextFieldUpTheHierarchy() {
        assertTrue(VibratorReader(BaseHelper::class.java).usable)
        assertTrue(VibratorReader(DerivedHelper::class.java).usable)
        assertTrue(VibratorReader(RenamedHelper::class.java).usable)
    }

    @Test
    fun aHelperWithoutContextIsNotUsable() {
        assertFalse(VibratorReader(NoContextHelper::class.java).usable)
        assertNull(VibratorReader(NoContextHelper::class.java).capabilities(NoContextHelper()))
    }

    @Test
    fun readingNeverThrows() {
        val reader = VibratorReader(BaseHelper::class.java)
        // A null Context, a missing helper and an object of the wrong class all read as "unknown".
        assertNull(reader.capabilities(BaseHelper()))
        assertNull(reader.capabilities(null))
        assertNull(reader.capabilities("not a helper"))
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
