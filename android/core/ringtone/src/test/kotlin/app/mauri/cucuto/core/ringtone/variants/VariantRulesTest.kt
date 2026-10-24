package app.mauri.cucuto.core.ringtone.variants

import app.mauri.cucuto.core.data.db.RingtoneMode
import app.mauri.cucuto.packspec.When
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import kotlin.random.Random

class VariantRulesTest {
    private val day =
        RingtoneVariant("day.ogg", "Day", tags = listOf("day"), condition = When(time = JsonPrimitive("day")))
    private val night =
        RingtoneVariant("night.ogg", "Night", tags = listOf("night"), condition = When(time = JsonPrimitive("night")))
    private val plain = RingtoneVariant("plain.ogg", "Plain")

    // 2026-10-24 is a Saturday, in autumn.
    private fun at(
        hour: Int,
        minute: Int = 0,
        date: String = "2026-10-24",
    ) = RuleContext(LocalDateTime.parse("${date}T%02d:%02d".format(hour, minute)))

    private fun pick(
        variants: List<RingtoneVariant>,
        mode: RingtoneMode,
        context: RuleContext = at(12),
        fixed: String? = null,
        current: String? = null,
        reroll: Boolean = false,
        random: Random = Random(1),
    ) = VariantRules.pick(variants, mode, fixed, context, current, reroll, random)

    @Test
    fun followPackUsesWhenConditionsWithDayFrom7To19() {
        val variants = listOf(plain, day, night)
        assertEquals(day, pick(variants, RingtoneMode.PACK, at(7)))
        assertEquals(day, pick(variants, RingtoneMode.PACK, at(18, 59)))
        assertEquals(night, pick(variants, RingtoneMode.PACK, at(19)))
        assertEquals(night, pick(variants, RingtoneMode.PACK, at(6, 59)))
    }

    @Test
    fun followPackPrefersTheMostSpecificMatchThenManifestOrder() {
        val saturdayNight =
            RingtoneVariant(
                "party.ogg",
                condition = When(time = JsonPrimitive("night"), weekday = JsonPrimitive("sat")),
            )
        val variants = listOf(plain, night, saturdayNight)
        assertEquals(saturdayNight, pick(variants, RingtoneMode.PACK, at(22)))
        assertEquals(night, pick(variants, RingtoneMode.PACK, at(22, date = "2026-10-25")))
        assertEquals(plain, pick(variants, RingtoneMode.PACK, at(12)))
    }

    @Test
    fun followPackFallsBackToTheFirstEntry() {
        val winter = RingtoneVariant("winter.ogg", condition = When(season = JsonPrimitive("winter")))
        val summer =
            RingtoneVariant("summer.ogg", condition = When(season = JsonArray(listOf(JsonPrimitive("summer")))))
        assertEquals(winter, pick(listOf(winter, summer), RingtoneMode.PACK, at(12)))
    }

    @Test
    fun hourRangesIncludeFromExcludeToAndWrapMidnight() {
        val late = RingtoneVariant("late.ogg", condition = When(time = range("22:00", "06:00")))
        val variants = listOf(plain, late)
        assertEquals(late, pick(variants, RingtoneMode.PACK, at(22)))
        assertEquals(late, pick(variants, RingtoneMode.PACK, at(3)))
        assertEquals(plain, pick(variants, RingtoneMode.PACK, at(6)))
        assertEquals(plain, pick(variants, RingtoneMode.PACK, at(21, 59)))
    }

    @Test
    fun seasonsAreMeteorologicalAndFlipInTheSouth() {
        val autumn = RingtoneVariant("autumn.ogg", tags = listOf("autumn"))
        val spring = RingtoneVariant("spring.ogg", tags = listOf("spring"))
        val variants = listOf(plain, autumn, spring)
        assertEquals(autumn, pick(variants, RingtoneMode.SEASON, at(12)))
        assertEquals(spring, pick(variants, RingtoneMode.SEASON, at(12).copy(southernHemisphere = true)))
        assertEquals(plain, pick(variants, RingtoneMode.SEASON, at(12, date = "2026-01-10")))
        assertEquals("winter", at(0, date = "2026-02-28").season)
        assertEquals("spring", at(0, date = "2026-03-01").season)
    }

