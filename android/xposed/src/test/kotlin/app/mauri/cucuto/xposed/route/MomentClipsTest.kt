package app.mauri.cucuto.xposed.route

import app.mauri.cucuto.core.routing.Clip
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.LocalDays
import app.mauri.cucuto.core.routing.Moment
import app.mauri.cucuto.core.routing.Route
import app.mauri.cucuto.core.routing.RoutingTable
import app.mauri.cucuto.core.routing.TimeCondition
import app.mauri.cucuto.core.routing.Voice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import java.util.Random

/** Rare, first of the day and burst clips (ADR 0059): special clips play instead of a draw from the shuffle bag. */
class MomentClipsTest {
    private val ana = "111111111111111@lid"
    private val bo = "222222222222222@lid"
    private val minute = 60_000L
    private val hour = 60 * minute

    /** 2026-10-24 10:00 in UTC+2, with days starting at 04:00 local (shift = +2 h - 4 h). */
    private val start = 1_792_828_800_000L
    private val days = LocalDays(shiftAtStart = -2 * 3600)

    private fun uri(name: String) = "content://app.mauri.cucuto.clips/app.mauri.demo.critters/bird/$name.ogg"

    /** Mornings from 05:00 to 12:00 local for three days around [start]. */
    private val mornings =
        TimeCondition(
            start - 10 * hour,
            start + 62 * hour,
            false,
            longArrayOf(-5, 2, 19, 26, 43, 50).map { start + it * hour }.toLongArray(),
        )

    private val bird =
        Voice(
            packId = "app.mauri.demo.critters",
            characterId = "bird",
            clips =
                listOf(
                    Clip(uri("chirp")),
                    Clip(uri("trill")),
                    Clip(uri("whistle")),
                    Clip(uri("dawn"), moment = Moment(firstOfDay = true), condition = 0),
                    Clip(uri("squawk"), moment = Moment(burst = 3)),
                    Clip(uri("cuckoo"), moment = Moment(oneIn = 25)),
                ),
            haptic = Haptic.Keep,
        )

    private fun table(voice: Voice = bird) =
        RoutingTable(
            generation = 1,
            enabled = true,
            voices = listOf(voice),
            routes =
                listOf(
                    Route("com.whatsapp", ana, voice = 0, seed = 1, flags = 0, contactId = 10),
                    Route("com.whatsapp", bo, voice = 0, seed = 2, flags = 0, contactId = 11),
                ),
            conditions = listOf(mornings),
            days = days,
        )

    private var clock = start
    private var bags = ShuffleBags(randomFor = { Random(it.toLong()) })
    private var router = AlertRouter(bags, now = { clock })

    private fun alert(
        key: String = ana,
        table: RoutingTable = table(),
    ): String =
        router
            .decide(NotificationSnapshot("com.whatsapp", shortcutId = key), table)!!
            .clipUri!!
            .substringAfterLast('/')
            .removeSuffix(".ogg")

    private val ordinary = setOf("chirp", "trill", "whistle")

    @Test
    fun theFirstMessageOfTheDayGreets() {
        assertEquals("dawn", alert())
        // Bo has his own rhythm.
        assertEquals("dawn", alert(bo))
        clock += 2 * minute
        assertTrue(alert() in ordinary + "cuckoo")
        clock += 5 * hour
        assertTrue("still the same day", alert() != "dawn")
        // 03:30 the next night still belongs to today; 08:00 is a new day, and a morning.
        clock = start + 17 * hour + 30 * minute
        assertTrue(alert() != "dawn")
        clock = start + 22 * hour
        assertEquals("dawn", alert())
        assertTrue(alert() != "dawn")
    }

    @Test
    fun aFirstMessageOutsideItsTimeConditionDrawsFromTheBag() {
        // 04:30: the first message of the day, but dawn is for mornings from 05:00.
        clock = start - 5 * hour - 30 * minute
        assertTrue(alert() in ordinary + "cuckoo")
        // The day has had its first message, so the morning does not greet.
        clock = start - 2 * hour
        assertTrue(alert() != "dawn")
    }

