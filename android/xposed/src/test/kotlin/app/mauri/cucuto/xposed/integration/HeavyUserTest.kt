package app.mauri.cucuto.xposed.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mauri.cucuto.core.data.db.GroupPolicy
import app.mauri.cucuto.core.data.db.InstalledPackEntity
import app.mauri.cucuto.core.data.db.PackSource
import app.mauri.cucuto.core.data.routing.HapticOverride
import app.mauri.cucuto.core.haptics.auto.AutoHapticFile
import app.mauri.cucuto.core.haptics.auto.AutoHapticSet
import app.mauri.cucuto.core.routing.ControlPoint
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import app.mauri.cucuto.core.routing.RoutingCodec
import app.mauri.cucuto.core.routing.RoutingContract
import app.mauri.cucuto.xposed.events.Outcome
import app.mauri.cucuto.xposed.integration.Notifications.BUSINESS
import app.mauri.cucuto.xposed.integration.Notifications.WHATSAPP
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/**
 * The size limit with everything turned on, through the real data layer and publisher (ADR 0047). The heavy user of
 * `RoutingScaleTest` (500 contacts with a phone JID and a LID each, 50 groups, 120 characters of 12 clips, 60 contacts
 * with their own clip toggles or haptic) plus every feature added since: an analysed envelope on every clip with
 * `auto` haptics (#149), day, night, hour, weekday, season and date clips (#200, #120), rare, first of the day and
 * burst clips (#120), per-sender groups (#159), 100 WhatsApp Business chats, haptic overrides from the editor (#172)
 * and the clip mirror's long `file://` URIs (#151).
 */
