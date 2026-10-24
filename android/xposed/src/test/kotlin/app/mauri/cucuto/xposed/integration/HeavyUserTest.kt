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
import app.mauri.cucuto.core.routing.RoutingFile
import app.mauri.cucuto.core.routing.RoutingTable
import app.mauri.cucuto.xposed.RoutingTableHolder
import app.mauri.cucuto.xposed.events.Outcome
import app.mauri.cucuto.xposed.integration.Notifications.BUSINESS
import app.mauri.cucuto.xposed.integration.Notifications.WHATSAPP
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 * `auto` haptics (#149), day, night, hour, weekday and season clips (#200), per-sender groups (#159), 100 WhatsApp
 * Business chats, haptic overrides from the editor (#172) and the clip mirror's long `file://` URIs (#151).
 */
@RunWith(AndroidJUnit4::class)
class HeavyUserTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var pipeline: Pipeline

    @After
    fun tearDown() = pipeline.close()

    /** One heavy pack: 30 characters with 12 clips each; three clips per character have a `when`. */
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
                        val condition = if (k < 3) ""","when":${conditions[(c + k) % conditions.size]}""" else ""
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

    /**
     * Sets up [scale] heavy users in one app (`scale` times the packs, contacts, Business chats and groups) and
     * returns how many routes the table must have.
     */
    private suspend fun Pipeline.heavyUser(scale: Int): Int {
        val packCount = PACKS * scale
        val packs = (0 until packCount).map { heavyPack(packRoot, it) }
        packs.forEach { this.packs.recordInstalled(it) }
        setPlace()

        fun character(i: Int) = packs[i % packCount].packId to "character-${(i / packCount) % CHARACTERS_PER_PACK}"
        for (i in 0 until CONTACTS * scale) {
            val lid = "${100_000_000_000_000L + i}@lid"
            val contact = contacts.findOrCreate(WHATSAPP, lid, "Contact $i", isGroup = false)
            contacts.addKey(contact, WHATSAPP, "${34_600_000_000L + i}@s.whatsapp.net")
            // 180 distinct voices per heavy user: every character once (the first 120 contacts), 30 with clip
            // toggles and 30 with their own haptic.
            val (packId, characterId) = character(i)
            assignments.assign(contact, packId, characterId)
            when {
                i in 120 * scale until 150 * scale -> {
                    assignments.setClipEnabled(contact, clipFile(characterId, i % CLIPS), false)
                }

                i in 150 * scale until 180 * scale -> {
                    assignments.setHapticOverride(
                        contact,
                        HapticOverride.Custom(editorPattern(i)).toJson(),
                    )
                }
            }
            if (i < BUSINESS_CHATS * scale) {
                val business = contacts.findOrCreate(BUSINESS, lid, "Contact $i", isGroup = false)
                assignments.assign(business, packId, characterId)
            }
        }
        for (g in 0 until GROUPS * scale) {
            val group =
                contacts.findOrCreate(
                    WHATSAPP,
                    "${120_363_000_000_000_000L + g}@g.us",
                    "Group $g",
                    isGroup = true,
                )
            if (g < PER_SENDER_GROUPS * scale) contacts.setGroupPolicy(group, GroupPolicy.PER_SENDER)
            val (packId, characterId) = character(g * 7)
            assignments.assign(group, packId, characterId)
        }
        return (CONTACTS * 2 + BUSINESS_CHATS + GROUPS) * scale
    }

    /** [encoded] as on the phone, whose mirror path is not this machine's temporary folder. */
    private fun Pipeline.onPhone(encoded: String) =
        encoded.replace("file://${mirrorRoot.absolutePath}/", "file://${RoutingGoldenTest.MIRROR_ROOT}/")

    private fun RoutingTable.envelopes() =
        voices
            .flatMap { it.clips }
            .mapNotNull { it.haptic }
            .distinct()
            .size

    @Test
    fun aHeavyUserWithEverythingOnFitsWithItsEnvelopes() =
        runBlocking<Unit> {
            pipeline = Pipeline(folder.newFolder())
            val p = pipeline
            val routes = p.heavyUser(scale = 1)

            p.start()
            val table = p.awaitModule { it.routes.size == routes }
            val published = p.awaitPublished()
            val encoded = p.service.writes.last()
            val onPhone = p.onPhone(encoded)
            val bytes = RoutingFile.utf8Length(onPhone)
            println(
                "Heavy user: $bytes bytes on the phone (%.0f %% of the table limit, %.0f %% of Remote Preferences'), "
                    .format(
                        bytes * 100.0 / RoutingContract.MAX_TABLE_BYTES,
                        onPhone.length * 100.0 / RoutingContract.MAX_PREFERENCES_CHARS,
                    ) +
                    "${table.routes.size} routes, ${table.voices.size} voices, ${table.envelopes()} clip envelopes, " +
                    "${table.conditions.size} conditions",
            )
            println("Bytes by line type: ${bytesByLineType(onPhone)}")

            assertEquals(180, table.voices.size)
            assertEquals(PACKS * CHARACTERS_PER_PACK * CLIPS, table.envelopes())
            assertEquals(6, table.conditions.size)
            assertTrue(table.voices.flatMap { it.clips }.all { it.uri.startsWith("file://") })
            assertFalse("published with every clip envelope", published.clipHapticsLeftOut)
            // Headroom (#223, ADR 0054): at least three times this user before the publisher drops anything.
            assertTrue("$bytes bytes", bytes * 3 <= RoutingContract.MAX_TABLE_BYTES)
            assertEquals(table, RoutingCodec.decode(encoded))
            // The released module reads it too.
            assertEquals(table.withoutConditions(), ReleasedRoutingReader.decode(encoded))

            // And the module routes from it.
            val seventh = "${100_000_000_000_000L + 7}@lid"
            val (alert, event) = p.alert(Notifications.oneToOne(seventh, "Contact 7"))
            assertEquals(Outcome.SWAPPED.name, event!!.outcome)
            assertTrue(alert.sound.toString().startsWith("file://"))
        }

    /**
     * Two heavy users (#223, ADR 0054): about twice the Binder limit of Remote Preferences, even without clip
     * envelopes. The full table goes to the remote file and the app's provider only. Whichever way the module gets its
     * tables (the nudge, at boot, or only from the provider) it ends with the full table, and the released module
     * reads it from the file.
     */
    @Test
    fun twoHeavyUsersGoThroughTheFileWithEveryEnvelope() =
        runBlocking<Unit> {
            pipeline = Pipeline(folder.newFolder())
            val p = pipeline
            val routes = p.heavyUser(scale = 2)

            p.start()
            val published = p.awaitPublished()
            val table = p.awaitModule { it.generation == published.generation }
            val encoded = p.service.writes.last()
            println("Two heavy users: ${encoded.length} chars, ${table.voices.size} voices")

            assertTrue(published.tooLargeForPreferences)
            assertFalse(published.clipHapticsLeftOut)
            assertTrue(encoded.length > RoutingContract.MAX_PREFERENCES_CHARS)
            assertEquals(routes, table.routes.size)
            assertEquals(2 * PACKS * CHARACTERS_PER_PACK * CLIPS, table.envelopes())
            assertEquals(RoutingCodec.decode(encoded), table)
            // Even without clip envelopes this is over the Binder limit: Remote Preferences only get the generation
            // (a table that fits them without envelopes goes there as a copy one generation older: see
            // RoutingPublisherTest).
            assertNull(p.service.preferences.value)
            assertEquals(published.generation, p.service.preferences.generation)
            // The released module (0.1.2) re-reads the file when nudged and gets every envelope.
            assertEquals(table.withoutConditions(), ReleasedRoutingReader.decode(encoded))

            // A module booting now finds nothing in Remote Preferences, then the full table at the boot check.
            val booted = holder(p)
            assertTrue(booted.ensureAttached())
            assertTrue(booted.checkAtBoot())
            assertEquals(table, booted.peek())

            // A module whose remote file is torn pulls the full table from the app's provider when nudged.
            val pulled = holder(p, remoteFile = { p.service.remoteFile!!.copyOf(100) })
            pulled.ensureAttached()
            assertTrue(pulled.refresh(published.generation))
            assertEquals(table, pulled.peek())

            // And the module routes from it.
            val first = "${100_000_000_000_000L}@lid"
            val (alert, event) = p.alert(Notifications.oneToOne(first, "Contact 0"))
            assertEquals(Outcome.SWAPPED.name, event!!.outcome)
            assertTrue(alert.sound.toString().startsWith("file://"))
        }

    private fun holder(
        p: Pipeline,
        remoteFile: () -> ByteArray? = { p.service.remoteFile },
    ) = RoutingTableHolder(
        preferences = { p.service.preferences },
        log = { _, _ -> },
        remoteFile = remoteFile,
        providerTable = { p.store.file.readBytes() },
    )

    /** Characters per line type (`V`, `H`, `R`, …) of an encoded table, header included under `c`. */
    private fun bytesByLineType(encoded: String): Map<Char, Int> =
        encoded
            .lineSequence()
            .filter { it.isNotEmpty() }
            .groupingBy { it[0] }
            .fold(0) { total, line -> total + line.length + 1 }
            .toSortedMap()

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
