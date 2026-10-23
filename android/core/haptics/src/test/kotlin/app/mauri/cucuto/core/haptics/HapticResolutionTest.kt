package app.mauri.cucuto.core.haptics

import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import org.junit.Assert.assertEquals
import org.junit.Test

class HapticResolutionTest {
    private val clip = Haptic.Custom(listOf(HapticEvent.Preset(0, HapticPreset.TICK)))
    private val voice = Haptic.Custom(listOf(HapticEvent.Preset(0, HapticPreset.CLICK)))

    @Test
    fun autoFollowsTheClip() {
        assertEquals(clip, Haptic.Auto.forClip(clip))
        assertEquals(Haptic.Keep, Haptic.Auto.forClip(null))
    }

    @Test
    fun otherModesIgnoreTheClip() {
        assertEquals(Haptic.Keep, Haptic.Keep.forClip(clip))
        assertEquals(Haptic.Off, Haptic.Off.forClip(clip))
        assertEquals(voice, voice.forClip(clip))
    }
}
