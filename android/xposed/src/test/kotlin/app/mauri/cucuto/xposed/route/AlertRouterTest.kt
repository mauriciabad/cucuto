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
    fun selfTestWorksEvenWhenTheGlobalSwitchIsOff() {
        // It checks the hook, not the routing; WhatsApp stays untouched either way.
        val decision =
            router.decide(
                snapshot(null, pkg = Targets.SELF_PACKAGE, channel = Targets.SELF_TEST_CHANNEL),
                table(false),
            )
        assertEquals(Outcome.SWAPPED, decision!!.outcome)
    }

    private fun simulate(
        target: String?,
        key: String?,
        pkg: String = Targets.SELF_PACKAGE,
        channel: String = Targets.SIMULATE_CHANNEL,
    ) = NotificationSnapshot(packageName = pkg, channelId = channel, simulatePackage = target, simulateKey = key)

    @Test
    fun simulatePlaysTheChatsVoiceAndIsReportedAsCucuto() {
        val decision = router.decide(simulate("com.whatsapp", ana), table())!!
        assertEquals(Outcome.SWAPPED, decision.outcome)
        assertTrue(decision.clipUri in frog.clips.map { it.uri })
        assertEquals(10L, decision.contactId)
        assertEquals(ana, decision.matchedKey)
        // Never reported as the messenger: the app must not count it as a message from the chat.
        assertEquals(Targets.SELF_PACKAGE, decision.packageName)
        assertEquals(AlertRouter.SIMULATED_DETAIL, decision.detail)
    }

    @Test
    fun simulateUsesTheChatsPackage() {
        val business = router.decide(simulate("com.whatsapp.w4b", ana), table())!!
        assertEquals(cat.clips.single().uri, business.clipUri)
        assertEquals(click, business.haptic)
        assertEquals(13L, business.contactId)
    }

    @Test
    fun simulateSharesTheShuffleBagWithRealMessages() {
        // Simulated and real alerts interleaved still never repeat a clip: they draw from one rotation.
        var previous: String? = null
        repeat(200) { i ->
            val snapshot = if (i % 2 == 0) simulate("com.whatsapp", ana) else snapshot(ana)
            val clip = router.decide(snapshot, table())!!.clipUri
            assertNotEquals(previous, clip)
            previous = clip
        }
    }

    @Test
    fun simulateFollowsTheSameRulesAsARealMessage() {
        val unassigned = router.decide(simulate("com.whatsapp", stranger), table())!!
        assertEquals(Outcome.UNASSIGNED, unassigned.outcome)
        assertNull(unassigned.clipUri)
        assertEquals(stranger, unassigned.matchedKey)

        val off = router.decide(simulate("com.whatsapp", ana), table(enabled = false))!!
        assertEquals(Outcome.DISABLED, off.outcome)
        assertNull(off.clipUri)

        // A per-sender group without a sender plays the group's own voice.
        val group = router.decide(simulate("com.whatsapp", family), table())!!
        assertEquals(cat.clips.single().uri, group.clipUri)
        assertTrue(group.identity.isGroup)
    }

    @Test
    fun simulateRejectsOtherPackagesAndBadTargets() {
        // Another app cannot use the simulate channel or extras: its notification is never touched.
        assertNull(router.decide(simulate("com.whatsapp", ana, pkg = "org.example.spoof"), table()))
        // Only WhatsApp chats can be simulated, and only on the simulate channel.
        assertEquals(Outcome.NO_KEY, router.decide(simulate("org.telegram.messenger", ana), table())!!.outcome)
        assertEquals(Outcome.NO_KEY, router.decide(simulate("com.whatsapp", null), table())!!.outcome)
        assertEquals(Outcome.NO_KEY, router.decide(simulate("com.whatsapp", "x".repeat(300)), table())!!.outcome)
        assertNull(router.decide(simulate("com.whatsapp", ana, channel = "other"), table()))
    }
}
