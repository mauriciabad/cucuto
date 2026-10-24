package app.mauri.cucuto.xposed.integration

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mauri.cucuto.core.data.db.GroupPolicy
import app.mauri.cucuto.core.data.routing.HapticOverride
import app.mauri.cucuto.core.haptics.HapticEffects
import app.mauri.cucuto.core.routing.ControlPoint
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticGrid
import app.mauri.cucuto.core.routing.HapticPreset
import app.mauri.cucuto.core.routing.RoutingCodec
import app.mauri.cucuto.core.routing.RoutingContract
import app.mauri.cucuto.core.routing.RoutingTable
import app.mauri.cucuto.xposed.events.DecisionEvent
import app.mauri.cucuto.xposed.events.Outcome
import app.mauri.cucuto.xposed.hook.AlertHooks
import app.mauri.cucuto.xposed.integration.DemoPacks.CRITTERS
import app.mauri.cucuto.xposed.integration.DemoPacks.KITCHEN
import app.mauri.cucuto.xposed.integration.Notifications.BUSINESS
import app.mauri.cucuto.xposed.integration.Notifications.WHATSAPP
import app.mauri.cucuto.xposed.route.AlertRouter
import app.mauri.cucuto.xposed.route.Targets
import io.github.libxposed.api.XposedInterface
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.net.URI

/**
 * Every routing feature together, from the app's database to what `system_server` plays (ADR 0047): voices and the
 * shuffle bag, the clip mirror and the self-test chirp, compact clip envelopes, haptic overrides, group policies and
 * per-sender voices, WhatsApp Business, Simulate, day and night clips and the live update after an app update.
 */
