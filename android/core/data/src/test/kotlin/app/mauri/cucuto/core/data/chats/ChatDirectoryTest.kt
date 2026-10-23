package app.mauri.cucuto.core.data.chats

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mauri.cucuto.core.data.FakeClock
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

/** Made-up keys and names only. */
@RunWith(AndroidJUnit4::class)
class ChatDirectoryTest {
    private lateinit var db: CucutoDatabase
    private lateinit var contacts: ContactRepository
    private lateinit var assignments: AssignmentRepository
    private lateinit var seen: SeenSenderRepository
    private lateinit var directory: RoomChatDirectory
    private val clock = FakeClock()

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        contacts = ContactRepository(db, db.contactDao(), clock)
        assignments = AssignmentRepository(db, db.assignmentDao(), clock)
        seen = SeenSenderRepository(db.seenSenderDao())
        directory = RoomChatDirectory(contacts, assignments, seen)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun aSeenSenderIsAnUnassignedChatUntilItGetsAVoice() =
        runTest {
            seen.record(WA, ADA, "Ada", isGroup = false, seenAt = 1_000)

            val before = directory.observeChats().first().single()
            assertEquals(ChatKey(WA, ADA), before.key)
            assertNull(before.contactId)
            assertNull(before.voice)
            assertEquals("Ada", before.displayName)

            val contactId = directory.assign(ChatKey(WA, ADA), PACK, "frog")

            val after = directory.observeChats().first().single()
            assertEquals(contactId, after.contactId)
            assertEquals(ChatVoice(PACK, "frog"), after.voice)
            assertEquals("Ada", after.displayName)
            assertEquals(1_000L, after.lastSeenAt)
            // The routing table reads assignments by contact; the key now routes.
            assertEquals("frog", assignments.observe(contactId).first()?.characterId)
            assertEquals(contactId, contacts.findByKey(WA, ADA)?.id)
        }

    @Test
    fun reassigningReusesTheContactAndSwitchesTheCharacter() =
        runTest {
            seen.record(WA, ADA, "Ada", isGroup = false, seenAt = 1_000)
            val first = directory.assign(ChatKey(WA, ADA), PACK, "frog")
            val second = directory.assign(ChatKey(WA, ADA), PACK, "owl")

            assertEquals(first, second)
            assertEquals(
                ChatVoice(PACK, "owl"),
                directory
                    .observeChats()
                    .first()
                    .single()
                    .voice,
            )
        }

    @Test
    fun aGroupKeyAssignsTheWholeGroup() =
        runTest {
            seen.record(WA, CLUB, "Book club", isGroup = true, seenAt = 1_000, senderKey = ADA, senderName = "Ada")

            val contactId = directory.assign(ChatKey(WA, CLUB), PACK, "owl")

            val group = contacts.get(contactId)!!
            assertTrue(group.contact.isGroup)
            assertEquals(listOf(CLUB), group.keys.map { it.value })
            // The sender inside the group did not become a contact.
            assertNull(contacts.findByKey(WA, ADA))
        }

    @Test
    fun aGroupKeyIsAGroupEvenWithoutASighting() =
        runTest {
            val contactId = directory.assign(ChatKey(WA, CLUB), PACK, "owl")
            assertTrue(contacts.get(contactId)!!.contact.isGroup)
        }

    @Test
    fun unassignKeepsTheContactButDropsTheVoice() =
        runTest {
            seen.record(WA, ADA, "Ada", isGroup = false, seenAt = 1_000)
            val contactId = directory.assign(ChatKey(WA, ADA), PACK, "frog")

            directory.unassign(ChatKey(WA, ADA))
            directory.unassign(ChatKey(WA, "999@lid"))

            val chat = directory.observeChats().first().single()
            assertEquals(contactId, chat.contactId)
            assertNull(chat.voice)
        }

    @Test
    fun dismissingForgetsTheSightingButKeepsContacts() =
        runTest {
            seen.record(WA, ADA, "Ada", isGroup = false, seenAt = 1_000)
            seen.record(WA, BEA, "Bea", isGroup = false, seenAt = 2_000)
            directory.assign(ChatKey(WA, ADA), PACK, "frog")

            directory.dismissRecent(ChatKey(WA, ADA))
            directory.dismissRecent(ChatKey(WA, BEA))

            val chat = directory.observeChats().first().single()
            assertEquals(ChatKey(WA, ADA), chat.key)
            assertNull(chat.lastSeenAt)
            assertFalse(chat.voice == null)
        }

    @Test
    fun chatsAreNewestFirstAndAMovedContactIsOneChat() =
        runTest {
            seen.record(WA, ADA, "Ada", isGroup = false, seenAt = 1_000)
            seen.record(WA, BEA, "Bea", isGroup = false, seenAt = 3_000)
            val ada = directory.assign(ChatKey(WA, ADA), PACK, "frog")
            // WhatsApp moved Ada to a new identity; a matcher (or the user) attached it to her contact.
            contacts.addKey(ada, WA, ADA_PHONE)
            seen.record(WA, ADA_PHONE, "Ada", isGroup = false, seenAt = 5_000)
            seen.record(W4B, ADA, "Ada (work)", isGroup = false, seenAt = 2_000)

            val chats = directory.observeChats().first()

            assertEquals(
                listOf(ChatKey(WA, ADA_PHONE), ChatKey(WA, BEA), ChatKey(W4B, ADA)),
                chats.map { it.key },
            )
            assertEquals(ada, chats[0].contactId)
            assertEquals(2, chats[0].messageCount)
            assertEquals(setOf(ChatKey(WA, ADA), ChatKey(WA, ADA_PHONE)), chats[0].keys.toSet())
            assertEquals(ChatVoice(PACK, "frog"), chats[0].voice)
            assertNull(chats[2].voice)
        }

    @Test
    fun chatKeysNeverPrintTheFullKey() {
        assertEquals("ChatKey(com.whatsapp, …@lid)", ChatKey(WA, ADA).toString())
    }

    private companion object {
        const val WA = "com.whatsapp"
        const val W4B = "com.whatsapp.w4b"
        const val ADA = "100000000000001@lid"
        const val ADA_PHONE = "34600000001@s.whatsapp.net"
        const val BEA = "100000000000002@lid"
        const val CLUB = "120300000000001@g.us"
        const val PACK = "app.mauri.demo.critters"
    }
}
