package app.mauri.cucuto.spike.waring.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwapDecisionTest {
    private val ring = "content://media/external/audio/media/42"
    private val swap = SpikeConfig(SpikeConfig.Mode.SWAP, testName = "Cucuto Test", ringUri = ring)
    private val defaultTone = "content://settings/system/ringtone"
    private val call = IncomingCall(name = "Cucuto Test", ageMillis = 300, isGroupCall = false)

    private fun keep(decision: Decision) = assertTrue("expected Keep, got $decision", decision is Decision.Keep)

    @Test
    fun swapsOnlyTheTestContactsRingingOneToOneCall() {
        assertEquals(Decision.Swap(ring), SwapDecision.decide(swap, defaultTone, call))
    }

    @Test
    fun observeAndOffNeverSwap() {
        keep(SwapDecision.decide(swap.copy(mode = SpikeConfig.Mode.OBSERVE), defaultTone, call))
        keep(SwapDecision.decide(swap.copy(mode = SpikeConfig.Mode.OFF), defaultTone, call))
    }

    @Test
    fun everyDoubtKeepsWhatsAppsRingtone() {
        keep(SwapDecision.decide(swap, null, call))
        keep(SwapDecision.decide(swap, "android.resource://com.whatsapp/raw/incoming_group_call_ringtone", call))
        keep(SwapDecision.decide(swap, defaultTone, null))
        keep(SwapDecision.decide(swap, defaultTone, call.copy(isGroupCall = true)))
        keep(SwapDecision.decide(swap, defaultTone, call.copy(ageMillis = SwapDecision.MAX_CALL_AGE_MILLIS + 1)))
        keep(SwapDecision.decide(swap, defaultTone, call.copy(ageMillis = -5)))
        keep(SwapDecision.decide(swap, defaultTone, call.copy(name = "Someone Else")))
        keep(SwapDecision.decide(swap, defaultTone, call.copy(name = null)))
        keep(SwapDecision.decide(swap.copy(testName = null), defaultTone, call))
        keep(SwapDecision.decide(swap.copy(ringUri = null), defaultTone, call))
        keep(SwapDecision.decide(swap.copy(ringUri = "file:///sdcard/Ringtones/x.ogg"), defaultTone, call))
        keep(SwapDecision.decide(swap, ring, call))
    }

    @Test
    fun modeParsingDefaultsToOff() {
        assertEquals(SpikeConfig.Mode.OFF, SpikeConfig.parseMode(null))
        assertEquals(SpikeConfig.Mode.OFF, SpikeConfig.parseMode("yes"))
        assertEquals(SpikeConfig.Mode.SWAP, SpikeConfig.parseMode(" Swap "))
        assertEquals(SpikeConfig.Mode.OBSERVE, SpikeConfig.parseMode("observe"))
    }

    @Test
    fun redactionKeepsOnlyTheUriShape() {
        assertEquals(defaultTone, Redact.uri(defaultTone))
        assertEquals("content://media/…", Redact.uri(ring))
        assertEquals("android.resource://com.whatsapp/…", Redact.uri("android.resource://com.whatsapp/raw/x"))
        assertEquals("null", Redact.uri(null))
    }
}