@RunWith(AndroidJUnit4::class)
class RoutingIntegrationTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val pipelines = mutableListOf<Pipeline>()

    @After
    fun tearDown() = pipelines.forEach { it.close() }

    private fun pipeline(
        mirror: Boolean = true,
        capabilities: app.mauri.cucuto.core.haptics.HapticCapabilities? = Pipeline.CAPABILITIES,
    ) = Pipeline(folder.newFolder(), mirror, capabilities).also { pipelines += it }

    // ---------------------------------------------------------------- the world

    private val anaPhone = "34600000001@s.whatsapp.net"
    private val ana = "111111111111111@lid"
    private val bo = "222222222222222@lid"
    private val cy = "333333333333333@lid"
    private val dee = "444444444444444@lid"
    private val shop = "555555555555555@lid"
    private val seenOnly = "666666666666666@lid"
    private val stranger = "999999999999999@lid"
    private val family = "120363000000000001@g.us"
    private val work = "120363000000000002@g.us"
    private val club = "120363000000000003@g.us"

    /** The frog's haptic from `packs/chiptune-critters/pack.yaml`: two clicks. */
    private val frogHaptic =
        Haptic.Custom(
            listOf(HapticEvent.Preset(0, HapticPreset.CLICK, 900), HapticEvent.Preset(200, HapticPreset.CLICK, 700)),
        )

    /** Made in the haptic editor for Dee. */
    private val deeHaptic =
        Haptic.Custom(
            listOf(HapticEvent.Preset(0, HapticPreset.TICK, 500), HapticEvent.Preset(90, HapticPreset.TICK, 500)),
        )

    /** The analysis of the cat's clips (off the compact grid on purpose: the catalog snaps them). */
    private val catEnvelopes: Map<String, Haptic.Custom> =
        listOf("mew", "mrrp", "purr").withIndex().associate { (i, name) ->
            "characters/cat/clips/$name.ogg" to
                Haptic.Custom(
                    listOf(
                        HapticEvent.Envelope(
                            atMs = 0,
                            points =
                                listOf(
                                    ControlPoint(301 + 100 * i, 333, 41 + 20 * i),
                                    ControlPoint(0, 250, 77),
                                ),
                        ),
                    ),
                )
        }

    private class World(
        val ana: Long,
        val bo: Long,
        val cy: Long,
        val dee: Long,
        val family: Long,
        val work: Long,
        val anaBusiness: Long,
        val shop: Long,
    )

    /**
     * Ana (frog: day and night clips, a preset haptic) under a phone JID and a LID; Bo (cat: `auto` with analysed
     * envelopes); Cy (bird, vibration off); Dee (pot, with her own haptic; one of the pot's clips cannot be mirrored);
     * Family (per-sender, glass), Work (one voice, whisk: `auto` without analysis), Club (per-sender, no voice);
     * Ana again and a shop on WhatsApp Business; and someone seen but never given a voice.
     */
    private suspend fun Pipeline.world(): World {
        val critters = install(CRITTERS, autoHaptics = catEnvelopes)
        val kitchen = install(KITCHEN)
        // A clip SystemUI cannot get a copy of (the mirror skips it): the provider serves it.
        File(kitchen.path, "characters/pot/clips/clang.ogg").delete()
        packs.recordInstalled(critters)
        packs.recordInstalled(kitchen)

        val anaId = contacts.findOrCreate(WHATSAPP, ana, "Ana", isGroup = false)
        contacts.addKey(anaId, WHATSAPP, anaPhone)
        assignments.assign(anaId, CRITTERS, "frog")
        val boId = contacts.findOrCreate(WHATSAPP, bo, "Bo", isGroup = false)
        assignments.assign(boId, CRITTERS, "cat")
        val cyId = contacts.findOrCreate(WHATSAPP, cy, "Cy", isGroup = false)
        assignments.assign(cyId, CRITTERS, "bird")
        assignments.setHapticOverride(cyId, HapticOverride.Off.toJson())
        val deeId = contacts.findOrCreate(WHATSAPP, dee, "Dee", isGroup = false)
        assignments.assign(deeId, KITCHEN, "pot")
        assignments.setHapticOverride(deeId, HapticOverride.Custom(deeHaptic).toJson())
        contacts.findOrCreate(WHATSAPP, seenOnly, "Seen", isGroup = false)

        val familyId = contacts.findOrCreate(WHATSAPP, family, "Family", isGroup = true)
        contacts.setGroupPolicy(familyId, GroupPolicy.PER_SENDER)
        assignments.assign(familyId, KITCHEN, "glass")
        val workId = contacts.findOrCreate(WHATSAPP, work, "Work", isGroup = true)
        assignments.assign(workId, KITCHEN, "whisk")
        val clubId = contacts.findOrCreate(WHATSAPP, club, "Club", isGroup = true)
        contacts.setGroupPolicy(clubId, GroupPolicy.PER_SENDER)

        val anaBusiness = contacts.findOrCreate(BUSINESS, ana, "Ana (Business)", isGroup = false)
        assignments.assign(anaBusiness, KITCHEN, "kettle")
        val shopId = contacts.findOrCreate(BUSINESS, shop, "Shop", isGroup = false)
        assignments.assign(shopId, CRITTERS, "bee")
        return World(anaId, boId, cyId, deeId, familyId, workId, anaBusiness, shopId)
    }

    /** Starts [this] pipeline with [world] and waits for the module to hold the full table. */
    private suspend fun Pipeline.started(): World {
        val world = world()
        start()
        awaitModule { it.route(BUSINESS, shop) != null && it.route(WHATSAPP, club) != null }
        return world
    }

    private fun name(uri: String?): String? = uri?.substringAfterLast('/')

    private fun Pipeline.mirrored(uri: String?): Boolean =
        uri != null && uri.startsWith("file://${mirrorRoot.absolutePath}/")

    private fun expectedEffect(haptic: Haptic.Custom) = HapticEffects.create(haptic, Pipeline.CAPABILITIES)

    private fun assertSwapped(
        alert: Alert,
        event: DecisionEvent?,
        pkg: String,
        key: String,
        contactId: Long,
    ): String {
        assertNotNull("decision reported", event)
        event!!
        assertEquals(Outcome.SWAPPED.name, event.outcome)
        assertEquals(pkg, event.packageName)
        assertEquals(key, event.conversationKey)
        assertEquals(contactId, event.contactId)
        assertEquals("the framework played the clip the module reported", event.clipUri, alert.sound.toString())
        return event.clipUri!!
    }

    private fun assertUntouched(alert: Alert) {
        assertEquals(FakeFramework.WHATSAPP_TONE, alert.sound)
        assertEquals(FakeFramework.WHATSAPP_VIBRATION, alert.vibration)
        assertFalse(alert.vibrationSuppressed)
    }

    // ---------------------------------------------------------------- tests

    @Test
    fun hooksAreInstalledProtectively() {
        val pipeline = pipeline()
        assertEquals(setOf(AlertHooks.PLAY_SOUND, AlertHooks.PLAY_VIBRATION), pipeline.framework.hooked)
        assertEquals(listOf(AlertHooks.PLAY_SOUND, AlertHooks.PLAY_VIBRATION), pipeline.hooks.installed)
        assertTrue(pipeline.hooks.missing.isEmpty())
        assertTrue(
            pipeline.framework.exceptionModes.values
                .all { it == XposedInterface.ExceptionMode.PROTECTIVE },
        )
    }

    @Test
    fun aOneToOneChatPlaysItsVoiceFromTheMirror() =
        runBlocking<Unit> {
            val p = pipeline()
            val world = p.started()

            val (alert, event) = p.alert(Notifications.oneToOne(ana, "Ana"))
            val clip = assertSwapped(alert, event, WHATSAPP, ana, world.ana)
            assertTrue("mirrored: $clip", p.mirrored(clip))
            assertTrue(name(clip) in setOf("ribbit.ogg", "hop.ogg"))
            // SystemUI opens the copy: same bytes as the pack's clip.
            val copy = File(URI(clip))
            assertArrayEquals(
                DemoPacks.sourceFile(CRITTERS, "characters/frog/clips/${name(clip)}").readBytes(),
                copy.readBytes(),
            )
            assertEquals(expectedEffect(frogHaptic), alert.vibration)
            assertEquals(false, event!!.isGroup)
            assertEquals("Ana", event.displayName)
            assertNull(event.senderKey)

            // The same person under the phone JID (older notifications) speaks with the same voice.
            val (byPhone, phoneEvent) = p.alert(Notifications.oneToOne(anaPhone, "Ana"))
            assertSwapped(byPhone, phoneEvent, WHATSAPP, anaPhone, world.ana)
            // One shuffle bag per speaker key: never the same clip twice in a row for one chat.
            var last = name(clip)
            repeat(12) {
                val next = name(p.alert(Notifications.oneToOne(ana, "Ana")).second!!.clipUri)
                assertNotEquals(last, next)
                last = next
            }

            // Warm, both hooks together (record reading, routing, haptic effect) stay inside the brief's 5 ms.
            p.hooks.maxMicros.set(0)
            val slowBefore = p.hooks.slowDecisions.get()
            repeat(200) { p.alert(Notifications.oneToOne(if (it % 2 == 0) ana else bo, "Someone")) }
            assertTrue("max ${p.hooks.maxMicros.get()} us", p.hooks.maxMicros.get() < AlertHooks.SLOW_NANOS / 1000)
            assertEquals(slowBefore, p.hooks.slowDecisions.get())
        }

    @Test
    fun dayAndNightClipsFollowTheClockAndThePlace() =
        runBlocking<Unit> {
            val p = pipeline()
            p.started()

            fun draws(count: Int = 16): Set<String?> =
                (1..count)
                    .map {
                        name(p.alert(Notifications.oneToOne(ana, "Ana")).second!!.clipUri)
                    }.toSet()

            p.clock.time = Pipeline.at(12, 0)
            assertEquals(setOf("ribbit.ogg", "hop.ogg"), draws())
            p.clock.time = Pipeline.at(23, 30)
            assertEquals(setOf("ribbit.ogg", "croak.ogg"), draws())
            // No place: day is 07:00 to 19:00, so 07:30 is already day.
            p.clock.time = Pipeline.at(7, 30)
            assertEquals(setOf("ribbit.ogg", "hop.ogg"), draws())

            // In Barcelona the sun rises at 08:13 on 24 October: the app recompiles the conditions and republishes.
            val before = p.moduleTables.value.generation
            p.setPlace()
            p.awaitModule { it.generation != before }
            assertEquals("before sunrise", setOf("ribbit.ogg", "croak.ogg"), draws())
            p.clock.time = Pipeline.at(8, 30)
            assertEquals("after sunrise", setOf("ribbit.ogg", "hop.ogg"), draws())
            p.clock.time = Pipeline.at(19, 10)
            assertEquals("after sunset (18:57)", setOf("ribbit.ogg", "croak.ogg"), draws())

            // Far outside the published span the module does not know, and every clip may play.
            p.clock.time = Pipeline.at(12, 0, Pipeline.DAY.plusDays(500))
            assertEquals(setOf("ribbit.ogg", "hop.ogg", "croak.ogg"), draws(40))
        }

    @Test
    fun autoHapticsFollowTheClipThatPlays() =
        runBlocking<Unit> {
            val p = pipeline()
            val world = p.started()
            val table = p.moduleTables.value
            assertEquals(
                "the cat's three envelopes travel as H lines",
                3,
                table.voices
                    .flatMap {
                        it.clips
                    }.mapNotNull { it.haptic }
                    .distinct()
                    .size,
            )

            val seen = mutableSetOf<String>()
            repeat(12) {
                val (alert, event) = p.alert(Notifications.oneToOne(bo, "Bo"))
                val clip = assertSwapped(alert, event, WHATSAPP, bo, world.bo)
                val path = "characters/cat/clips/${name(clip)}"
                seen += path
                assertEquals(path, expectedEffect(HapticGrid.snap(catEnvelopes.getValue(path))), alert.vibration)
            }
            assertEquals(catEnvelopes.keys, seen)

            // `auto` without an analysis keeps WhatsApp's vibration (Work's whisk).
            val (whisk, _) = p.alert(Notifications.groupSummary(work, "Work"))
            assertTrue(whisk.sound.toString().contains("/whisk/"))
            assertEquals(FakeFramework.WHATSAPP_VIBRATION, whisk.vibration)
        }

    @Test
    fun hapticOverridesWinOverThePack() =
        runBlocking<Unit> {
            val p = pipeline()
            val world = p.started()

            val (off, offEvent) = p.alert(Notifications.oneToOne(cy, "Cy"))
            assertSwapped(off, offEvent, WHATSAPP, cy, world.cy)
            assertTrue("vibration off for Cy", off.vibrationSuppressed)
            assertNull(off.vibration)

            val (own, ownEvent) = p.alert(Notifications.oneToOne(dee, "Dee"))
            assertSwapped(own, ownEvent, WHATSAPP, dee, world.dee)
            assertEquals(expectedEffect(deeHaptic), own.vibration)

            // Back to the pack's haptic: the next message vibrates like the bird.
            p.assignments.setHapticOverride(world.cy, HapticOverride.PackDefault.toJson())
            p.awaitModule { t -> t.resolve(WHATSAPP, cy)?.voice?.haptic != Haptic.Off }
            val (bird, _) = p.alert(Notifications.oneToOne(cy, "Cy"))
            assertFalse(bird.vibrationSuppressed)
            assertNotEquals(FakeFramework.WHATSAPP_VIBRATION, bird.vibration)
        }

    @Test
    fun groupsFollowTheirPolicy() =
        runBlocking<Unit> {
            val p = pipeline()
            val world = p.started()

            // The group summary names no sender: the per-sender group plays its own voice.
            val (summary, summaryEvent) = p.alert(Notifications.groupSummary(family, "Family"))
            val glass = assertSwapped(summary, summaryEvent, WHATSAPP, family, world.family)
            assertTrue(glass.contains("/glass/"))
            assertEquals(true, summaryEvent!!.isGroup)
            assertEquals(AlertRouter.DETAIL_NO_SENDER, summaryEvent.detail)

            // A message naming Bo: Bo's own voice, Bo's contact, Bo's envelope.
            val (fromBo, boEvent) = p.alert(Notifications.groupMessage(family, "Family", bo, "Bo"))
            val cat = assertSwapped(fromBo, boEvent, WHATSAPP, family, world.bo)
            assertTrue(cat.contains("/cat/"))
            assertEquals(AlertRouter.DETAIL_PER_SENDER, boEvent!!.detail)
            assertEquals(bo, boEvent.senderKey)
            assertEquals("Bo", boEvent.senderName)
            assertEquals(
                expectedEffect(HapticGrid.snap(catEnvelopes.getValue("characters/cat/clips/${name(cat)}"))),
                fromBo.vibration,
            )

            // A member without a voice: the group's voice.
            val (fromStranger, strangerEvent) = p.alert(Notifications.groupMessage(family, "Family", stranger, "Zed"))
            assertTrue(assertSwapped(fromStranger, strangerEvent, WHATSAPP, family, world.family).contains("/glass/"))
            assertEquals(AlertRouter.DETAIL_SENDER_UNASSIGNED, strangerEvent!!.detail)

            // One voice per group: Bo writing in Work still sounds like Work.
            val (inWork, workEvent) = p.alert(Notifications.groupMessage(work, "Work", bo, "Bo"))
            assertTrue(assertSwapped(inWork, workEvent, WHATSAPP, work, world.work).contains("/whisk/"))
            assertNull(workEvent!!.detail)

            // A per-sender group with no voice of its own keeps WhatsApp's tone unless the sender has one.
            val (clubSummary, clubEvent) = p.alert(Notifications.groupSummary(club, "Club"))
            assertUntouched(clubSummary)
            assertEquals(Outcome.UNASSIGNED.name, clubEvent!!.outcome)
            assertEquals(AlertRouter.DETAIL_NO_SENDER_NO_GROUP_VOICE, clubEvent.detail)
            val (clubBo, clubBoEvent) = p.alert(Notifications.groupMessage(club, "Club", bo, "Bo"))
            assertTrue(assertSwapped(clubBo, clubBoEvent, WHATSAPP, club, world.bo).contains("/cat/"))

            // Switching Family to one voice: Bo's messages there now sound like the group.
            p.contacts.setGroupPolicy(world.family, GroupPolicy.GROUP)
            p.awaitModule { t -> t.route(WHATSAPP, family)?.isPerSender == false }
            val (grouped, groupedEvent) = p.alert(Notifications.groupMessage(family, "Family", bo, "Bo"))
            assertTrue(assertSwapped(grouped, groupedEvent, WHATSAPP, family, world.family).contains("/glass/"))
        }

    @Test
    fun whatsAppBusinessHasItsOwnVoices() =
        runBlocking<Unit> {
            val p = pipeline()
            val world = p.started()

            // The same LID on Business is another contact, with another voice and another shuffle bag.
            val (business, businessEvent) = p.alert(Notifications.oneToOne(ana, "Ana", pkg = BUSINESS))
            assertTrue(assertSwapped(business, businessEvent, BUSINESS, ana, world.anaBusiness).contains("/kettle/"))
            val (shopAlert, shopEvent) = p.alert(Notifications.oneToOne(shop, "Shop", pkg = BUSINESS))
            assertTrue(assertSwapped(shopAlert, shopEvent, BUSINESS, shop, world.shop).contains("/bee/"))

            // Bo has a voice on WhatsApp only.
            val (boOnBusiness, boEvent) = p.alert(Notifications.oneToOne(bo, "Bo", pkg = BUSINESS))
            assertUntouched(boOnBusiness)
            assertEquals(Outcome.UNASSIGNED.name, boEvent!!.outcome)
            assertEquals(BUSINESS, boEvent.packageName)
            // And the shop has none on WhatsApp.
            assertEquals(Outcome.UNASSIGNED.name, p.alert(Notifications.oneToOne(shop, "Shop")).second!!.outcome)
        }

    @Test
    fun unassignedChatsAndOtherAppsKeepTheirTone() =
        runBlocking<Unit> {
            val p = pipeline()
            p.started()

            for (key in listOf(stranger, seenOnly)) {
                val (alert, event) = p.alert(Notifications.oneToOne(key, "Someone"))
                assertUntouched(alert)
                assertEquals(Outcome.UNASSIGNED.name, event!!.outcome)
                assertEquals(key, event.conversationKey)
                assertNull(event.contactId)
                assertNull(event.clipUri)
            }

            val (telegram, telegramEvent) = p.alert(Notifications.otherApp())
            assertUntouched(telegram)
            assertNull("other apps are not even reported", telegramEvent)
        }

    @Test
    fun aSilentChannelOnlyChangesTheVibration() =
        runBlocking<Unit> {
            val p = pipeline()
            val world = p.started()

            // The chat's channel has no sound: the framework only calls playVibration.
            val (alert, event) = p.alert(Notifications.oneToOne(ana, "Ana"), sound = null)
            assertNull(alert.sound)
            assertEquals(expectedEffect(frogHaptic), alert.vibration)
            assertEquals(Outcome.SWAPPED.name, event!!.outcome)
            assertEquals(world.ana, event.contactId)

            // Neither sound nor vibration: the framework calls no hook, and there is nothing to report.
            val (quiet, quietEvent) = p.alert(Notifications.oneToOne(ana, "Ana"), sound = null, vibration = null)
            assertEquals(Alert(null, null, false), quiet)
            assertNull(quietEvent)
        }

    @Test
    fun theGlobalSwitchTurnsEverythingOffButTheSelfTest() =
        runBlocking<Unit> {
            val p = pipeline()
            val world = p.started()

            p.settings.setEnabled(false)
            p.awaitModule { !it.enabled }
            val (alert, event) = p.alert(Notifications.oneToOne(ana, "Ana"))
            assertUntouched(alert)
            assertEquals(Outcome.DISABLED.name, event!!.outcome)
            assertUntouched(p.alert(Notifications.groupMessage(family, "Family", bo, "Bo")).first)
            val simulated = p.alert(Notifications.simulate(WHATSAPP, ana)).second!!
            assertEquals(Outcome.DISABLED.name, simulated.outcome)
            // Diagnostics still proves the hook works.
            assertEquals(Outcome.SWAPPED.name, p.alert(Notifications.selfTest()).second!!.outcome)

            p.settings.setEnabled(true)
            p.awaitModule { it.enabled }
            val (back, backEvent) = p.alert(Notifications.oneToOne(ana, "Ana"))
            assertSwapped(back, backEvent, WHATSAPP, ana, world.ana)
        }

    @Test
    fun simulateDrawsFromTheChatsOwnBag() =
        runBlocking<Unit> {
            val p = pipeline()
            val world = p.started()

            val (alert, event) = p.alert(Notifications.simulate(WHATSAPP, ana))
            event!!
            // Reported under Cucuto's own package, so the app never takes it for a real message.
            assertEquals(Targets.SELF_PACKAGE, event.packageName)
            assertEquals(Outcome.SWAPPED.name, event.outcome)
            assertEquals(ana, event.conversationKey)
            assertEquals(world.ana, event.contactId)
            assertTrue(event.detail!!.startsWith(AlertRouter.SIMULATED_DETAIL))
            assertEquals(event.clipUri, alert.sound.toString())
            assertTrue(p.mirrored(event.clipUri))
            assertEquals(expectedEffect(frogHaptic), alert.vibration)

            // The simulated clip counts as played: Ana's next real message does not repeat it.
            repeat(6) {
                val previous = p.alert(Notifications.simulate(WHATSAPP, ana)).second!!.clipUri
                val real = p.alert(Notifications.oneToOne(ana, "Ana")).second!!.clipUri
                assertNotEquals(previous, real)
            }

            // Business chats, per-sender groups without a sender, unknown chats and other packages.
            assertTrue(
                p
                    .alert(Notifications.simulate(BUSINESS, ana))
                    .second!!
                    .clipUri!!
                    .contains("/kettle/"),
            )
            val group = p.alert(Notifications.simulate(WHATSAPP, family)).second!!
            assertTrue(group.clipUri!!.contains("/glass/"))
            assertEquals("${AlertRouter.SIMULATED_DETAIL}, ${AlertRouter.DETAIL_NO_SENDER}", group.detail)
            assertEquals(true, group.isGroup)
            val (unknown, unknownEvent) = p.alert(Notifications.simulate(WHATSAPP, stranger))
            assertUntouched(unknown)
            assertEquals(Outcome.UNASSIGNED.name, unknownEvent!!.outcome)
            assertEquals(
                Outcome.NO_KEY.name,
                p.alert(Notifications.simulate("org.telegram.messenger", ana)).second!!.outcome,
            )
        }

    @Test
    fun theSelfTestPlaysTheMirroredChirp() =
        runBlocking<Unit> {
            val p = pipeline()
            p.started()
            val (alert, event) = p.alert(Notifications.selfTest())
            assertEquals(Outcome.SWAPPED.name, event!!.outcome)
            assertEquals(AlertRouter.SELF_TEST_KEY, event.conversationKey)
            assertEquals("file://${p.mirrorRoot.absolutePath}/selftest/chirp.wav", event.clipUri)
            assertArrayEquals(p.chirp.readBytes(), File(URI(event.clipUri!!)).readBytes())
            assertEquals(Uri.parse(event.clipUri), alert.sound)
            assertEquals(expectedEffect(Targets.SELF_TEST_HAPTIC), alert.vibration)
        }

    @Test
    fun withoutTheMirrorEveryClipComesFromTheProvider() =
        runBlocking<Unit> {
            val p = pipeline(mirror = false)
            val world = p.started()
            val table = p.moduleTables.value
            assertNull(table.selfTestClip)
            assertTrue(
                table.voices.flatMap { it.clips }.all {
                    it.uri.startsWith(
                        "content://${RoutingContract.CLIP_AUTHORITY}/",
                    )
                },
            )

            val (alert, event) = p.alert(Notifications.oneToOne(ana, "Ana"))
            val clip = assertSwapped(alert, event, WHATSAPP, ana, world.ana)
            assertTrue(
                clip,
                clip.matches(
                    Regex(
                        "content://app\\.mauri\\.cucuto\\.clips/$CRITTERS/frog/characters/frog/clips/(ribbit|hop)\\.ogg",
                    ),
                ),
            )
            assertEquals(Targets.SELF_TEST_CLIP, p.alert(Notifications.selfTest()).second!!.clipUri)
        }

    @Test
    fun aClipTheMirrorCannotCopyFallsBackToTheProvider() =
        runBlocking<Unit> {
            val p = pipeline()
            val world = p.started()
            val pot =
                p.moduleTables.value
                    .resolve(WHATSAPP, dee)!!
                    .voice
            assertEquals(
                listOf(
                    "file://${p.mirrorRoot.absolutePath}/$KITCHEN/1.1.0/characters/pot/clips/bong.ogg",
                    RoutingContract.clipUri(KITCHEN, "pot", "characters/pot/clips/clang.ogg"),
                ),
                pot.clips.map { it.uri },
            )
            val played =
                (1..6).map {
                    name(
                        assertSwapped(
                            p.alert(Notifications.oneToOne(dee, "Dee")).first,
                            p.events.last(),
                            WHATSAPP,
                            dee,
                            world.dee,
                        ),
                    )
                }
            assertEquals(setOf("bong.ogg", "clang.ogg"), played.toSet())
            // The mirror keeps exactly what the published table can play.
            val mirrored =
                p.mirrorRoot
                    .walk()
                    .filter {
                        it.isFile && it.name != ".nomedia"
                    }.map { "file://${it.absolutePath}" }
                    .toSet()
            assertEquals(
                app.mauri.cucuto.core.data.routing.ClipMirrorStore
                    .fileUris(p.moduleTables.value),
                mirrored,
            )
        }

    @Test
    fun anUpdatedAppStillReachesTheRunningModule() =
        runBlocking<Unit> {
            val p = pipeline()
            val world = p.started()
            // After an app update LSPosed stops delivering preference changes until the next reboot (ADR 0024).
            p.service.deliverPreferenceChanges = false

            p.assignments.assign(world.ana, KITCHEN, "glass")
            p.awaitModule { it.resolve(WHATSAPP, ana)?.voice?.characterId == "glass" }
            val (alert, event) = p.alert(Notifications.oneToOne(ana, "Ana"))
            assertTrue(assertSwapped(alert, event, WHATSAPP, ana, world.ana).contains("/glass/"))
            assertTrue(p.moduleLog.any { it.startsWith("Routing table ") })

            // A torn read of the file (the app rewrote it in place) leaves the module on the last good table.
            val good = p.holder.peek()
            p.service.remoteFile = p.service.remoteFile!!.copyOf(100)
            assertFalse(p.holder.refresh())
            assertEquals(good, p.holder.peek())
        }

    @Test
    fun theModuleReadsExactlyWhatTheAppPublished() =
        runBlocking<Unit> {
            val p = pipeline()
            p.started()
            val published = p.awaitPublished()
            val held: RoutingTable = p.holder.peek()
            assertEquals(published.generation, held.generation)
            assertEquals(published.routes, held.routes.size)
            assertEquals(published.voices, held.voices.size)
            assertFalse(published.clipHapticsLeftOut)
            assertEquals(
                p.service.writes
                    .last()
                    .length,
                published.encodedChars,
            )
            // Every key of every contact with a voice routes, phone JID and LID alike; the seen-only contact does not.
            val keys = held.routes.map { it.packageName to it.key }.toSet()
            assertEquals(
                setOf(ana, anaPhone, bo, cy, dee, family, work, club).map { WHATSAPP to it }.toSet() +
                    setOf(BUSINESS to ana, BUSINESS to shop),
                keys,
            )
        }

    @Test
    fun theReleasedModuleReadsTodaysTable() =
        runBlocking<Unit> {
            val p = pipeline()
            val world = p.started()
            val before = p.moduleTables.value.generation
            p.setPlace()
            p.awaitModule { it.generation != before }
            val encoded = p.service.writes.last()
            val today = RoutingCodec.decode(encoded)
            val released = ReleasedRoutingReader.decode(encoded)

            // Everything but the day and night conditions, which that module does not know about.
            assertEquals(today.withoutConditions(), released)
            // So the frog plays all its clips whatever the time, as ADR 0037 promises.
            val router = AlertRouter(now = { Pipeline.at(12, 0) })
            val snapshot =
                app.mauri.cucuto.xposed.route
                    .NotificationSnapshot(WHATSAPP, shortcutId = ana)
            val clips = (1..40).map { name(router.decide(snapshot, released)!!.clipUri) }.toSet()
            assertEquals(setOf("ribbit.ogg", "hop.ogg", "croak.ogg"), clips)
            assertEquals(world.ana, router.decide(snapshot, released)!!.contactId)
        }

    /**
     * Records a golden table for [RoutingGoldenTest] from the world above, with Barcelona as the place:
     * `./gradlew :xposed:testDebugUnitTest --tests '*recordGolden' -Pcucuto.recordGolden=<file>`. Skipped otherwise.
     * Goldens are frozen: add a new one when the format grows, never rewrite an old one.
     */
    @Test
    fun recordGolden() =
        runBlocking<Unit> {
            val target = System.getProperty("cucuto.recordGolden")?.takeIf { it.isNotBlank() }
            assumeTrue("Set -Pcucuto.recordGolden=<file> to record a golden table", target != null)
            val p = pipeline()
            p.started()
            val before = p.moduleTables.value.generation
            p.setPlace()
            p.awaitModule { it.generation != before }
            val golden =
                p.service.writes.last().replace(
                    "file://${p.mirrorRoot.absolutePath}/",
                    "file://${RoutingGoldenTest.MIRROR_ROOT}/",
                )
            File(target!!).writeText(golden)
        }
}
