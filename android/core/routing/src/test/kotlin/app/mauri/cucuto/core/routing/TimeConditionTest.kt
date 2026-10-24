package app.mauri.cucuto.core.routing

import app.mauri.cucuto.core.routing.RoutingFixtures.WHATSAPP
import app.mauri.cucuto.core.routing.RoutingFixtures.sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TimeConditionTest {
    private val day = 86_400_000L
    private val from = 1_761_000_000_000L

    /** Day from 07:00 to 19:00 (relative to [from]) for three days. */
    private val daytime =
        TimeCondition(
            fromMs = from,
            untilMs = from + 3 * day,
            activeAtFrom = false,
            toggles = longArrayOf(7, 19, 31, 43, 55, 67).map { from + it * 3_600_000L }.toLongArray(),
        )

    @Test
    fun stateFlipsAtEveryToggle() {
        assertEquals(TimeCondition.INACTIVE, daytime.state(from))
        assertEquals(TimeCondition.INACTIVE, daytime.state(from + 7 * 3_600_000L - 1))
        assertEquals(TimeCondition.ACTIVE, daytime.state(from + 7 * 3_600_000L))
        assertEquals(TimeCondition.ACTIVE, daytime.state(from + 19 * 3_600_000L - 1))
        assertEquals(TimeCondition.INACTIVE, daytime.state(from + 19 * 3_600_000L))
        assertEquals(TimeCondition.ACTIVE, daytime.state(from + day + 12 * 3_600_000L))
        assertEquals(TimeCondition.INACTIVE, daytime.state(from + 3 * day - 1))
    }

    @Test
    fun unknownOutsideTheSpan() {
        assertEquals(TimeCondition.UNKNOWN, daytime.state(from - 1))
        assertEquals(TimeCondition.UNKNOWN, daytime.state(from + 3 * day))
        assertEquals(TimeCondition.UNKNOWN, daytime.state(Long.MAX_VALUE))
        assertEquals(TimeCondition.UNKNOWN, daytime.state(Long.MIN_VALUE))
    }

    @Test
    fun matchesALinearScanEverywhere() {
        val toggles = daytime.toggles()
        var t = from
        while (t < from + 3 * day) {
            val flips = toggles.count { it <= t }
            val expected = if (flips % 2 == 1) TimeCondition.ACTIVE else TimeCondition.INACTIVE
            assertEquals("at $t", expected, daytime.state(t))
            t += 59_999
        }
        assertEquals(from + 7 * 3_600_000L, daytime.nextToggleAfter(from))
        assertNull(daytime.nextToggleAfter(from + 67 * 3_600_000L))
    }

    @Test
    fun rejectsBadToggles() {
        for (toggles in listOf(longArrayOf(from), longArrayOf(from + 10, from + 10), longArrayOf(from + 3 * day))) {
            try {
                TimeCondition(from, from + 3 * day, false, toggles)
                fail("Accepted ${toggles.toList()}")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun rejectsASpanWiderThanALong() {
        // until - from wraps to a negative length; accepted, state() would index before its buckets.
        try {
            TimeCondition(Long.MIN_VALUE + 1, Long.MAX_VALUE, false, LongArray(0))
            fail("Accepted a span that overflows")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun conditionalTable(): RoutingTable {
        val night =
            TimeCondition(from, from + 3 * day, true, longArrayOf(from + 7 * 3_600_000L, from + 19 * 3_600_000L))
        val voice = sample.voices[0]
        val clips = voice.clips.mapIndexed { i, clip -> if (i == 0) clip.copy(condition = 1) else clip }
        return sample.copy(
            voices = listOf(voice.copy(clips = clips)) + sample.voices.drop(1),
            conditions = listOf(daytime, night),
        )
    }

    @Test
    fun codecRoundTripsConditions() {
        val table = conditionalTable()
        val encoded = RoutingCodec.encode(table)
        assertEquals(table, RoutingCodec.decode(encoded))
        val lines = encoded.split('\n')
        // The header gains a ninth count; W lines carry whole-second deltas.
        assertTrue(lines[0], lines[0].endsWith("\t2"))
        assertEquals(
            "W\t$from\t${from + 3 * day}\t0\t25200,43200,43200,43200,43200,43200",
            lines.first {
                it.startsWith("W\t")
            },
        )
        assertTrue(lines.any { it.startsWith("V\t") && it.contains("\tw1,") })
        assertTrue(RoutingCodec.decode(encoded).voices[0].isConditional)
        assertFalse(RoutingCodec.decode(encoded).voices[1].isConditional)
    }

    @Test
    fun tablesWithoutConditionsKeepTheirEncoding() {
        val encoded = RoutingCodec.encode(sample)
        assertFalse(encoded.contains("\nW\t"))
        assertFalse(encoded.contains("\tw"))
        assertEquals(8, encoded.substringBefore('\n').split('\t').size)
    }

    @Test
    fun aReaderFromBeforeConditionsPlaysEveryClip() {
        val table = conditionalTable()
        // What a reader that predates conditions sees: no W lines, no w field, an eight-field header.
        val old =
            RoutingCodec
                .encode(table)
                .lines()
                .filterNot { it.startsWith("W\t") }
                .joinToString("\n") { line ->
                    when {
                        line.startsWith("cucuto-routing") -> line.substringBeforeLast('\t')
                        line.startsWith("V\t") -> line.split('\t').filterNot { it.startsWith("w") }.joinToString("\t")
                        else -> line
                    }
                }
        assertEquals(sample, RoutingCodec.decode(old))
    }

    @Test
    fun playableFollowsTheCondition() {
        val table = conditionalTable()
        val match = table.resolve(WHATSAPP, sample.routes[0].key)!!
        val nightClip = match.voice.clips[0]
        val plainClip = match.voice.clips[1]
        assertTrue(table.isPlayable(nightClip, from + 3_600_000L))
        assertFalse(table.isPlayable(nightClip, from + 12 * 3_600_000L))
        // Outside the published span the condition is ignored.
        assertTrue(table.isPlayable(nightClip, from + 4 * day))
        assertTrue(table.isPlayable(plainClip, from + 12 * 3_600_000L))
    }

    @Test
    fun rejectsBadConditionReferences() {
        val encoded = RoutingCodec.encode(conditionalTable())
        for (broken in listOf(encoded.replace("\tw1,", "\tw7,"), encoded.replace("\tw1,", "\tw1,-,-,-,-,-,"))) {
            assertNull(RoutingCodec.decodeOrNull(broken))
        }
        // A W line whose toggles leave the span.
        assertNull(RoutingCodec.decodeOrNull(encoded.replace("\t25200,", "\t9999999,")))
        try {
            sample.copy(voices = conditionalTable().voices)
            fail("A clip pointing at a missing condition was accepted")
        } catch (_: IllegalArgumentException) {
        }
    }
}
