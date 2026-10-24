package app.mauri.cucuto.ui.ringtone.rules

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mauri.cucuto.core.ringtone.variants.RingtoneVariant
import app.mauri.cucuto.packspec.When
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale

/** The rules in plain words (ADR 0074), in English and in Spanish. */
@RunWith(AndroidJUnit4::class)
class RuleTextTest {
    private val live = RingtoneVariant("k/live.ogg", "Live", condition = When(time = JsonPrimitive("day")))
    private val radio = RingtoneVariant("k/radio.ogg", "Radio", condition = When(time = JsonPrimitive("night")))
    private val party =
        RingtoneVariant(
            "k/party.ogg",
            condition =
                When(
                    time =
                        buildJsonObject {
                            put("from", "22:00")
                            put("to", "06:00")
                        },
                    season = JsonArray(listOf(JsonPrimitive("summer"), JsonPrimitive("autumn"))),
                    weekday = JsonArray(listOf(JsonPrimitive("sat"), JsonPrimitive("sun"))),
                ),
        )
    private val plain = RingtoneVariant("k/plain.ogg", "Plain")

    private fun text(language: String): RuleText {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return RuleText(context.resources, Locale.forLanguageTag(language))
    }

    @Test
    fun english() {
        val text = text("en")
        val view = RulesView.of(listOf(live, radio))
        assertEquals("Live by day and Radio at night", text.summary(view))
        assertEquals("Rings by day", text.line(view.lines[0], view))
        assertEquals(
            "from 22:00 to 06:00, in summer and autumn, every Saturday and Sunday",
            text.condition(party.condition),
        )
        val withRest = RulesView.of(listOf(party, plain))
        assertEquals("Rings the rest of the time", text.line(withRest.lines[1], withRest))
        assertEquals("party by day", text.summary(RulesView.of(listOf(party.copy(condition = live.condition)))))
        assertEquals("Always Plain", text.summary(RulesView.of(listOf(plain, live.copy(condition = null)))))
        val fallback = RulesView.of(listOf(party, live))
        assertEquals("When none of these apply, party rings.", text.fallback(fallback))
        val repeated = RulesView.of(listOf(live, live.copy(file = "k/again.ogg", label = "Again")))
        assertEquals("Never rings: Live comes first at the same times", text.line(repeated.lines[1], repeated))
        val holidays =
            When(
                date =
                    JsonArray(
                        listOf(
                            JsonPrimitive("12-25"),
                            buildJsonObject {
                                put("from", "12-31")
                                put("to", "01-06")
                            },
                        ),
                    ),
            )
        assertEquals("on 25 December and from 31 December to 6 January", text.condition(holidays))
        assertEquals(
            "As the pack says: in summer and autumn",
            text.choice(WhenChoice.PACK, When(season = party.condition!!.season)),
        )
    }

    @Test
    @Config(qualifiers = "es")
    fun spanish() {
        val text = text("es")
        val view = RulesView.of(listOf(live, radio))
        assertEquals("Live de día y Radio de noche", text.summary(view))
        assertEquals("Suena de día", text.line(view.lines[0], view))
        assertEquals("de 22:00 a 06:00, en verano y otoño, cada sábado y domingo", text.condition(party.condition))
        assertEquals("Siempre Plain", text.summary(RulesView.of(listOf(plain))))
        assertEquals("En invierno", text.choice(WhenChoice.WINTER, null))
        assertEquals("el 25 de diciembre", text.condition(When(date = JsonPrimitive("12-25"))))
    }
}
