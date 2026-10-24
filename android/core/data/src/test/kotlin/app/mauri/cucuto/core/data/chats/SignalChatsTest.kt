package app.mauri.cucuto.core.data.chats

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mauri.cucuto.core.data.FakeClock
import app.mauri.cucuto.core.data.FakeWhatsAppContacts
import app.mauri.cucuto.core.data.db.CucutoDatabase
import app.mauri.cucuto.core.data.inMemoryDatabase
import app.mauri.cucuto.core.data.repository.AssignmentRepository
import app.mauri.cucuto.core.data.repository.ContactRepository
import app.mauri.cucuto.core.data.repository.SeenSenderRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Signal and Molly chats follow the person's Android contact (ADR 0081). Made-up keys and names only. */
@RunWith(AndroidJUnit4::class)
class SignalChatsTest {
    private lateinit var db: CucutoDatabase
    private lateinit var contacts: ContactRepository
    private lateinit var assignments: AssignmentRepository
    private lateinit var seen: SeenSenderRepository
    private lateinit var directory: RoomChatDirectory
    private lateinit var linker: ChatLinker
    private val clock = FakeClock()
    private val phone = FakeWhatsAppContacts()

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        contacts = ContactRepository(db, db.contactDao(), clock)
        assignments = AssignmentRepository(db, db.assignmentDao(), clock)
        seen = SeenSenderRepository(db.seenSenderDao())
        directory =
            RoomChatDirectory(
                contacts,
                assignments,
                seen,
                PhoneRingtoneFollow(db.phoneRingtoneDao(), { false }, clock),
                db.phoneRingtoneDao(),
            )
        linker = ChatLinker(contacts, seen, phone)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun chats() = directory.observeChats().first()

    private suspend fun routes() = db.contactDao().observeRoutingRows().first()

    @Test
    fun aVoiceOnALinkedWhatsAppChatAlsoRoutesSignalAndMolly() =
        runTest {
            val ana = contacts.findOrCreate(WA, ANA_LID, "Ana", isGroup = false)
            assignments.assign(ana, PACK, "frog")
            assertTrue("no link, no Signal route", routes().none { it.derived })

            contacts.linkAndroidContact(ana, LOOKUP, "Ana")
            val derived = routes().filter { it.derived }
            assertEquals(setOf(SIGNAL, MOLLY), derived.map { it.packageName }.toSet())
            assertTrue(derived.all { it.keyValue == ANA_SIGNAL && it.contactId == ana && it.characterId == "frog" })

            // Groups never get one, and a voice is needed.
            val group = contacts.findOrCreate(WA, "120363000000000001@g.us", "Family", isGroup = true)
            contacts.linkAndroidContact(group, "lk-group", null)
            assignments.assign(group, PACK, "owl")
            val bo = contacts.findOrCreate(WA, "100000000000002@lid", "Bo", isGroup = false)
            contacts.linkAndroidContact(bo, "lk-bo", "Bo")
            assertEquals(2, routes().count { it.derived })
        }

    @Test
    fun aSignalSightingJoinsThePersonsChatAndAssigningItReusesThem() =
        runTest {
            val ana = contacts.findOrCreate(WA, ANA_LID, "Ana", isGroup = false)
            contacts.linkAndroidContact(ana, LOOKUP, "Ana")
            seen.record(WA, ANA_LID, "Ana", isGroup = false, seenAt = 1_000)
            seen.record(SIGNAL, ANA_SIGNAL, "Ana", isGroup = false, seenAt = 2_000)

            val chat = chats().single()
            assertEquals(ana, chat.contactId)
            assertEquals(ChatKey(SIGNAL, ANA_SIGNAL), chat.key)
            assertEquals(SIGNAL, chat.signalApp)
            assertTrue(chat.onWhatsApp)
            assertEquals(2, chat.messageCount)
            assertEquals(ana, contacts.findByKey(SIGNAL, ANA_SIGNAL)?.id)

            assertEquals(ana, directory.assign(ChatKey(SIGNAL, ANA_SIGNAL), PACK, "frog"))
            assertEquals(1, chats().size)
            assertEquals(ChatVoice(PACK, "frog"), chats().single().voice)
        }