@RunWith(AndroidJUnit4::class)
class HeavyUserTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var pipeline: Pipeline

    @After
    fun tearDown() = pipeline.close()

    /**
     * One of four packs of 30 characters with 12 clips each. Three clips per character have a time `when`; every
     * third character also has a rare clip, every fifth a first-of-the-day clip and every sixth a burst clip.
     */
    private fun heavyPack(
        root: File,
        index: Int,
    ): InstalledPackEntity {
        val packId = "app.mauri.test.heavy-$index"
        val dir = File(root, "$packId/2.0.0").apply { mkdirs() }
        val conditions =
            listOf(
                """{"time":"night"}""",
                """{"time":"day"}""",
                """{"time":{"from":"07:00","to":"09:30"}}""",
                """{"time":{"from":"22:00","to":"06:00"},"weekday":["sat","sun"]}""",
                """{"season":"winter"}""",
                """{"weekday":["mon","tue","wed","thu","fri"]}""",
                """{"date":[{"from":"12-24","to":"01-06"},"10-31"]}""",
            )
        val envelopes = HashMap<String, Haptic.Custom?>()
        val characters =
            (0 until CHARACTERS_PER_PACK).joinToString(",") { c ->
                val clips =
                    (0 until CLIPS).joinToString(",") { k ->
                        val file = clipFile("character-$c", k)
                        File(dir, file).apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(32) { (c + k).toByte() })
                        // A distinct 16-point envelope per clip: the worst case for the H dictionary.
                        envelopes[file] =
                            Haptic.Custom(
                                listOf(
                                    HapticEvent.Envelope(
                                        atMs = (index * CHARACTERS_PER_PACK + c) * CLIPS + k,
                                        points =
                                            (1..16).map { p ->
                                                ControlPoint(
                                                    if (p ==
                                                        16
                                                    ) {
                                                        0
                                                    } else {
                                                        (index * 97 + c * 31 + k * 7 + p * 13) % 1000
                                                    },
                                                    (p * 61) % 1000,
                                                    20 * p,
                                                )
                                            },
                                    ),
                                ),
                            )
                        val condition =
                            when {
                                k < 3 -> ""","when":${conditions[(c + k) % conditions.size]}"""
                                k == 3 && c % 3 == 0 -> ""","oneIn":${20 + c}"""
                                k == 4 && c % 5 == 0 -> ""","when":{"firstOfDay":true}"""
                                k == 5 && c % 6 == 0 -> ""","when":{"burst":3}"""
                                else -> ""
                            }
                        """{"file":"$file","weight":${1 + k % 3}$condition}"""
                    }
                """{"id":"character-$c","name":"Character $c","sprite":"characters/character-$c/sprite.svg",""" +
                    """"clips":[$clips],"haptic":{"mode":"auto"}}"""
            }
        File(dir, "pack.json").writeText(
            """{"formatVersion":1,"id":"$packId","name":"Heavy $index","version":"2.0.0","author":"Cucuto tests",""" +
                """"license":"CC0-1.0","characters":[$characters]}""",
        )
        AutoHapticFile.write(dir, AutoHapticSet(analyzerVersion = 1, 16, 20, envelopes))
        return InstalledPackEntity(
            packId,
            "2.0.0",
            packId,
            dir.absolutePath,
            "0".repeat(64),
            1,
            PackSource.LOCAL,
            installedAt = 1,
        )
    }

    @Test
    fun aHeavyUserWithEverythingOnFitsWithItsEnvelopes() =
        runBlocking<Unit> {
            pipeline = Pipeline(folder.newFolder())
            val p = pipeline
            val packs = (0 until PACKS).map { heavyPack(p.packRoot, it) }
            packs.forEach { p.packs.recordInstalled(it) }
            p.setPlace()

            fun character(i: Int) = packs[i % PACKS].packId to "character-${(i / PACKS) % CHARACTERS_PER_PACK}"
            for (i in 0 until CONTACTS) {
                val lid = "${100_000_000_000_000L + i}@lid"
                val contact = p.contacts.findOrCreate(WHATSAPP, lid, "Contact $i", isGroup = false)
                p.contacts.addKey(contact, WHATSAPP, "${34_600_000_000L + i}@s.whatsapp.net")
                // 180 distinct voices: every character once (the first 120 contacts), 30 with clip toggles and 30
                // with their own haptic.
                val (packId, characterId) = character(i)
                p.assignments.assign(contact, packId, characterId)
                when {
                    i in 120 until 150 -> {
                        p.assignments.setClipEnabled(contact, clipFile(characterId, i % CLIPS), false)
                    }

                    i in 150 until 180 -> {
                        p.assignments.setHapticOverride(
                            contact,
                            HapticOverride.Custom(editorPattern(i)).toJson(),
                        )
                    }
                }
                if (i < BUSINESS_CHATS) {
                    val business = p.contacts.findOrCreate(BUSINESS, lid, "Contact $i", isGroup = false)
                    p.assignments.assign(business, packId, characterId)
                }
            }
            for (g in 0 until GROUPS) {
                val group =
                    p.contacts.findOrCreate(
                        WHATSAPP,
                        "${120_363_000_000_000_000L + g}@g.us",
                        "Group $g",
                        isGroup = true,
                    )
                if (g < PER_SENDER_GROUPS) p.contacts.setGroupPolicy(group, GroupPolicy.PER_SENDER)
                val (packId, characterId) = character(g * 7)
                p.assignments.assign(group, packId, characterId)
            }

            p.start()
            val routes = CONTACTS * 2 + BUSINESS_CHATS + GROUPS
            val table = p.awaitModule { it.routes.size == routes }
            val published = p.awaitPublished()
            val encoded = p.service.writes.last()
            // Sized as on the phone, whose mirror path is not this machine's temporary folder.
            val onPhone =
                encoded.replace(
                    "file://${p.mirrorRoot.absolutePath}/",
                    "file://${RoutingGoldenTest.MIRROR_ROOT}/",
                )
            val envelopes =
                table.voices
                    .flatMap { it.clips }
                    .mapNotNull { it.haptic }
                    .distinct()
                    .size
            println(
                "Heavy user: ${onPhone.length} chars on the phone (limit ${RoutingContract.MAX_ENCODED_CHARS}), " +
                    "${table.routes.size} routes, ${table.voices.size} voices, $envelopes clip envelopes, " +
                    "${table.conditions.size} conditions " +
                    "(${encoded.lines().filter { it.startsWith("W\t") }.sumOf { it.length }} chars of W lines), " +
                    "special clips: ${encoded.lines().filter { it.startsWith("D\t") }.sumOf { it.length }} chars of " +
                    "D line, ${Regex("\tm[^\t\n]*").findAll(encoded).sumOf { it.value.length }} chars of m fields",
            )

            assertEquals(180, table.voices.size)
            assertEquals(PACKS * CHARACTERS_PER_PACK * CLIPS, envelopes)
            assertEquals(7, table.conditions.size)
            assertTrue("local days for first-of-the-day clips", table.days != null)
            assertTrue(table.voices.count { it.hasMoments } in 40..120)
            assertTrue(table.voices.flatMap { it.clips }.all { it.uri.startsWith("file://") })
            assertFalse("published with every clip envelope", published.clipHapticsLeftOut)
            assertTrue("${onPhone.length} chars", onPhone.length < RoutingContract.MAX_ENCODED_CHARS)
            assertEquals(table, RoutingCodec.decode(encoded))
            // The released module reads it too.
            assertEquals(table.withoutConditions(), ReleasedRoutingReader.decode(encoded))

            // And the module routes from it.
            val seventh = "${100_000_000_000_000L + 7}@lid"
            val (alert, event) = p.alert(Notifications.oneToOne(seventh, "Contact 7"))
            assertEquals(Outcome.SWAPPED.name, event!!.outcome)
            assertTrue(alert.sound.toString().startsWith("file://"))
        }

    private fun clipFile(
        characterId: String,
        clip: Int,
    ) = "characters/$characterId/clips/clip-number-$clip.ogg"

    private fun editorPattern(i: Int) =
        Haptic.Custom(
            listOf(
                HapticEvent.Preset(0, HapticPreset.CLICK, 400 + i),
                HapticEvent.Preset(120 + i, HapticPreset.TICK, 500),
                HapticEvent.Preset(300 + i, HapticPreset.LOW_TICK, 700),
            ),
        )

    private companion object {
        const val PACKS = 4
        const val CHARACTERS_PER_PACK = 30
        const val CLIPS = 12
        const val CONTACTS = 500
        const val BUSINESS_CHATS = 100
        const val GROUPS = 50
        const val PER_SENDER_GROUPS = 15
    }
}
