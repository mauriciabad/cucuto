package app.mauri.cucuto.ui.ringtone

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Ringtone previews ring like the call until stopped, with pause and progress (owner update 2026-10-24, #242). */
@OptIn(ExperimentalCoroutinesApi::class)
class RingtonePlaybackTest {
    private val live = RingSound(File("live.ogg"))
    private val radio = RingSound(File("radio.ogg"))

    @Test
    fun itRingsUntilStoppedWithProgressRoundTheRingCycle() =
        runTest {
            val preview = FakePreview(cycle = 90_000)
            val playback = RingtonePlayback<String>(backgroundScope, preview) { testScheduler.currentTime }

            playback.play("kk/live", live)
            runCurrent()
            assertEquals(90_000L, playback.playing.value?.cycleMs)
            assertEquals(listOf(live), preview.rung)

            advanceTimeBy(45_000)
            assertEquals(0.5f, playback.playing.value!!.progressAt(testScheduler.currentTime), 0.01f)

            // No cut-off: long after a 30 s preview would have stopped, it still rings, round the next cycle.
            advanceTimeBy(10 * 90_000)
            runCurrent()
            assertEquals("kk/live", playback.playing.value?.key)
            assertEquals(45_000L, playback.playing.value!!.positionAt(testScheduler.currentTime))

            playback.stop()
            assertNull(playback.playing.value)
            assertTrue(preview.stops > 0)
        }

    @Test
    fun aTapPausesAndAnotherResumesWithoutLosingItsPlace() =
        runTest {
            val preview = FakePreview(cycle = 90_000)
            val playback = RingtonePlayback<String>(backgroundScope, preview) { testScheduler.currentTime }

            playback.toggle("a", live)
            runCurrent()
            advanceTimeBy(10_000)
            playback.toggle("a", live)
            assertTrue(playback.playing.value!!.paused)
            assertEquals(10_000L, playback.playing.value!!.positionAt(testScheduler.currentTime))

            advanceTimeBy(30_000)
            assertEquals(
                "paused: the progress waits",
                10_000L,
                playback.playing.value!!.positionAt(testScheduler.currentTime),
            )

            playback.toggle("a", live)
            assertFalse(playback.playing.value!!.paused)
            advanceTimeBy(5_000)
            assertEquals(15_000L, playback.playing.value!!.positionAt(testScheduler.currentTime))
            assertEquals(1 to 1, preview.pauses to preview.resumes)
            assertEquals("it did not start over", 1, preview.rung.size)
        }

    @Test
    fun anotherRingtoneTakesOver() =
        runTest {
            val preview = FakePreview(cycle = 90_000)
            val playback = RingtonePlayback<String>(backgroundScope, preview)

            playback.toggle("a", live)
            runCurrent()
            playback.toggle("b", radio)
            runCurrent()

            assertEquals("b", playback.playing.value?.key)
            assertTrue(playback.isActive("b"))
            assertFalse(playback.isActive("a"))
            assertEquals(listOf(live, radio), preview.rung)
        }

    @Test
    fun aFileThatCannotPlayLeavesNothingRinging() =
        runTest {
            val playback = RingtonePlayback<String>(backgroundScope, FakePreview(cycle = null))
            playback.play("broken", live)
            runCurrent()
            assertNull(playback.playing.value)
        }

    private class FakePreview(
        private val cycle: Long?,
    ) : RingtonePreview {
        val rung = mutableListOf<RingSound>()
        var stops = 0
        var pauses = 0
        var resumes = 0

        override suspend fun ring(sound: RingSound): Long? {
            rung += sound
            return cycle
        }

        override fun pause() {
            pauses++
        }

        override fun resume() {
            resumes++
        }

        override fun stop() {
            stops++
        }
    }
}
