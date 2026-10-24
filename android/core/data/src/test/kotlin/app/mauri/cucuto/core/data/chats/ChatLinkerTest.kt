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

/**
 * Chats added from contacts and their linking on the first notification (ADR 0065), on Room. Made-up names, numbers,
 * keys and lookup keys only.
 */
@RunWith(AndroidJUnit4::class)
class ChatLinkerTest {
    private lateinit var db: CucutoDatabase
    private lateinit var contacts: ContactRepository
    private lateinit var seen: SeenSenderRepository
    private lateinit var directory: RoomChatDirectory
    private lateinit var linker: ChatLinker
    private lateinit var sightings: AlertSightings
    private val clock = FakeClock()
    private val phone = FakeWhatsAppContacts()

    private val ana = person("lk-ana", "Ana Test", "34600000001")
    private val bruno = person("lk-bruno", "Bruno Test", "34600000002")

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        contacts = ContactRepository(db, db.contactDao(), clock)
        seen = SeenSenderRepository(db.seenSenderDao())
        val assignments = AssignmentRepository(db, db.assignmentDao(), clock)
        directory =
            RoomChatDirectory(contacts, assignments, seen, PhoneRingtoneFollow(db.phoneRingtoneDao(), { false }, clock))
        linker = ChatLinker(contacts, seen, phone)
        sightings = AlertSightings(seen, contacts, linker)
        phone.entries = listOf(ana, bruno)
    }

    @After
    fun tearDown() = db.close()

    private fun person(
        lookupKey: String,
        name: String,
        vararg numbers: String,
        packageName: String = WA,
    ) = WhatsAppContact(lookupKey, name, packageName, numbers.map { "$it@s.whatsapp.net" })

    private suspend fun message(
        key: String,
        name: String?,
        packageName: String = WA,
    ) = sightings.record(
        packageName,
        key,
        name,
        isGroup = false,
        seenAt = clock.now(),
        senderKey = null,
        senderName = null,
    )

    private suspend fun chats() = directory.observeChats().first()

    @Test
    fun `picked people are saved as chats waiting for their first message, linked to their contact`() =
        runTest {
            val saved = linker.addFromContacts(listOf(ana), phone.entries)

            assertEquals(listOf(ChatKey(WA, "34600000001@s.whatsapp.net")), saved)
            val chat = chats().single()
            assertTrue(chat.awaitingLink)
            assertEquals("Ana Test", chat.displayName)
            assertEquals(listOf("+34600000001"), chat.phoneNumbers)
            val contact = contacts.get(chat.contactId!!)!!.contact
            assertEquals("lk-ana", contact.androidLookupKey)
            assertEquals("Ana Test", contact.androidDisplayName)
            assertTrue(ana in linker.known())
            assertFalse("only the people picked are saved", bruno in linker.known())
        }

    @Test
    fun `the first notification links the lid, so the voice plays from the next message`() =
        runTest {
            val pending = linker.addFromContacts(listOf(ana), phone.entries).single()
            val contactId = directory.assign(pending, PACK, "frog")

            message(ANA_LID, "Ana Test")

            assertEquals(contactId, contacts.findByKey(WA, ANA_LID)?.id)
            val chat = chats().single()
            assertFalse(chat.awaitingLink)
            assertEquals(ChatKey(WA, ANA_LID), chat.key)
            assertEquals(ChatVoice(PACK, "frog"), chat.voice)
        }

    @Test
    fun `someone who already messaged is linked as soon as they are added`() =
        runTest {
            message(ANA_LID, "Ana Test")

            linker.addFromContacts(listOf(ana), phone.entries)

            val chat = chats().single()
            assertFalse(chat.awaitingLink)
            assertEquals(setOf(ChatKey(WA, ANA_LID), ChatKey(WA, "34600000001@s.whatsapp.net")), chat.keys.toSet())
        }

    @Test
    fun `a namesake the user did not add is asked about on Home instead of linked`() =
        runTest {
            val otherAna = person("lk-ana-2", "Ana Test", "34600000003")
            phone.entries = listOf(ana, otherAna)
            linker.addFromContacts(listOf(ana), phone.entries)

            message(ANA_LID, "Ana Test")

            assertNull(contacts.findByKey(WA, ANA_LID))
            val question = chats().single { it.key == ChatKey(WA, ANA_LID) }
            assertEquals(listOf("+34600000001"), question.linkCandidates.single().phoneNumbers)

            linker.confirmLink(question.key, question.linkCandidates.single().contactId)

            val linked = chats().single()
            assertFalse(linked.awaitingLink)
            assertTrue(linked.linkCandidates.isEmpty())
        }

    @Test
    fun `two people with the same name are asked, and one answer can settle the other`() =
        runTest {
            val otherAna = person("lk-ana-2", "Ana Test", "34600000003")
            phone.entries = listOf(ana, otherAna)
            val (first, second) = linker.addFromContacts(listOf(ana, otherAna), phone.entries)
            val firstId = contacts.findByKey(first.packageName, first.conversationKey)!!.id
            val secondId = contacts.findByKey(second.packageName, second.conversationKey)!!.id

            message(ANA_LID, "Ana Test")
            message(OTHER_LID, "Ana Test")

            val asked = chats().filter { it.linkCandidates.isNotEmpty() }
            assertEquals(setOf(ChatKey(WA, ANA_LID), ChatKey(WA, OTHER_LID)), asked.map { it.key }.toSet())
            assertEquals(
                listOf(firstId, secondId),
                asked
                    .first()
                    .linkCandidates
                    .map { it.contactId }
                    .sorted(),
            )

            linker.confirmLink(ChatKey(WA, ANA_LID), firstId)

            assertEquals(firstId, contacts.findByKey(WA, ANA_LID)?.id)
            assertEquals("the other Ana is now the only match", secondId, contacts.findByKey(WA, OTHER_LID)?.id)
            assertTrue(chats().none { it.awaitingLink || it.linkCandidates.isNotEmpty() })
        }

    @Test
    fun `without the contacts permission nothing links by itself, and contacts are read only on a name match`() =
        runTest {
            linker.addFromContacts(listOf(ana), phone.entries)
            phone.granted = false

            message(STRANGER_LID, "Someone Else")
            assertEquals(0, phone.loads)

            message(ANA_LID, "Ana Test")
            assertNull(contacts.findByKey(WA, ANA_LID))
            assertEquals(1, chats().single { it.key == ChatKey(WA, ANA_LID) }.linkCandidates.size)
        }

    @Test
    fun `groups and WhatsApp Business chats with the same name are not linked`() =
        runTest {
            linker.addFromContacts(listOf(ana), phone.entries)

            sightings.record(WA, "120300000000001@g.us", "Ana Test", true, clock.now(), null, null)
            message("100000000000009@lid", "Ana Test", packageName = ChatKey.WHATSAPP_BUSINESS)

            assertTrue(chats().single { it.contactId != null }.awaitingLink)
            assertTrue(chats().all { it.linkCandidates.isEmpty() })
        }

    @Test
    fun `adding someone twice, or someone who already has a chat, never makes a second chat`() =
        runTest {
            linker.addFromContacts(listOf(ana), phone.entries)
            linker.addFromContacts(listOf(ana), phone.entries)
            assertEquals(1, chats().size)

            // Bruno messaged and got a voice before he was added from contacts.
            message(BRUNO_LID, "Bruno Test")
            val brunoId = directory.assign(ChatKey(WA, BRUNO_LID), PACK, "owl")

            linker.addFromContacts(listOf(bruno), phone.entries)

            val chat = chats().single { it.contactId == brunoId }
            assertFalse(chat.awaitingLink)
            assertEquals("lk-bruno", contacts.get(brunoId)!!.contact.androidLookupKey)
            assertEquals(2, chats().size)
            assertTrue(bruno in linker.known())
        }

    @Test
    fun `a WhatsApp Business entry of the same person is its own chat`() =
        runTest {
            val anaBusiness = person("lk-ana", "Ana Test", "34600000001", packageName = ChatKey.WHATSAPP_BUSINESS)

            linker.addFromContacts(listOf(ana, anaBusiness), phone.entries + anaBusiness)

            assertEquals(setOf(WA, ChatKey.WHATSAPP_BUSINESS), chats().map { it.key.packageName }.toSet())
            assertTrue(chats().all { it.awaitingLink })
        }

    private companion object {
        const val WA = ChatKey.WHATSAPP
        const val PACK = "app.mauri.demo.critters"
        const val ANA_LID = "100000000000001@lid"
        const val OTHER_LID = "100000000000003@lid"
        const val BRUNO_LID = "100000000000002@lid"
        const val STRANGER_LID = "100000000000099@lid"
    }
}
