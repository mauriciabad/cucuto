package app.mauri.cucuto.core.routing

import app.mauri.cucuto.core.routing.RoutingFixtures.sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Special clips and local days in the routing table (ADR 0059). */
class MomentsTest {
    private val hour = 3_600_000L

    /** Madrid in 2026 with days starting at 04:00: UTC+1 until 29 March 01:00 UTC, then UTC+2 until 25 October. */
    private val madrid =
        LocalDays(
            shiftAtStart = 3600 - 4 * 3600,
            transitionsMs = longArrayOf(1_774_746_000_000L, 1_792_890_000_000L),
            shiftsSeconds = intArrayOf(7200 - 4 * 3600, 3600 - 4 * 3600),
        )

    private fun special(): RoutingTable {
        val voice = sample.voices[0]
        val clips =
            voice.clips +
                listOf(
                    Clip("content://app.mauri.cucuto.clips/p/c/dawn.ogg", moment = Moment(firstOfDay = true)),
                    Clip("content://app.mauri.cucuto.clips/p/c/hey.ogg", moment = Moment(burst = 3)),
                    Clip("content://app.mauri.cucuto.clips/p/c/gold.ogg", moment = Moment(oneIn = 25)),
                    Clip("content://app.mauri.cucuto.clips/p/c/x.ogg", moment = Moment(true, 4, 9)),
                )
        return sample.copy(voices = listOf(voice.copy(clips = clips)) + sample.voices.drop(1), days = madrid)
    }

    @Test
    fun codecRoundTripsMomentsAndDays() {
        val table = special()
        val encoded = RoutingCodec.encode(table)
        assertEquals(table, RoutingCodec.decode(encoded))
        val lines = encoded.split('\n')
        assertEquals("D\t-10800\t1774746000:-7200,1792890000:-10800", lines.first { it.startsWith("D\t") })
        val clipCount = sample.voices[0].clips.size
        val voice = lines.first { it.startsWith("V\t") }
        assertTrue(
            voice,
            voice.endsWith("\tm$clipCount:f,${clipCount + 1}:b3,${clipCount + 2}:r25,${clipCount + 3}:fb4r9"),
        )
        // The header does not count the D line, so its fields stay as they were.
        assertEquals(8, encoded.substringBefore('\n').split('\t').size)
        val decoded = RoutingCodec.decode(encoded)
        assertTrue(decoded.voices[0].hasMoments)
        assertFalse(decoded.voices[1].hasMoments)
    }

    @Test
    fun tablesWithoutMomentsKeepTheirEncoding() {
        val encoded = RoutingCodec.encode(sample)
        assertFalse(encoded.contains("\nD\t"))
        assertFalse(encoded.contains("\tm"))
    }

    @Test
    fun aReaderFromBeforeMomentsDrawsEveryClip() {
        val table = special()
        val old =
            RoutingCodec
                .encode(table)
                .lines()
                .filterNot { it.startsWith("D\t") }
                .joinToString("\n") { line ->
                    if (line.startsWith(
                            "V\t",
                        )
                    ) {
                        line.split('\t').filterNot { it.startsWith("m") }.joinToString("\t")
                    } else {
                        line
                    }
                }
        val plain =
            table.copy(
                days = null,
                voices =
                    table.voices.map { v ->
                        v.copy(clips = v.clips.map { it.copy(moment = Moment.NONE) })
                    },
            )
        assertEquals(plain, RoutingCodec.decode(old))
    }

    @Test
    fun rejectsBadMoments() {
        val encoded = RoutingCodec.encode(special())
        val n = sample.voices[0].clips.size
        for (broken in listOf(
            encoded.replace("\tm$n:f,", "\tm$n:q,"),
            encoded.replace("\tm$n:f,", "\tm$n:,"),
            encoded.replace("\tm$n:f,", "\tm$n:b1,"),
            encoded.replace("\tm$n:f,", "\tm$n:r1,"),
            encoded.replace("\tm$n:f,", "\tm99:f,"),
            encoded.replace("\tm$n:f,${n + 1}:", "\tm${n + 1}:f,$n:"),
            encoded.replace("1774746000:-7200", "1774746000"),
            encoded.replace("\t-10800\t", "\t-99999999\t"),
        )) {
            assertNull(broken, RoutingCodec.decodeOrNull(broken))
        }
    }

    @Test
    fun localDaysStartAtFourInTheMorningAndFollowDaylightSaving() {
        // 2026-01-10 03:59 and 04:00 in Madrid (UTC+1).
        val jan10At4 = 1_768_014_000_000L
        assertEquals(madrid.dayOf(jan10At4 - 60_000) + 1, madrid.dayOf(jan10At4))
        assertEquals(madrid.dayOf(jan10At4), madrid.dayOf(jan10At4 + 23 * hour + 59 * 60_000))
        assertEquals(madrid.dayOf(jan10At4) + 1, madrid.dayOf(jan10At4 + 24 * hour))
        // Summer time (UTC+2): 2026-07-01 04:00 local is 02:00 UTC.
        val jul1At4 = 1_782_871_200_000L
        assertEquals(madrid.dayOf(jul1At4 - 1) + 1, madrid.dayOf(jul1At4))
        assertEquals(-7200, madrid.shiftAt(jul1At4))
        // The 23-hour day of the spring change (28 March, 04:00 to 29 March, 04:00) is still one day.
        val mar28At4 = 1_774_666_800_000L
        assertEquals(madrid.dayOf(mar28At4 - 1) + 1, madrid.dayOf(mar28At4))
        assertEquals(madrid.dayOf(mar28At4), madrid.dayOf(mar28At4 + 22 * hour + 59 * 60_000))
        assertEquals(madrid.dayOf(mar28At4) + 1, madrid.dayOf(mar28At4 + 23 * hour))
        // Without days, the module counts in UTC.
        assertEquals(Math.floorDiv(jan10At4, LocalDays.DAY_MS), sample.dayOf(jan10At4))
    }

    @Test
    fun momentsAreBounded() {
        for (bad in listOf(
            { Moment(burst = 1) },
            { Moment(burst = 51) },
            { Moment(oneIn = 1) },
            { Moment(oneIn = 10_001) },
        )) {
            try {
                bad()
                throw AssertionError("accepted")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertFalse(Moment.NONE.isSpecial)
        assertTrue(Moment(oneIn = 2).isSpecial)
        assertFalse(Moment(oneIn = 2).isMoment)
        assertTrue(Moment(burst = 2).isMoment)
    }
}
