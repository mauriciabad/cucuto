package app.mauri.cucuto.xposed.route

import app.mauri.cucuto.core.routing.Clip
import app.mauri.cucuto.core.routing.ContactKeys
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.Route
import app.mauri.cucuto.core.routing.RoutingTable
import app.mauri.cucuto.core.routing.Voice
import app.mauri.cucuto.xposed.ModuleLog
import app.mauri.cucuto.xposed.events.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Signal and Molly (ADR 0081), on the shapes Signal-Android's `NotificationFactory.notifyForConversation` posts: the
 * conversation's own notification alerts, its shortcut ID is the recipient ID, and its one `Person` carries the Android
 * contact's lookup URI when the person is a saved contact. Every identifier here is made up.
 */
class SignalRoutingTest {
    private val lookupKey = "3789r12-2A4C3E"
    private val anaUri = "content://com.android.contacts/contacts/lookup/$lookupKey/1234"
    private val anaKey = "contact:$lookupKey"

    private fun signal(
        packageName: String = ContactKeys.SIGNAL,
        shortcutId: String? = "42",
        personUri: String? = anaUri,
        isGroupConversation: Boolean = false,
        senderKey: String? = null,
        senderUri: String? = null,
    ) = NotificationSnapshot(
        packageName = packageName,
        channelId = "messages_3",
        shortcutId = shortcutId,
        shortcutInfoId = shortcutId,
        shortcutLabel = "Ana",
        title = "Ana",
        isGroupConversation = isGroupConversation,
        senderKey = senderKey,
        senderUri = senderUri,
        personUri = personUri,
    )

    @Test
    fun savedContactIsKeyedByTheAndroidContactThenTheRecipientId() {
        val id = ConversationKeys.identify(signal())
        assertEquals(listOf(anaKey, "42"), id.keys)
        assertFalse(id.isGroup)
        assertEquals("Ana", id.displayName)
    }

    @Test
    fun withoutAContactUriOnlyTheRecipientIdIsUsed() {
        assertEquals(listOf("42"), ConversationKeys.identify(signal(personUri = null)).keys)
        assertEquals(listOf("42"), ConversationKeys.identify(signal(personUri = "tel:+34600000001")).keys)
        assertEquals(listOf("42"), ConversationKeys.identify(signal(personUri = "content://evil/lookup/x/1")).keys)
    }

    @Test
    fun screenLockHidesTheShortcutButThePersonStillNamesTheContact() {
        assertEquals(listOf(anaKey), ConversationKeys.identify(signal(shortcutId = null)).keys)
    }

    @Test
    fun mollyIsReadTheSameWay() {
        assertEquals(listOf(anaKey, "42"), ConversationKeys.identify(signal(packageName = ContactKeys.MOLLY)).keys)
    }

    @Test
    fun whatsAppNeverUsesAPersonUri() {
        val id = ConversationKeys.identify(signal(packageName = "com.whatsapp"))
        assertEquals(listOf("42"), id.keys)
    }

    @Test
    fun groupsComeFromTheMessagingStyleFlagAndSendersFromTheirContact() {
        val id =
            ConversationKeys.identify(
                signal(
                    shortcutId = "17",
                    personUri = null,
                    isGroupConversation = true,
                    senderKey = "42",
                    senderUri = anaUri,
                ),
            )
        assertEquals(listOf("17"), id.keys)
        assertTrue(id.isGroup)
        assertEquals(anaKey, id.senderKey)
        val unsaved =
            ConversationKeys.identify(
                signal(shortcutId = "17", personUri = null, isGroupConversation = true, senderKey = "43"),
            )
        assertEquals("43", unsaved.senderKey)
    }

