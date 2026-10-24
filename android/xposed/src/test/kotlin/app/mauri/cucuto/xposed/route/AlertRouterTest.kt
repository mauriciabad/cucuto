package app.mauri.cucuto.xposed.route

import app.mauri.cucuto.core.routing.Clip
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import app.mauri.cucuto.core.routing.Route
import app.mauri.cucuto.core.routing.RoutingTable
import app.mauri.cucuto.core.routing.Voice
import app.mauri.cucuto.xposed.events.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class AlertRouterTest {
    private val ana = "111111111111111@lid"
    private val bo = "222222222222222@lid"
    private val stranger = "999999999999999@lid"
    private val family = "120363000000000001@g.us"

    private val click = Haptic.Custom(listOf(HapticEvent.Preset(0, HapticPreset.CLICK, 800)))
    private val tick = Haptic.Custom(listOf(HapticEvent.Preset(0, HapticPreset.TICK, 500)))

    private val frog =
        Voice(
            packId = "app.mauri.demo.critters",
            characterId = "frog",
            clips =
                listOf(
                    Clip("content://app.mauri.cucuto.clips/app.mauri.demo.critters/frog/a.ogg", haptic = tick),
                    Clip("content://app.mauri.cucuto.clips/app.mauri.demo.critters/frog/b.ogg"),
                    Clip("content://app.mauri.cucuto.clips/app.mauri.demo.critters/frog/c.ogg"),
                ),
            haptic = Haptic.Auto,
        )
    private val cat =
        Voice(
            "app.mauri.demo.critters",
            "cat",
            listOf(Clip("content://app.mauri.cucuto.clips/app.mauri.demo.critters/cat/m.ogg")),
            click,
        )

    private fun table(enabled: Boolean = true) =
        RoutingTable(
            generation = 1,
            enabled = enabled,
            voices = listOf(frog, cat),
            routes =
                listOf(
                    Route("com.whatsapp", ana, voice = 0, seed = 1, flags = 0, contactId = 10),
                    Route("com.whatsapp", bo, voice = Route.NO_VOICE, seed = 2, flags = 0, contactId = 11),
                    Route(
                        "com.whatsapp",
                        family,
                        voice = 1,
                        seed = 3,
                        flags = Route.FLAG_GROUP or Route.FLAG_PER_SENDER,
                        contactId = 12,
                    ),
                    Route("com.whatsapp.w4b", ana, voice = 1, seed = 4, flags = 0, contactId = 13),
                ),
        )

    private val router = AlertRouter(ShuffleBags(randomFor = { Random(it.toLong()) }))

    private fun snapshot(
        key: String?,
        pkg: String = "com.whatsapp",
        channel: String = "individual_chat_defaults_5",
        sender: String? = null,
    ) = NotificationSnapshot(packageName = pkg, channelId = channel, shortcutId = key, senderKey = sender)

    @Test
    fun otherPackagesAreIgnored() {
        assertNull(router.decide(snapshot(ana, pkg = "org.telegram.messenger"), table()))
        assertNull(router.decide(snapshot(ana, pkg = "com.android.systemui"), table()))
    }

    @Test
    fun assignedContactGetsAClipFromItsVoice() {
        val decision = router.decide(snapshot(ana), table())!!
        assertEquals(Outcome.SWAPPED, decision.outcome)
        assertEquals(ana, decision.matchedKey)
        assertEquals(10L, decision.contactId)
        assertTrue(decision.clipUri in frog.clips.map { it.uri })
    }

    @Test
    fun theSameClipNeverPlaysTwiceInARow() {
        var previous: String? = null
        repeat(300) {
            val clip = router.decide(snapshot(ana), table())!!.clipUri
            assertNotEquals(previous, clip)
            previous = clip
        }
    }

    @Test
    fun autoHapticFollowsTheClipAndFallsBackToKeep() {
        repeat(30) {
            val decision = router.decide(snapshot(ana), table())!!
            val expected = if (decision.clipUri!!.endsWith("/a.ogg")) tick else Haptic.Keep
            assertEquals(expected, decision.haptic)
        }
    }

    @Test
    fun customHapticIsPassedThrough() {
        assertEquals(click, router.decide(snapshot(ana, pkg = "com.whatsapp.w4b"), table())!!.haptic)
    }

    @Test
    fun packagesAreRoutedSeparately() {
        val business = router.decide(snapshot(ana, pkg = "com.whatsapp.w4b"), table())!!
        assertEquals(cat.clips.single().uri, business.clipUri)
        assertEquals(13L, business.contactId)
    }

    @Test
    fun unassignedContactsAreLeftAlone() {
        val unknown = router.decide(snapshot(stranger), table())!!
        assertEquals(Outcome.UNASSIGNED, unknown.outcome)
        assertNull(unknown.clipUri)
        assertNull(unknown.haptic)
        assertEquals(stranger, unknown.identity.primaryKey)

        val noVoice = router.decide(snapshot(bo), table())!!
        assertEquals(Outcome.UNASSIGNED, noVoice.outcome)
        assertEquals(11L, noVoice.contactId)
        assertNull(noVoice.clipUri)
    }

    @Test
    fun theGlobalSwitchTurnsEverythingOff() {
        val decision = router.decide(snapshot(ana), table(enabled = false))!!
        assertEquals(Outcome.DISABLED, decision.outcome)
        assertNull(decision.clipUri)
    }

    @Test
    fun notificationsWithoutAKeyAreReported() {
        assertEquals(Outcome.NO_KEY, router.decide(snapshot(null), table())!!.outcome)
    }

    @Test
    fun anEmptyTableChangesNothing() {
        assertEquals(Outcome.UNASSIGNED, router.decide(snapshot(ana), RoutingTable.EMPTY)!!.outcome)
    }

    @Test
    fun perSenderGroupUsesTheSendersVoiceAndFallsBackToTheGroup() {
        val bySender = router.decide(snapshot(family, channel = "group_chat_defaults_6", sender = ana), table())!!
        assertEquals(Outcome.SWAPPED, bySender.outcome)
        assertEquals(10L, bySender.contactId)
        assertTrue(bySender.clipUri in frog.clips.map { it.uri })

        val byGroup = router.decide(snapshot(family, channel = "group_chat_defaults_6", sender = stranger), table())!!
        assertEquals(12L, byGroup.contactId)
        assertEquals(cat.clips.single().uri, byGroup.clipUri)
    }

    // ------------------------------------------------------------- group policy (#20) and Business (#21)

    private val carla = "333333333333333@lid"
    private val friends = "120363000000000002@g.us"
    private val club = "120363000000000003@g.us"
    private val book = "120363000000000004@g.us"
    private val shop = "120363000000000005@g.us"
    private val wa = "com.whatsapp"
    private val w4b = "com.whatsapp.w4b"
    private val perSender = Route.FLAG_GROUP or Route.FLAG_PER_SENDER

    /** Fake records: every combination of policy, group voice and sender voice, in both apps. */
    private fun groupTable() =
        RoutingTable(
            generation = 2,
            enabled = true,
            voices = listOf(frog, cat),
            routes =
                listOf(
                    Route(wa, ana, voice = 0, seed = 1, flags = 0, contactId = 10),
                    Route(wa, bo, voice = Route.NO_VOICE, seed = 2, flags = 0, contactId = 11),
                    Route(wa, carla, voice = 0, seed = 5, flags = 0, contactId = 14),
                    // Per-sender group with a voice of its own.
                    Route(wa, family, voice = 1, seed = 3, flags = perSender, contactId = 12),
                    // Default policy ("one character per group") with a voice.
                    Route(wa, friends, voice = 1, seed = 6, flags = Route.FLAG_GROUP, contactId = 15),
                    // Default policy without a voice (only listed for completeness; the app does not publish it).
                    Route(wa, club, voice = Route.NO_VOICE, seed = 7, flags = Route.FLAG_GROUP, contactId = 16),
                    // Per-sender group without a voice of its own.
                    Route(wa, book, voice = Route.NO_VOICE, seed = 8, flags = perSender, contactId = 17),
                    // WhatsApp Business: its own Ana and its own per-sender group.
                    Route(w4b, ana, voice = 1, seed = 4, flags = 0, contactId = 13),
                    Route(w4b, shop, voice = Route.NO_VOICE, seed = 9, flags = perSender, contactId = 18),
                ),
        )

    private fun group(
        key: String,
        sender: String?,
        pkg: String = wa,
    ) = router.decide(snapshot(key, pkg = pkg, channel = "group_chat_defaults_6", sender = sender), groupTable())!!

    @Test
    fun perSenderGroupPlaysTheSendersVoice() {
        val decision = group(family, sender = ana)
        assertEquals(Outcome.SWAPPED, decision.outcome)
        assertEquals(family, decision.matchedKey)
        assertEquals(10L, decision.contactId)
        assertTrue(decision.clipUri in frog.clips.map { it.uri })
        assertEquals(AlertRouter.DETAIL_PER_SENDER, decision.detail)
    }

    @Test
    fun perSenderGroupFallsBackToItsOwnVoice() {
        val noVoice = group(family, sender = bo)
        assertEquals(12L, noVoice.contactId)
        assertEquals(cat.clips.single().uri, noVoice.clipUri)
        assertEquals(AlertRouter.DETAIL_SENDER_UNASSIGNED, noVoice.detail)

        val unknown = group(family, sender = stranger)
        assertEquals(12L, unknown.contactId)
        assertEquals(AlertRouter.DETAIL_SENDER_UNASSIGNED, unknown.detail)

        // The alerting summary carried no MessagingStyle sender.
        val noSender = group(family, sender = null)
        assertEquals(Outcome.SWAPPED, noSender.outcome)
        assertEquals(12L, noSender.contactId)
        assertEquals(AlertRouter.DETAIL_NO_SENDER, noSender.detail)
    }

    @Test
    fun perSenderGroupWithoutAVoiceOnlyPlaysVoicedSenders() {
        val voiced = group(book, sender = carla)
        assertEquals(Outcome.SWAPPED, voiced.outcome)
        assertEquals(14L, voiced.contactId)
        assertEquals(AlertRouter.DETAIL_PER_SENDER, voiced.detail)

        val silent = group(book, sender = bo)
        assertEquals(Outcome.UNASSIGNED, silent.outcome)
        assertEquals(17L, silent.contactId)
        assertNull(silent.clipUri)
        assertNull(silent.haptic)
        assertEquals(AlertRouter.DETAIL_SENDER_UNASSIGNED_NO_GROUP_VOICE, silent.detail)

        val noSender = group(book, sender = null)
        assertEquals(Outcome.UNASSIGNED, noSender.outcome)
        assertEquals(AlertRouter.DETAIL_NO_SENDER_NO_GROUP_VOICE, noSender.detail)
    }

    @Test
    fun defaultPolicyGroupNeverUsesItsMembersVoices() {
        val voiced = group(friends, sender = ana)
        assertEquals(Outcome.SWAPPED, voiced.outcome)
        assertEquals(15L, voiced.contactId)
        assertEquals(cat.clips.single().uri, voiced.clipUri)
        assertNull(voiced.detail)

        // ADR 0009 default (#86): a group without a voice keeps WhatsApp's tone even when the sender has one.
        val silent = group(club, sender = ana)
        assertEquals(Outcome.UNASSIGNED, silent.outcome)
        assertNull(silent.clipUri)
        assertEquals(AlertRouter.DETAIL_NO_VOICE, silent.detail)

        val unknownGroup = group("120363000000000099@g.us", sender = ana)
        assertEquals(Outcome.UNASSIGNED, unknownGroup.outcome)
        assertNull(unknownGroup.clipUri)
        assertNull(unknownGroup.contactId)
    }

    @Test
    fun aSenderOnAOneToOneNotificationIsIgnored() {
        // Only groups carry a sender into routing; a 1:1 chat is its own speaker.
        val decision = router.decide(snapshot(bo, sender = ana), groupTable())!!
        assertEquals(Outcome.UNASSIGNED, decision.outcome)
        assertNull(decision.identity.senderKey)
    }

    @Test
    fun businessGroupsOnlyUseBusinessVoices() {
        // Carla has a voice in WhatsApp, not in WhatsApp Business: her Business messages keep the tone.
        val other = group(shop, sender = carla, pkg = w4b)
        assertEquals(Outcome.UNASSIGNED, other.outcome)
        assertEquals(18L, other.contactId)

        // Ana's Business contact is a different contact with a different voice.
        val business = group(shop, sender = ana, pkg = w4b)
        assertEquals(13L, business.contactId)
        assertEquals(cat.clips.single().uri, business.clipUri)
        assertEquals(10L, group(family, sender = ana).contactId)

        // A WhatsApp group key means nothing to Business.
        assertEquals(Outcome.UNASSIGNED, group(family, sender = ana, pkg = w4b).outcome)
    }

    @Test
    fun aSendersShuffleBagIsSharedBetweenTheirChatAndPerSenderGroups() {
        // One bag per speaker: the same clip never plays twice in a row, whichever chat Ana writes in.
        var previous: String? = null
        repeat(200) { i ->
            val clip =
                if (i % 2 == 0) {
                    router.decide(snapshot(ana), groupTable())!!.clipUri
                } else {
                    group(family, sender = ana).clipUri
                }
            assertNotEquals(previous, clip)
            previous = clip
        }
    }

    @Test
    fun selfTestOnlyAppliesToItsChannel() {
        val selfTest =
            router.decide(
                snapshot(null, pkg = Targets.SELF_PACKAGE, channel = Targets.SELF_TEST_CHANNEL),
                table(),
            )!!
        assertEquals(Outcome.SWAPPED, selfTest.outcome)
        assertEquals(Targets.SELF_TEST_CLIP, selfTest.clipUri)
        assertEquals(Targets.SELF_TEST_HAPTIC, selfTest.haptic)

        assertNull(router.decide(snapshot(null, pkg = Targets.SELF_PACKAGE, channel = "other"), table()))
    }

    @Test
    fun selfTestPlaysTheMirroredChirpWhenTheTableNamesOne() {
        val mirrored = "file:///storage/emulated/0/Android/media/app.mauri.cucuto/clips/selftest/chirp.wav"
        val decision =
            router.decide(
                snapshot(null, pkg = Targets.SELF_PACKAGE, channel = Targets.SELF_TEST_CHANNEL),
                table().copy(selfTestClip = mirrored),
            )!!
        assertEquals(mirrored, decision.clipUri)
        assertEquals(Targets.SELF_TEST_HAPTIC, decision.haptic)
    }

    @Test
    fun selfTestWorksEvenWhenTheGlobalSwitchIsOff() {
        // It checks the hook, not the routing; WhatsApp stays untouched either way.
        val decision =
            router.decide(
                snapshot(null, pkg = Targets.SELF_PACKAGE, channel = Targets.SELF_TEST_CHANNEL),
                table(false),
            )
        assertEquals(Outcome.SWAPPED, decision!!.outcome)
    }
}
