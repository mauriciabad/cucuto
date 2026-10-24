package app.mauri.cucuto.ui.ringtone.rules

import app.mauri.cucuto.core.data.db.RingtoneRuleEntry
import app.mauri.cucuto.core.ringtone.variants.RingtoneVariant
import app.mauri.cucuto.packspec.When
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules screen's model (ADR 0074): reading a pack's rules, and editing "Your own rules". */
class RuleModelTest {
    private val live = RingtoneVariant("live.ogg", "Live", condition = When(time = JsonPrimitive("day")))
    private val radio = RingtoneVariant("radio.ogg", "Radio", condition = When(time = JsonPrimitive("night")))
    private val winter =
        RingtoneVariant(
            "winter.ogg",
            "Winter",
            condition = When(season = JsonArray(listOf(JsonPrimitive("winter"), JsonPrimitive("autumn")))),
        )
    private val plain = RingtoneVariant("plain.ogg", "Plain")

    @Test
    fun dayAndNightCoverEveryMomentSoThereIsNoFallbackLine() {
        val view = RulesView.of(listOf(live, radio))
        assertNull(view.fallback)
        assertFalse(view.unconditional)
        assertEquals(listOf(null, null), view.lines.map { it.shadowedBy })
    }

    @Test
    fun conditionsThatCanAllFailSayWhichVersionRingsThen() {
        assertEquals(winter, RulesView.of(listOf(winter, live)).fallback)
        assertNull(RulesView.of(listOf(winter, plain)).fallback)
    }

    @Test
    fun aRepeatedConditionNeverRingsAndNamesTheOneBeforeIt() {
        val again = RingtoneVariant("again.ogg", condition = When(time = JsonPrimitive("day")))
        val view = RulesView.of(listOf(live, radio, again))
        assertEquals(live, view.lines[2].shadowedBy)
        val plainTwice = RulesView.of(listOf(plain, RingtoneVariant("other.ogg")))
        assertTrue(plainTwice.unconditional)
        assertEquals(plain, plainTwice.lines[1].shadowedBy)
    }

    @Test
    fun whenChoicesReadSimpleConditionsAndLeaveTheRestToThePack() {
        assertEquals(WhenChoice.ANY, WhenChoice.of(null))
        assertEquals(WhenChoice.DAY, WhenChoice.of(live.condition))
        assertEquals(WhenChoice.NIGHT, WhenChoice.of(radio.condition))
        assertEquals(WhenChoice.SUMMER, WhenChoice.of(When(season = JsonArray(listOf(JsonPrimitive("summer"))))))
        assertEquals(WhenChoice.PACK, WhenChoice.of(winter.condition))
        assertEquals(WhenChoice.PACK, WhenChoice.of(When(time = JsonPrimitive("day"), weekday = JsonPrimitive("sat"))))
    }

    @Test
    fun aCopyOfThePackSavesAsThePacksRules() {
        val entries = listOf(live, radio, winter)
        val draft = RulesDraft.fromPack(entries)
        assertTrue(RulesDraft.isPack(entries, draft.rules))
        // The pack's own list of one season reads as that season and still compares equal.
        val summer = RingtoneVariant("summer.ogg", condition = When(season = JsonArray(listOf(JsonPrimitive("summer")))))
        assertTrue(RulesDraft.isPack(listOf(summer), RulesDraft.fromPack(listOf(summer)).rules))
        // The winter row offers the pack's own condition; the others do not need it.
        assertTrue(WhenChoice.PACK in draft.rows[2].choices)
        assertFalse(WhenChoice.PACK in draft.rows[0].choices)
    }

    @Test
    fun editingSwapsConditionsTurnsVersionsOffAndMovesTheFallbackFirst() {
        val entries = listOf(live, radio, winter)
        val draft =
            RulesDraft
                .fromPack(entries)
                .choose(0, WhenChoice.NIGHT)
                .choose(1, WhenChoice.DAY)
                .toggle(2, false)
                .fallbackTo(1)
        assertEquals(
            listOf(
                RingtoneRuleEntry("radio.ogg", buildJsonObject { put("time", "day") }),
                RingtoneRuleEntry("live.ogg", buildJsonObject { put("time", "night") }),
            ),
            draft.rules,
        )
        assertFalse(RulesDraft.isPack(entries, draft.rules))
    }

    @Test
    fun oneVersionAlwaysStaysOnAndTheFallbackFollowsTheVersionsThatAre() {
        val draft = RulesDraft.fromPack(listOf(live, radio)).toggle(0, false)
        assertEquals(1, draft.fallback)
        assertEquals(draft, draft.toggle(1, false))
        assertEquals(draft, draft.fallbackTo(0))
        assertEquals(listOf("radio.ogg"), draft.rules.map { it.variant })
    }

    @Test
    fun savedRulesComeBackInTheirOrderWithThePacksOtherVersionsOff() {
        val entries = listOf(live, radio, winter)
        val rules =
            listOf(
                RingtoneRuleEntry("radio.ogg", buildJsonObject { put("time", "day") }),
                RingtoneRuleEntry("gone.ogg"),
                RingtoneRuleEntry("live.ogg"),
            )
        val draft = RulesDraft.fromSaved(entries, rules)
        assertEquals(listOf("radio.ogg", "live.ogg", "winter.ogg"), draft.rows.map { it.file })
        assertEquals(listOf(true, true, false), draft.rows.map { it.on })
        assertEquals(listOf(WhenChoice.DAY, WhenChoice.ANY, WhenChoice.PACK), draft.rows.map { it.choice })
        assertEquals(listOf("radio.ogg", "live.ogg"), draft.rules.map { it.variant })
        // Nothing left of them: a copy of the pack's.
        assertEquals(RulesDraft.fromPack(entries), RulesDraft.fromSaved(entries, listOf(RingtoneRuleEntry("gone.ogg"))))
    }
}