    @Test
    fun aNewSignalPersonStartsLinkedToTheirAndroidContact() =
        runTest {
            seen.record(SIGNAL, ANA_SIGNAL, "Ana", isGroup = false, seenAt = 1_000)
            val chat = chats().single()
            assertNull(chat.contactId)
            assertFalse(chat.onWhatsApp)

            val id = directory.assign(chat.key, PACK, "frog")
            val contact = contacts.get(id)!!.contact
            assertEquals(LOOKUP, contact.androidLookupKey)
            assertFalse(contact.isGroup)
            // Molly gets the voice too, through the link.
            assertTrue(routes().any { it.packageName == MOLLY && it.keyValue == ANA_SIGNAL && it.contactId == id })
            // An unsaved number stays its own chat, keyed by the recipient ID.
            seen.record(SIGNAL, "43", "+34600000009", isGroup = false, seenAt = 2_000)
            val unsaved = directory.assign(ChatKey(SIGNAL, "43"), PACK, "owl")
            assertNull(contacts.get(unsaved)!!.contact.androidLookupKey)
        }

    @Test
    fun aStoredKeyWinsOverAnotherContactsLink() =
        runTest {
            val older = contacts.findOrCreate(WA, ANA_LID, "Ana", isGroup = false)
            contacts.linkAndroidContact(older, LOOKUP, "Ana")
            assignments.assign(older, PACK, "frog")
            val business = contacts.findOrCreate(ChatKey.WHATSAPP_BUSINESS, ANA_LID, "Ana", isGroup = false)
            contacts.linkAndroidContact(business, LOOKUP, "Ana")
            assignments.assign(business, PACK, "owl")

            // Both link the same person: the oldest contact comes first, so the builder keeps it.
            val signalRows = routes().filter { it.packageName == SIGNAL && it.keyValue == ANA_SIGNAL }
            assertEquals(listOf(older, business), signalRows.map { it.contactId })

            val signalOnly = contacts.findOrCreate(SIGNAL, ANA_SIGNAL, "Ana", isGroup = false)
            assertEquals("the derived owner answers first", older, signalOnly)
        }

    @Test
    fun signalEntriesFromContactsBecomeChatsAtOnce() =
        runTest {
            val entry = WhatsAppContact(LOOKUP, "Ana", SIGNAL, emptyList(), listOf("+34600000001"))
            phone.entries = listOf(entry)
            assertFalse(entry in linker.known())

            val saved = linker.addFromContacts(listOf(entry), phone.entries)

            assertEquals(listOf(ChatKey(SIGNAL, ANA_SIGNAL)), saved)
            val chat = chats().single()
            assertFalse("Signal needs no first message to link", chat.awaitingLink)
            assertEquals(listOf("+34600000001"), chat.phoneNumbers)
            assertEquals(LOOKUP, contacts.get(chat.contactId!!)!!.contact.androidLookupKey)
            assertTrue(entry in linker.known())

            // Adding it again, or a person already linked through WhatsApp, makes no second chat.
            linker.addFromContacts(listOf(entry), phone.entries)
            assertEquals(1, chats().size)
        }

    @Test
    fun aPersonLinkedThroughWhatsAppIsAlreadyKnownOnSignal() =
        runTest {
            val ana = contacts.findOrCreate(WA, ANA_LID, "Ana", isGroup = false)
            contacts.linkAndroidContact(ana, LOOKUP, "Ana")
            val entry = WhatsAppContact(LOOKUP, "Ana", SIGNAL, emptyList())
            assertTrue(entry in linker.known())
            assertEquals(listOf(ChatKey(SIGNAL, ANA_SIGNAL)), linker.addFromContacts(listOf(entry), listOf(entry)))
            assertEquals(1, chats().size)
        }

    private companion object {
        const val WA = ChatKey.WHATSAPP
        const val SIGNAL = ChatKey.SIGNAL
        const val MOLLY = ChatKey.MOLLY
        const val PACK = "app.mauri.demo.critters"
        const val ANA_LID = "100000000000001@lid"
        const val LOOKUP = "3789r12-2A4C3E"
        const val ANA_SIGNAL = "contact:$LOOKUP"
    }
}