    @Test
    fun theThirdMessageInARowIsImpatient() {
        clock = start + 13 * hour // no greeting at night
        val first = alert()
        assertTrue(first in ordinary + "cuckoo")
        clock += 30_000
        assertTrue(alert() != "squawk")
        clock += 59_000
        assertEquals("the third message within a minute of the one before", "squawk", alert())
        clock += 10_000
        assertTrue("once per burst", alert() != "squawk")
        clock += 10_000
        assertTrue(alert() != "squawk")
        // A pause ends the burst; three more in a row squawk again.
        clock += 61_000
        assertTrue(alert() != "squawk")
        clock += 1_000
        assertTrue(alert() != "squawk")
        clock += 1_000
        assertEquals("squawk", alert())
    }

    @Test
    fun rareClipsAreRareAndNeverRepeat() {
        clock = start + 13 * hour
        var rare = 0
        var last = ""
        val counts = HashMap<String, Int>()
        repeat(20_000) {
            // One message every two minutes for four weeks: never a burst, and dawn once on some days.
            clock += 2 * minute
            val clip = alert()
            counts.merge(clip, 1, Int::plus)
            if (clip == "cuckoo") {
                rare++
                assertNotEquals("never twice in a row", "cuckoo", last)
            }
            if (clip != "cuckoo" && clip != "dawn") assertNotEquals("the bag never repeats", last, clip)
            last = clip
        }
        // About 1 in 25 (800 of 20,000), with a wide margin.
        assertTrue("$rare cuckoos: $counts", rare in 600..1000)
        assertEquals(null, counts["squawk"])
        assertTrue(counts.keys.containsAll(ordinary))
    }

    @Test
    fun aRebootForgetsTheRhythm() {
        assertEquals("dawn", alert())
        assertTrue(alert() != "dawn")
        // system_server restarts: the module no longer knows today's first message.
        bags = ShuffleBags(randomFor = { Random(it.toLong()) })
        router = AlertRouter(bags, now = { clock })
        assertEquals("dawn", alert())
    }

    @Test
    fun aVoiceOfOnlySpecialClipsStillSpeaks() {
        val specials = bird.copy(clips = bird.clips.filter { it.moment.isSpecial })
        val table = table(specials)
        clock = start + 13 * hour
        val heard =
            (1..30)
                .map {
                    clock += 5 * minute
                    alert(table = table)
                }.toSet()
        assertTrue(heard.isNotEmpty())
        assertTrue(heard.all { it in setOf("dawn", "squawk", "cuckoo") })
    }

    @Test
    fun localDaysDecideWhenADayStarts() {
        val anytime =
            bird.copy(
                clips = bird.clips.map { if (it.moment.firstOfDay) it.copy(condition = Clip.ALWAYS) else it },
            )
        val local = table(anytime)
        val utc = local.copy(days = null)
        assertEquals("dawn", alert(ana, local))
        assertEquals("dawn", alert(bo, utc))
        // 02:30 local on 25 October is 00:30 UTC: a new day in UTC, still the 24th until 04:00 locally.
        clock = start + 16 * hour + 30 * minute
        assertTrue(alert(ana, local) != "dawn")
        assertEquals("dawn", alert(bo, utc))
        clock = start + 18 * hour
        assertEquals("04:00 local", "dawn", alert(ana, local))
    }

    @Test
    fun specialClipsDoNotAllocateInSteadyState() {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val table = table()
        val snapshot = NotificationSnapshot("com.whatsapp", shortcutId = ana)
        repeat(20_000) {
            clock += 20_000
            router.decide(snapshot, table)
        }
        val before = threads.getThreadAllocatedBytes(Thread.currentThread().threadId())
        val runs = 10_000
        repeat(runs) {
            clock += 20_000
            router.decide(snapshot, table)
        }
        val perDecision = (threads.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before) / runs
        // Only the decision itself, as for every other voice (DecisionPerformanceTest).
        assertTrue("$perDecision bytes per decision", perDecision <= 160)
    }
}
