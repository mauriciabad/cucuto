package app.mauri.cucuto.core.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import app.mauri.cucuto.core.data.FakeClock
import app.mauri.cucuto.core.data.db.ContactKeyKind
import app.mauri.cucuto.core.data.db.CucutoDatabase
import app.mauri.cucuto.core.data.db.GroupPolicy
import app.mauri.cucuto.core.data.inMemoryDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactRepositoryTest {
    private lateinit var db: CucutoDatabase
    private lateinit var contacts: ContactRepository
    private lateinit var assignments: AssignmentRepository
    private val clock = FakeClock()

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        contacts = ContactRepository(db, db.contactDao(), clock)
        assignments = AssignmentRepository(db, db.assignmentDao(), clock)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun findOrCreateReusesTheContactOfAKnownKey() =
        runTest {
            val id = contacts.findOrCreate(WA, PHONE_JID, "Ada", isGroup = false)
            clock.advance()
            assertEquals(id, contacts.findOrCreate(WA, PHONE_JID, "Ada L.", isGroup = false))

            val contact = contacts.get(id)!!
            assertEquals("Ada", contact.contact.displayName)
            val key = contact.keys.single()
            assertEquals(ContactKeyKind.PHONE_JID, key.kind)
            assertEquals("Ada L.", key.displayName)
            assertEquals(clock.time, key.lastSeenAt)
        }

    @Test
    fun keysArePerPackage() =
        runTest {
            val personal = contacts.findOrCreate(WA, PHONE_JID, "Ada", isGroup = false)
            val business = contacts.findOrCreate(W4B, PHONE_JID, "Ada (work)", isGroup = false)
            assertTrue(personal != business)
            assertEquals(business, contacts.findByKey(W4B, PHONE_JID)?.id)
        }

    @Test
    fun aContactMatchesOnEveryKey() =
        runTest {
            val id = contacts.findOrCreate(WA, PHONE_JID, "Ada", isGroup = false)
            assertTrue(contacts.addKey(id, WA, LID))
            assertTrue(contacts.addKey(id, WA, LID)) // idempotent
            assertTrue(contacts.addPhoneNumber(id, "+34600000001"))

            assertEquals(id, contacts.findByKey(WA, LID)?.id)
            val kinds =
                contacts
                    .get(id)!!
                    .keys
                    .map { it.kind }
                    .toSet()
            assertEquals(setOf(ContactKeyKind.PHONE_JID, ContactKeyKind.LID, ContactKeyKind.PHONE_NUMBER), kinds)

            // A key cannot belong to two contacts.
            val other = contacts.create("Someone else")
            assertFalse(contacts.addKey(other, WA, LID))
        }

    @Test
    fun groupsAreDetectedAndHaveAPolicy() =
        runTest {
            val id = contacts.findOrCreate(WA, "120363000000000001@g.us", "Family", isGroup = false)
            val group = contacts.get(id)!!.contact
            assertTrue(group.isGroup)
            assertEquals(GroupPolicy.GROUP, group.groupPolicy)

            contacts.setGroupPolicy(id, GroupPolicy.PER_SENDER)
            assertEquals(GroupPolicy.PER_SENDER, contacts.get(id)!!.contact.groupPolicy)
        }

    @Test
    fun mergeMovesKeysAndKeepsTheTargetsAssignment() =
        runTest {
            val phone = contacts.findOrCreate(WA, PHONE_JID, "Ada", isGroup = false)
            val lid = contacts.findOrCreate(WA, LID, "Ada", isGroup = false)
            assignments.assign(phone, "app.mauri.demo.critters", "frog")
            assignments.assign(lid, "app.mauri.demo.critters", "owl")

            contacts.merge(fromContactId = lid, intoContactId = phone)

            assertNull(contacts.get(lid))
            assertEquals(phone, contacts.findByKey(WA, LID)?.id)
            assertEquals("frog", db.assignmentDao().get(phone)?.characterId)
            assertNull(db.assignmentDao().get(lid))
        }

    @Test
    fun deletingAContactCascadesToKeysAndAssignment() =
        runTest {
            val id = contacts.findOrCreate(WA, PHONE_JID, "Ada", isGroup = false)
            assignments.assign(id, "app.mauri.demo.critters", "frog")
            contacts.delete(id)
            assertNull(contacts.findByKey(WA, PHONE_JID))
            assertNull(db.assignmentDao().get(id))
        }

    @Test
    fun observeContactsEmitsOnEveryChange() =
        runTest {
            contacts.observeContacts().test {
                assertEquals(emptyList<Any>(), awaitItem())

                val id = contacts.findOrCreate(WA, PHONE_JID, "Bea", isGroup = false)
                assertEquals(listOf("Bea"), awaitItem().map { it.contact.displayName })

                contacts.findOrCreate(WA, LID, "ada", isGroup = false)
                assertEquals(listOf("ada", "Bea"), awaitItem().map { it.contact.displayName })

                contacts.rename(id, "Beatriz")
                assertEquals(listOf("ada", "Beatriz"), awaitItem().map { it.contact.displayName })

                contacts.linkAndroidContact(id, "lookup-1", "Beatriz G.")
                val linked = awaitItem().single { it.contact.id == id }.contact
                assertEquals("lookup-1", linked.androidLookupKey)
                cancelAndIgnoreRemainingEvents()
            }
        }

    private companion object {
        const val WA = "com.whatsapp"
        const val W4B = "com.whatsapp.w4b"
        const val PHONE_JID = "34600000001@s.whatsapp.net"
        const val LID = "123456789012345@lid"
    }
}