    @Test
    fun lookupUriParsing() {
        assertEquals(anaKey, ContactKeys.fromPersonUri(anaUri))
        assertEquals(anaKey, ContactKeys.fromPersonUri("content://com.android.contacts/contacts/lookup/$lookupKey"))
        assertEquals(anaKey, ContactKeys.fromPersonUri("content://com.android.contacts/contacts/lookup/$lookupKey?x=1"))
        assertNull(ContactKeys.fromPersonUri("content://com.android.contacts/contacts/lookup/"))
        assertNull(ContactKeys.fromPersonUri("content://com.android.contacts/contacts/lookup//12"))
        assertNull(ContactKeys.fromPersonUri("content://com.android.contacts/contacts/lookup/" + "a".repeat(300)))
        assertNull(ContactKeys.fromPersonUri(null))
        assertEquals(lookupKey, ContactKeys.lookupKeyOf(anaKey))
        assertNull(ContactKeys.lookupKeyOf("contact:"))
        assertNull(ContactKeys.lookupKeyOf("42"))
    }

    @Test
    fun contactKeysLogOnlyTheirShape() {
        assertEquals("<contact>", ModuleLog.keyShape(anaKey))
    }

    private val frog =
        Voice(
            "app.mauri.demo.critters",
            "frog",
            listOf(Clip("content://app.mauri.cucuto.clips/x/frog/a.ogg")),
            Haptic.Keep,
        )

    private fun table(vararg routes: Route) = RoutingTable(1, true, listOf(frog), routes.toList())

    @Test
    fun theDerivedContactRoutePlaysTheVoiceOnSignal() {
        val table =
            table(
                Route("com.whatsapp", "111111111111111@lid", 0, seed = 1, flags = 0, contactId = 10),
                Route(ContactKeys.SIGNAL, anaKey, 0, seed = 1, flags = 0, contactId = 10),
                Route(ContactKeys.MOLLY, anaKey, 0, seed = 1, flags = 0, contactId = 10),
            )
        val router = AlertRouter()
        val signal = router.decide(signal(), table)!!
        assertEquals(Outcome.SWAPPED, signal.outcome)
        assertEquals(anaKey, signal.matchedKey)
        assertEquals(10L, signal.contactId)
        assertEquals(Outcome.SWAPPED, router.decide(signal(packageName = ContactKeys.MOLLY), table)!!.outcome)
    }

    @Test
    fun anUnsavedSenderMatchesOnItsRecipientId() {
        val table = table(Route(ContactKeys.SIGNAL, "42", 0, seed = 1, flags = 0, contactId = 11))
        val decision = AlertRouter().decide(signal(personUri = null), table)!!
        assertEquals(Outcome.SWAPPED, decision.outcome)
        assertEquals("42", decision.matchedKey)
    }

    @Test
    fun anUnknownSignalChatKeepsSignalsToneAndReportsItsContactKey() {
        val decision = AlertRouter().decide(signal(), table())!!
        assertEquals(Outcome.UNASSIGNED, decision.outcome)
        assertEquals(anaKey, decision.identity.primaryKey)
        assertNull(decision.clipUri)
    }

    @Test
    fun aPerSenderSignalGroupPlaysTheSendersVoice() {
        val table =
            table(
                Route(ContactKeys.SIGNAL, "17", Route.NO_VOICE, 3, Route.FLAG_GROUP or Route.FLAG_PER_SENDER, 12),
                Route(ContactKeys.SIGNAL, anaKey, 0, seed = 1, flags = 0, contactId = 10),
            )
        val decision =
            AlertRouter().decide(
                signal(
                    shortcutId = "17",
                    personUri = null,
                    isGroupConversation = true,
                    senderKey = "42",
                    senderUri = anaUri,
                ),
                table,
            )!!
        assertEquals(Outcome.SWAPPED, decision.outcome)
        assertEquals(10L, decision.contactId)
    }

    @Test
    fun otherPackagesStayUntouched() {
        assertNull(AlertRouter().decide(signal(packageName = "org.telegram.messenger"), table()))
        assertTrue(Targets.isTarget(ContactKeys.SIGNAL))
        assertTrue(Targets.isTarget(ContactKeys.MOLLY))
        assertEquals("com.whatsapp", Targets.MESSENGERS.first())
    }
}