    @Test
    fun timeModeUsesDayAndNightTagsAndIgnoresWhen() {
        val dayTagged = RingtoneVariant("a.ogg", tags = listOf("Day"), condition = When(time = JsonPrimitive("night")))
        val nightTagged = RingtoneVariant("b.ogg", tags = listOf("night"))
        assertEquals(dayTagged, pick(listOf(nightTagged, dayTagged), RingtoneMode.TIME, at(12)))
        assertEquals(nightTagged, pick(listOf(nightTagged, dayTagged), RingtoneMode.TIME, at(23)))
    }

    @Test
    fun fixedUsesTheChosenVariantOrTheFirst() {
        assertEquals(night, pick(listOf(day, night), RingtoneMode.FIXED, fixed = "night.ogg"))
        assertEquals(day, pick(listOf(day, night), RingtoneMode.FIXED, fixed = "gone.ogg"))
        assertEquals(day, pick(listOf(day, night), RingtoneMode.FIXED))
    }

    @Test
    fun randomKeepsTheCurrentVariantUntilARerollThenNeverRepeats() {
        val variants = listOf(day, night, plain)
        assertEquals(night, pick(variants, RingtoneMode.RANDOM, current = "night.ogg"))
        repeat(200) { seed ->
            val next =
                pick(
                    variants,
                    RingtoneMode.RANDOM,
                    current = "night.ogg",
                    reroll = true,
                    random = Random(seed),
                )!!
            assertNotEquals("night.ogg", next.file)
        }
        assertEquals(day, pick(listOf(day), RingtoneMode.RANDOM, current = "day.ogg", reroll = true))
    }

    @Test
    fun randomFollowsWeights() {
        val heavy = RingtoneVariant("heavy.ogg", weight = 9.0)
        val light = RingtoneVariant("light.ogg", weight = 1.0)
        val random = Random(42)
        val heavyPicks = (1..10_000).count { VariantRules.weighted(listOf(heavy, light), null, random) == heavy }
        assert(heavyPicks in 8_700..9_300) { "heavy picked $heavyPicks times" }
    }

    @Test
    fun noVariantsPicksNothing() {
        assertNull(pick(emptyList(), RingtoneMode.PACK))
    }

    @Test
    fun nextBoundaryFindsTheNextEdge() {
        val variants = listOf(day, night)
        assertEquals(
            LocalDateTime.parse("2026-10-24T19:00"),
            VariantRules.nextBoundary(variants, RingtoneMode.PACK, at(12)),
        )
        assertEquals(
            LocalDateTime.parse("2026-10-25T07:00"),
            VariantRules.nextBoundary(variants, RingtoneMode.PACK, at(19)),
        )
        assertEquals(
            LocalDateTime.parse("2026-10-24T19:00"),
            VariantRules.nextBoundary(variants, RingtoneMode.TIME, at(8)),
        )
        assertEquals(
            LocalDateTime.parse("2026-12-01T00:00"),
            VariantRules.nextBoundary(variants, RingtoneMode.SEASON, at(8)),
        )
        assertEquals(
            LocalDateTime.parse("2026-03-01T00:00"),
            VariantRules.nextBoundary(variants, RingtoneMode.SEASON, at(8, date = "2026-02-28")),
        )
        assertNull(VariantRules.nextBoundary(variants, RingtoneMode.FIXED, at(8)))
        assertNull(VariantRules.nextBoundary(variants, RingtoneMode.RANDOM, at(8)))
        assertNull(VariantRules.nextBoundary(listOf(plain), RingtoneMode.PACK, at(8)))
        val weekly = listOf(plain, RingtoneVariant("sun.ogg", condition = When(weekday = JsonPrimitive("sun"))))
        assertEquals(
            LocalDateTime.parse("2026-10-25T00:00"),
            VariantRules.nextBoundary(weekly, RingtoneMode.PACK, at(8)),
        )
        val ranged = listOf(plain, RingtoneVariant("r.ogg", condition = When(time = range("10:30", "11:00"))))
        assertEquals(
            LocalDateTime.parse("2026-10-24T10:30"),
            VariantRules.nextBoundary(ranged, RingtoneMode.PACK, at(8)),
        )
    }

    private fun range(
        from: String,
        to: String,
    ) = buildJsonObject {
        put("from", from)
        put("to", to)
    }
}
