package app.mauri.cucuto.xposed.route

import app.mauri.cucuto.core.routing.Clip
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.Route
import app.mauri.cucuto.core.routing.RoutingTable
import app.mauri.cucuto.core.routing.TimeCondition
import app.mauri.cucuto.core.routing.Voice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Day and night clips (ADR 0037): the router draws only from clips whose condition holds at the current time. */
class ConditionalClipsTest {
    private val ana = "111111111111111@lid"
    private val hour = 3_600_000L
    private val from = 1_761_000_000_000L

    /** Day from 07:00 to 19:00 (hours after [from]) for two days; night is the opposite. */
    private val day =
        TimeCondition(from, from + 48 * hour, false, longArrayOf(7, 19, 31, 43).map { from + it * hour }.toLongArray())
    private val night =
        TimeCondition(from, from + 48 * hour, true, longArrayOf(7, 19, 31, 43).map { from + it * hour }.toLongArray())

    private fun uri(name: String) = "content://app.mauri.cucuto.clips/app.mauri.demo.critters/owl/$name.ogg"

    private val owl =
        Voice(
            packId = "app.mauri.demo.critters",
            characterId = "owl",
            clips =
                listOf(
                    Clip(uri("chirp"), condition = 0),
                    Clip(uri("tweet"), condition = 0),
                    Clip(uri("hoot"), condition = 1),
                    Clip(uri("hoo"), condition = 1),
                    Clip(uri("flap")),
                ),
            haptic = Haptic.Keep,
        )

    private val table =
        RoutingTable(
            generation = 1,
            enabled = true,
            voices = listOf(owl),
            routes = listOf(Route("com.whatsapp", ana, voice = 0, seed = 1, flags = 0, contactId = 10)),
            conditions = listOf(day, night),
        )

    private var clock = from

    private val router = AlertRouter(ShuffleBags(randomFor = { Random(it.toLong()) }), now = { clock })

    private fun draw(times: Int): Set<String> =
        (1..times).mapTo(
            HashSet(),
        ) { router.decide(NotificationSnapshot("com.whatsapp", shortcutId = ana), table)!!.clipUri!! }

    @Test
    fun daytimePlaysDayClipsAndUnconditionalOnes() {
        clock = from + 12 * hour
        assertEquals(setOf(uri("chirp"), uri("tweet"), uri("flap")), draw(60))
    }

    @Test
    fun nightPlaysNightClipsAndUnconditionalOnes() {
        clock = from + 2 * hour
        assertEquals(setOf(uri("hoot"), uri("hoo"), uri("flap")), draw(60))
        clock = from + 20 * hour
        assertEquals(setOf(uri("hoot"), uri("hoo"), uri("flap")), draw(60))
    }

    @Test
    fun theVoiceSwitchesAtTheBoundaryWithoutRepeatingTheLastClip() {
        clock = from + 7 * hour - 1
        var previous = draw(1).single()
        clock = from + 7 * hour
        repeat(30) {
            val clip = router.decide(NotificationSnapshot("com.whatsapp", shortcutId = ana), table)!!.clipUri!!
            assertTrue(clip in setOf(uri("chirp"), uri("tweet"), uri("flap")))
            assertTrue(clip != previous)
            previous = clip
        }
    }

    @Test
    fun outsideThePublishedSpanEveryClipPlays() {
        clock = from + 72 * hour
        assertEquals(owl.clips.map { it.uri }.toSet(), draw(200))
    }

    @Test
    fun whenNoClipMatchesTheWholePoolPlays() {
        val dayOnly = owl.copy(clips = owl.clips.take(2))
        val onlyDay = table.copy(voices = listOf(dayOnly))
        clock = from + 2 * hour
        val clips =
            (1..40).mapTo(
                HashSet(),
            ) { router.decide(NotificationSnapshot("com.whatsapp", shortcutId = ana), onlyDay)!!.clipUri!! }
        assertEquals(setOf(uri("chirp"), uri("tweet")), clips)
    }

    @Test
    fun theClockIsNotReadForUnconditionalVoices() {
        val plain = owl.copy(clips = owl.clips.map { it.copy(condition = Clip.ALWAYS) })
        val noConditions = table.copy(voices = listOf(plain), conditions = emptyList())
        val strict = AlertRouter(now = { error("clock read for a voice without conditions") })
        repeat(5) { strict.decide(NotificationSnapshot("com.whatsapp", shortcutId = ana), noConditions) }
    }
}
