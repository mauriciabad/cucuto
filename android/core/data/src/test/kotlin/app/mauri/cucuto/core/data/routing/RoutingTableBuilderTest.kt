package app.mauri.cucuto.core.data.routing

import app.mauri.cucuto.core.data.db.GroupPolicy
import app.mauri.cucuto.core.data.db.RoutingRow
import app.mauri.cucuto.core.routing.Clip
import app.mauri.cucuto.core.routing.ControlPoint
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.Route
import app.mauri.cucuto.core.routing.RoutingCodec
import app.mauri.cucuto.core.routing.Voice
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingTableBuilderTest {
    private val catalog = FakeCatalog()
    private val builder = RoutingTableBuilder(catalog, ProviderClips)
    private val packs = mapOf(CRITTERS to installedPack())

    private fun row(
        key: String,
        contactId: Long,
        characterId: String? = "frog",
        isGroup: Boolean = false,
        policy: GroupPolicy = GroupPolicy.GROUP,
        disabled: List<String> = emptyList(),
        haptic: String? = null,
        packId: String? = CRITTERS,
    ) = RoutingRow(
        packageName = "com.whatsapp",
        keyValue = key,
        contactId = contactId,
        isGroup = isGroup,
        groupPolicy = policy,
        shuffleSeed = contactId.toInt() * 10,
        packId = packId.takeIf { characterId != null },
        characterId = characterId,
        disabledClips = disabled,
        hapticOverride = haptic,
    )

    @Test
    fun buildsVoicesWithClipUrisWeightsAndHaptic() =
        runTest {
            val table = builder.build(listOf(row("a@s.whatsapp.net", 1)), packs, enabled = true)
            val expected =
                Voice(
                    packId = CRITTERS,
                    characterId = "frog",
                    clips =
                        listOf(
                            Clip(
                                "content://app.mauri.cucuto.clips/$CRITTERS/frog/characters/frog/clips/ribbit.ogg",
                                2000,
                            ),
                            Clip(
                                "content://app.mauri.cucuto.clips/$CRITTERS/frog/characters/frog/clips/croak.ogg",
                                1000,
                            ),
                        ),
                    haptic = frogHaptic,
                )
            assertEquals(listOf(expected), table.voices)
            assertEquals(listOf(Route("com.whatsapp", "a@s.whatsapp.net", 0, 10, 0, 1)), table.routes)
            assertEquals(0L, table.generation)
            assertTrue(table.enabled)
        }

    @Test
    fun mirroredClipsGetFileUrisAndTheRestKeepTheProvider() =
        runTest {
            val mirror =
                FakeMirror(
                    mirrored = setOf("characters/frog/clips/ribbit.ogg"),
                    selfTest = "file:///mirror/selftest/chirp.wav",
                )
            val table = RoutingTableBuilder(catalog, mirror).build(listOf(row("a@s.whatsapp.net", 1)), packs, true)
            assertEquals(
                listOf(
                    "file:///mirror/$CRITTERS/1.0.0/characters/frog/clips/ribbit.ogg",
                    "content://app.mauri.cucuto.clips/$CRITTERS/frog/characters/frog/clips/croak.ogg",
                ),
                table.voices
                    .single()
                    .clips
                    .map { it.uri },
            )
            assertEquals("file:///mirror/selftest/chirp.wav", table.selfTestClip)
            assertEquals(table, RoutingCodec.decode(RoutingCodec.encode(table)))
        }

    @Test
    fun clipEnvelopesOnlyGoWhereAutoHapticsUseThem() =
        runTest {
            val envelope = Haptic.Custom(listOf(HapticEvent.Envelope(0, listOf(ControlPoint(1000, 500, 40)))))
            catalog.characters =
                catalog.characters.mapValues { (_, characters) ->
                    characters.mapValues { (_, c) -> c.copy(clips = c.clips.map { it.copy(autoHaptic = envelope) }) }
                }
            val table =
                builder.build(
                    listOf(
                        row("frog@s.whatsapp.net", 1),
                        row("frog-auto@s.whatsapp.net", 2, haptic = """{"mode":"auto"}"""),
                        row("owl@s.whatsapp.net", 3, characterId = "owl"),
                        row("owl-off@s.whatsapp.net", 4, characterId = "owl", haptic = """{"mode":"none"}"""),
                    ),
                    packs,
                    enabled = true,
                )

            fun clipHaptics(key: String) =
                table.voices[table.route("com.whatsapp", key)!!.voice].clips.map { it.haptic }
            // The frog's pack haptic is custom and the module would ignore clip envelopes: left out.
            assertEquals(listOf(null, null), clipHaptics("frog@s.whatsapp.net"))
            // A per-contact auto override on the same character needs them.
            assertEquals(listOf(envelope, envelope), clipHaptics("frog-auto@s.whatsapp.net"))
            assertEquals(listOf(envelope), clipHaptics("owl@s.whatsapp.net"))
            assertEquals(listOf(null), clipHaptics("owl-off@s.whatsapp.net"))
        }

    @Test
    fun keysOfOneContactShareOneVoiceAndEqualVoicesAreDeduplicated() =
        runTest {
            val table =
                builder.build(
                    listOf(
                        row("a@s.whatsapp.net", 1),
                        row("1@lid", 1),
                        row("b@s.whatsapp.net", 2),
                        row("c@s.whatsapp.net", 3, characterId = "owl"),
                    ),
                    packs,
                    enabled = true,
                )
            assertEquals(2, table.voices.size)
            assertEquals(listOf(0, 0, 0, 1), table.routes.map { it.voice })
            // One catalog lookup per contact, not per key.
            assertEquals(3, catalog.lookups)
        }

    @Test
    fun clipTogglesAndOverridesMakeAVoiceOfTheirOwn() =
        runTest {
            val table =
                builder.build(
                    listOf(
                        row("a@s.whatsapp.net", 1),
                        row("b@s.whatsapp.net", 2, disabled = listOf("characters/frog/clips/croak.ogg")),
                        row("c@s.whatsapp.net", 3, haptic = """{"mode":"none"}"""),
                    ),
                    packs,
                    enabled = true,
                )
            assertEquals(3, table.voices.size)
            assertEquals(1, table.voices[1].clips.size)
            assertEquals(Haptic.Off, table.voices[2].haptic)
        }

    @Test
    fun contactsWithoutAPlayableVoiceKeepWhatsAppsTone() =
        runTest {
            val table =
                builder.build(
                    listOf(
                        // Pack not installed (for example before a restore finishes).
                        row("a@s.whatsapp.net", 1, packId = "io.github.missing.pack"),
                        // Character removed by a pack update.
                        row("b@s.whatsapp.net", 2, characterId = "gone"),
                        // Every clip switched off.
                        row(
                            "c@s.whatsapp.net",
                            3,
                            characterId = "owl",
                            disabled = listOf("characters/owl/clips/hoot.ogg"),
                        ),
                    ),
                    packs,
                    enabled = true,
                )
            assertTrue(table.routes.isEmpty())
            assertTrue(table.voices.isEmpty())
        }

    @Test
    fun groupPolicies() =
        runTest {
            val table =
                builder.build(
                    listOf(
                        row("g1@g.us", 10, isGroup = true),
                        row("g2@g.us", 11, characterId = null, isGroup = true, policy = GroupPolicy.PER_SENDER),
                        row("g3@g.us", 12, characterId = "owl", isGroup = true, policy = GroupPolicy.PER_SENDER),
                    ),
                    packs,
                    enabled = false,
                )
            val byKey = table.routes.associateBy { it.key }
            assertEquals(Route.FLAG_GROUP, byKey.getValue("g1@g.us").flags)
            assertEquals(Route.NO_VOICE, byKey.getValue("g2@g.us").voice)
            assertTrue(byKey.getValue("g2@g.us").isPerSender)
            assertEquals("owl", table.voices[byKey.getValue("g3@g.us").voice].characterId)
            assertFalse(table.enabled)
            // Whatever the builder makes, the codec can carry.
            assertEquals(table, RoutingCodec.decode(RoutingCodec.encode(table)))
        }
}
