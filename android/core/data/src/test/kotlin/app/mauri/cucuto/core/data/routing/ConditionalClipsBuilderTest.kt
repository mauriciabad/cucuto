package app.mauri.cucuto.core.data.routing

import app.mauri.cucuto.core.conditions.ClipWhen
import app.mauri.cucuto.core.conditions.ConditionCompiler
import app.mauri.cucuto.core.conditions.Place
import app.mauri.cucuto.core.conditions.TimeOfDay
import app.mauri.cucuto.core.data.db.GroupPolicy
import app.mauri.cucuto.core.data.db.RoutingRow
import app.mauri.cucuto.core.routing.Clip
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.RoutingCodec
import app.mauri.cucuto.core.routing.TimeCondition
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Day and night clips in the routing table (ADR 0037). */
class ConditionalClipsBuilderTest {
    private val owl =
        CatalogCharacter(
            clips =
                listOf(
                    CatalogClip("characters/owl/clips/chirp.ogg", 1000, condition = ClipWhen(TimeOfDay.Day)),
                    CatalogClip("characters/owl/clips/hoot.ogg", 1000, condition = ClipWhen(TimeOfDay.Night)),
                    CatalogClip("characters/owl/clips/flap.ogg", 1000),
                ),
            haptic = Haptic.Keep,
        )
    private val catalog = FakeCatalog(mapOf(CRITTERS to mapOf("owl" to owl, "owl2" to owl)))
    private val builder = RoutingTableBuilder(catalog, ProviderClips)
    private val packs = mapOf(CRITTERS to installedPack())
    private val madrid = ZoneId.of("Europe/Madrid")
    private val compiler = ConditionCompiler(Place(41.39, 2.17), madrid, LocalDate.of(2026, 10, 24))

    private fun row(
        key: String,
        contactId: Long,
        characterId: String,
    ) = RoutingRow(
        packageName = "com.whatsapp",
        keyValue = key,
        contactId = contactId,
        isGroup = false,
        groupPolicy = GroupPolicy.GROUP,
        shuffleSeed = 1,
        packId = CRITTERS,
        characterId = characterId,
        disabledClips = emptyList(),
        hapticOverride = null,
    )

    private fun ms(text: String) = ZonedDateTime.parse(text).toInstant().toEpochMilli()

    @Test
    fun clipsPointAtSharedCompiledConditions() =
        runTest {
            val rows = listOf(row("a@lid", 1, "owl"), row("b@lid", 2, "owl2"))
            val table = builder.build(rows, packs, enabled = true, compiler = compiler)
            // Two characters use the same two conditions: compiled once each.
            assertEquals(2, table.conditions.size)
            for (voice in table.voices) {
                assertEquals(listOf(0, 1, Clip.ALWAYS), voice.clips.map { it.condition })
                assertTrue(voice.isConditional)
            }
            val day = table.conditions[0]
            assertEquals(TimeCondition.ACTIVE, day.state(ms("2026-10-24T12:00:00+02:00")))
            assertEquals(TimeCondition.INACTIVE, day.state(ms("2026-10-24T23:00:00+02:00")))
            assertEquals(TimeCondition.ACTIVE, table.conditions[1].state(ms("2026-10-24T23:00:00+02:00")))
            // The encoded table round-trips and stays far below the publishing limit.
            val encoded = RoutingCodec.encode(table)
            assertEquals(table, RoutingCodec.decode(encoded))
            assertTrue("${encoded.length} chars", encoded.length < 16_000)
        }

    @Test
    fun withoutACompilerEveryClipAlwaysPlays() =
        runTest {
            val table = builder.build(listOf(row("a@lid", 1, "owl")), packs, enabled = true)
            assertTrue(table.conditions.isEmpty())
            assertFalse(table.voices.single().isConditional)
        }

    @Test
    fun theCatalogReadsClipConditionsFromPackJson() {
        val manifest =
            Json.parseToJsonElement(
                """
                {"characters": [{"id": "owl", "clips": [
                  {"file": "a.ogg", "label": "Hoot", "when": {"time": "night"}},
                  {"file": "b.ogg", "when": {"time": {"from": "22:00", "to": "06:00"}, "weekday": ["sat", "sun"]}},
                  {"file": "c.ogg", "when": {"season": "winter"}},
                  {"file": "d.ogg", "label": "  "},
                  {"file": "e.ogg", "when": {"time": "teatime"}}
                ]}]}
                """.trimIndent(),
            ) as JsonObject
        val clips = PackManifestCatalog.parseCharacters(manifest).getValue("owl").clips
        assertEquals(ClipWhen.of(time = "night"), clips[0].condition)
        assertEquals(ClipWhen.of(from = "22:00", to = "06:00", weekdays = listOf("sat", "sun")), clips[1].condition)
        assertEquals(ClipWhen.of(seasons = listOf("winter")), clips[2].condition)
        assertEquals(null, clips[3].condition)
        // Not understood (a validated pack never has this): the clip simply always plays.
        assertEquals(null, clips[4].condition)
        // Labels for the clip list (#185); a blank one counts as none.
        assertEquals(listOf("Hoot", null, null, null, null), clips.map { it.label })
    }
}
