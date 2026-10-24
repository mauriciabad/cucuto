package app.mauri.cucuto.core.data.chats

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mauri.cucuto.core.data.FakeClock
import app.mauri.cucuto.core.data.NoContacts
import app.mauri.cucuto.core.data.db.CucutoDatabase
import app.mauri.cucuto.core.data.db.GroupPolicy
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
        directory =
            RoomChatDirectory(contacts, assignments, seen, PhoneRingtoneFollow(db.phoneRingtoneDao(), { false }, clock))
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
    fun aSeenGroupCanBeSwitchedToPerSenderWithoutAVoice() =
        runTest {
            seen.record(WA, CLUB, "Book club", isGroup = true, seenAt = 1_000)
            assertEquals(
                GroupPolicy.GROUP,
                directory
                    .observeChats()
                    .first()
                    .single()
                    .groupPolicy,
            )

            val contactId = directory.setGroupPolicy(ChatKey(WA, CLUB), GroupPolicy.PER_SENDER)

            val chat = directory.observeChats().first().single()
            assertEquals(contactId, chat.contactId)
            assertEquals(GroupPolicy.PER_SENDER, chat.groupPolicy)
            assertNull(chat.voice)
            assertEquals("Book club", chat.displayName)
            val group = contacts.get(contactId)!!.contact
            assertTrue(group.isGroup)

            // Switching back keeps the contact (and any voice) as it was.
            assertEquals(contactId, directory.setGroupPolicy(ChatKey(WA, CLUB), GroupPolicy.GROUP))
            assertEquals(
                GroupPolicy.GROUP,
                directory
                    .observeChats()
                    .first()
                    .single()
                    .groupPolicy,
            )
        }

    @Test
    fun theGroupPolicyIsKeptPerApp() =
        runTest {
            directory.setGroupPolicy(ChatKey(WA, CLUB), GroupPolicy.PER_SENDER)
            directory.assign(ChatKey(W4B, CLUB), PACK, "owl")

            val chats = directory.observeChats().first().associateBy { it.key }
            assertEquals(GroupPolicy.PER_SENDER, chats.getValue(ChatKey(WA, CLUB)).groupPolicy)
            assertEquals(GroupPolicy.GROUP, chats.getValue(ChatKey(W4B, CLUB)).groupPolicy)
            assertFalse(chats.getValue(ChatKey(WA, CLUB)).isBusiness)
            assertTrue(chats.getValue(ChatKey(W4B, CLUB)).isBusiness)
        }

    @Test
    fun perSenderGroupMembersBecomeRecentSenders() =
        runTest {
            val sightings = AlertSightings(seen, contacts, ChatLinker(contacts, seen, NoContacts))
            directory.setGroupPolicy(ChatKey(WA, CLUB), GroupPolicy.PER_SENDER)

            sightings.record(WA, CLUB, "Book club", isGroup = true, seenAt = 1_000, senderKey = ADA, senderName = "Ada")

            val chats = directory.observeChats().first()
            assertEquals(setOf(ChatKey(WA, CLUB), ChatKey(WA, ADA)), chats.map { it.key }.toSet())
            val ada = chats.single { it.key == ChatKey(WA, ADA) }
            assertEquals("Ada", ada.displayName)
            assertFalse(ada.isGroup)
            assertNull(ada.contactId)

            // A voice given to the member is her 1:1 key, which is what the per-sender lookup lands on.
            val adaId = directory.assign(ChatKey(WA, ADA), PACK, "frog")
            assertEquals(adaId, contacts.findByKey(WA, ADA)?.id)
        }

    @Test
    fun onlyPerSenderGroupsAddTheirMembers() =
        runTest {
            val sightings = AlertSightings(seen, contacts, ChatLinker(contacts, seen, NoContacts))
            // Default policy, seen only.
            sightings.record(WA, CLUB, "Club", isGroup = true, seenAt = 1_000, senderKey = ADA, senderName = "Ada")
            // Default policy, with a voice.
            directory.assign(ChatKey(WA, CLUB), PACK, "owl")
            sightings.record(WA, CLUB, "Club", isGroup = true, seenAt = 2_000, senderKey = BEA, senderName = "Bea")
            // Per sender, but the sender is not a person JID, or is missing.
            directory.setGroupPolicy(ChatKey(WA, CLUB), GroupPolicy.PER_SENDER)
            sightings.record(WA, CLUB, "Club", isGroup = true, seenAt = 3_000, senderKey = "Ada", senderName = "Ada")
            sightings.record(WA, CLUB, "Club", isGroup = true, seenAt = 4_000, senderKey = null, senderName = "Ada")
            // A 1:1 alert never adds a second row.
            sightings.record(WA, BEA, "Bea", isGroup = false, seenAt = 5_000, senderKey = ADA, senderName = "Ada")
            // A Business alert does not look at the WhatsApp group's policy.
            sightings.record(W4B, CLUB, "Club", isGroup = true, seenAt = 6_000, senderKey = ADA, senderName = "Ada")

            val keys =
                directory
                    .observeChats()
                    .first()
                    .map { it.key }
                    .toSet()
            assertEquals(setOf(ChatKey(WA, CLUB), ChatKey(WA, BEA), ChatKey(W4B, CLUB)), keys)
            assertEquals(4, seen.get(WA, CLUB)!!.messageCount)
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
