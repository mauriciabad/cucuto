package app.mauri.cucuto.core.ringtone.restore

import app.mauri.cucuto.core.data.db.ContactRingtoneWriteEntity
import app.mauri.cucuto.core.data.db.PhoneRingtoneRow
import app.mauri.cucuto.core.ringtone.contacts.AndroidContact
import app.mauri.cucuto.core.ringtone.contacts.AndroidContactRingtone
import app.mauri.cucuto.core.ringtone.contacts.AndroidContacts
import app.mauri.cucuto.core.ringtone.store.PhoneRingtoneStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Restored contacts (Android links and phone ringtones) finding their Android contacts on a new phone, or on the same
 * one (ADR 0057; ADR 0013's amendment for #43).
 */
class ContactRelinkerTest {
    private val contacts = FakeContacts()
    private val links = FakeLinks()
    private val store = FakeStore()
    private val relinker = ContactRelinker(contacts, links, store) { 42L }

    private fun person(
        key: String,
        name: String,
        vararg numbers: String,
        ringtone: String? = null,
    ) {
        contacts.people[key] = Person(name, numbers.toList(), ringtone)
    }

    @Test
    fun `on a new phone the phone number finds the contact even when the old lookup key means nothing here`() =
        runTest {
            person("new-key-ada", "Ada Lovelace", "+34600111222")
            links.keys[1] = "0r1-ABC"
            links.numbers[1] = listOf("+34600111222")

            val result = relinker.relink(RestoredLink(1, "Ada", androidName = "Ada Lovelace"))
            assertEquals(RelinkOutcome.LINKED, result.outcome)
            assertEquals("new-key-ada", links.keys[1])
        }

    @Test
    fun `a lookup key that still resolves to the person with that number is kept`() =
        runTest {
            person("0r1-ABC", "Ada", "+34600111222")
            links.keys[1] = "0r1-ABC"
            links.numbers[1] = listOf("+34600111222")
            assertEquals(RelinkOutcome.KEPT, relinker.relink(RestoredLink(1)).outcome)
            assertEquals(0, links.linkCalls)
        }

    @Test
    fun `a lookup key that resolves to someone else than the number's owner loses to the number`() =
        runTest {
            person("0r1-ABC", "Somebody else", "+34999999999")
            person("ada-here", "Ada", "+34600111222")
            links.keys[1] = "0r1-ABC"
            links.numbers[1] = listOf("+34600111222")
            val result = relinker.relink(RestoredLink(1))
            assertEquals(RelinkOutcome.LINKED, result.outcome)
            assertEquals("ada-here", result.lookupKey)
        }

    @Test
    fun `several contacts share the number, and the old phone's name picks one`() =
        runTest {
            person("home", "Casa", "+34930000000")
            person("ada", "Ada Lovelace", "+34930000000")
            links.numbers[1] = listOf("+34930000000")
            val result = relinker.relink(RestoredLink(1, androidName = "ada lovelace"))
            assertEquals("ada", result.lookupKey)
        }

    @Test
    fun `without numbers, the lookup key, then the exact name`() =
        runTest {
            person("0r2-DEF", "Bruno")
            links.keys[2] = "0r2-DEF"
            assertEquals(RelinkOutcome.KEPT, relinker.relink(RestoredLink(2, androidName = "Bruno")).outcome)

            person("bruno-here", "Carla")
            links.keys[3] = "gone"
            val byName = relinker.relink(RestoredLink(3, androidName = "Carla"))
            assertEquals(RelinkOutcome.LINKED, byName.outcome)
            assertEquals("bruno-here", links.keys[3])
        }

    @Test
    fun `a contact missing on the new phone is unlinked and keeps its ringtone choice for later`() =
        runTest {
            person("someone", "Someone", "+34611111111")
            person("twin-1", "Twin")
            person("twin-2", "Twin")
            links.keys[1] = "0r1-ABC"
            links.numbers[1] = listOf("+34600111222")
            val result = relinker.relink(RestoredLink(1, androidName = "Twin", writtenUri = "content://x"))
            assertEquals(RelinkOutcome.MISSING, result.outcome)
            assertNull(result.lookupKey)
            assertTrue(1 in links.keys)
            assertNull(links.keys[1])
            assertFalse(result.undoSeeded)
            assertTrue(store.writes.isEmpty())
        }

    @Test
    fun `on the same phone the backup's undo record is adopted while Cucuto's old file is still on the contact`() =
        runTest {
            person("0r1-ABC", "Ada", "+34600111222", ringtone = "content://media/external/audio/media/1042")
            links.keys[1] = "0r1-ABC"
            links.numbers[1] = listOf("+34600111222")
            val item =
                RestoredLink(
                    1,
                    originalRingtone = "content://media/internal/audio/media/31",
                    writtenUri = "content://media/external/audio/media/1042",
                )
            assertTrue(relinker.relink(item).undoSeeded)
            assertEquals(
                ContactRingtoneWriteEntity(
                    lookupKey = "0r1-ABC",
                    contactId = 1,
                    originalRingtone = "content://media/internal/audio/media/31",
                    writtenUri = "content://media/external/audio/media/1042",
                    variant = null,
                    userChanged = false,
                    writtenAt = 42,
                ),
                store.writes["0r1-ABC"],
            )
            // Idempotent: a second restore never replaces the record this phone keeps.
            store.writes["0r1-ABC"] = store.writes.getValue("0r1-ABC").copy(writtenUri = "content://newer")
            assertFalse(relinker.relink(item).undoSeeded)
            assertEquals("content://newer", store.writes.getValue("0r1-ABC").writtenUri)
        }

    @Test
    fun `the undo record is ignored when the contact rings with anything else (another phone, or the user's choice)`() =
        runTest {
            person("ada", "Ada", "+34600111222", ringtone = null)
            links.numbers[1] = listOf("+34600111222")
            val result = relinker.relink(RestoredLink(1, writtenUri = "content://media/external/audio/media/1042"))
            assertEquals(RelinkOutcome.LINKED, result.outcome)
            assertFalse(result.undoSeeded)
            assertTrue(store.writes.isEmpty())
        }

    @Test
    fun `the pending list survives a round trip through settings and shrugs off garbage`() {
        val items =
            listOf(
                RestoredLink(1, "Ada", "Ada L.", originalRingtone = "content://o", writtenUri = "content://w"),
                RestoredLink(2),
                RestoredLink(4, "Bruno", "Bruno", ringtone = false),
            )
        assertEquals(items, RestoredLink.decode(RestoredLink.encode(items)))
        assertEquals(emptyList<RestoredLink>(), RestoredLink.decode("{not json"))
        assertEquals(emptyList<RestoredLink>(), RestoredLink.decode(null))
        // Lists written before #43 have no "ringtone": they held phone ringtones only.
        assertEquals(listOf(RestoredLink(3)), RestoredLink.decode("""[{"contactId":3},{"name":"x"}]"""))
        assertEquals(listOf(RestoredLink(5)), RestoredLink.decode("""[{"contactId":5,"ringtone":"no"}]"""))
    }

    @Test
    fun `a contact restored with only its Android link is matched the same way and never adopts an undo record`() =
        runTest {
            person("new-key-bruno", "Bruno", "+34600333444", ringtone = "content://media/external/audio/media/1042")
            links.keys[2] = "0r2-DEF"
            links.numbers[2] = listOf("+34600333444")
            val item =
                RestoredLink(
                    2,
                    "Bruno",
                    androidName = "Bruno",
                    ringtone = false,
                    writtenUri = "content://media/external/audio/media/1042",
                )
            val result = relinker.relink(item)
            assertEquals(RelinkOutcome.LINKED, result.outcome)
            assertEquals("new-key-bruno", links.keys[2])
            assertFalse(result.undoSeeded)
            assertTrue(store.writes.isEmpty())
        }

    private data class Person(
        val name: String,
        val numbers: List<String>,
        val ringtone: String?,
    )

    private class FakeContacts : AndroidContacts {
        val people = LinkedHashMap<String, Person>()

        override fun canReadAndWrite() = true

        override suspend fun find(lookupKey: String) =
            people[lookupKey]?.let { AndroidContactRingtone(lookupKey, it.name, it.ringtone) }

        override suspend fun setRingtone(
            lookupKey: String,
            uri: String?,
        ) = false

        override suspend fun byPhoneNumber(number: String) =
            people.filter { number in it.value.numbers }.map { AndroidContact(it.key, it.value.name) }

        override suspend fun byName(name: String) =
            people
                .filter {
                    it.value.name.equals(
                        name,
                        ignoreCase = true,
                    )
                }.map { AndroidContact(it.key, it.value.name) }

        override suspend fun resolve(contactUri: String): AndroidContact? = null

        override suspend fun phoneNumbers(lookupKey: String) = people[lookupKey]?.numbers.orEmpty()
    }

    private class FakeLinks : ContactLinks {
        val keys = HashMap<Long, String?>()
        val numbers = HashMap<Long, List<String>>()
        var linkCalls = 0

        override suspend fun linkedKey(contactId: Long) = keys[contactId]

        override suspend fun phoneNumbers(contactId: Long) = numbers[contactId].orEmpty()

        override suspend fun link(
            contactId: Long,
            contact: AndroidContact?,
        ) {
            linkCalls++
            keys[contactId] = contact?.lookupKey
        }
    }

    private class FakeStore : PhoneRingtoneStore {
        val writes = LinkedHashMap<String, ContactRingtoneWriteEntity>()

        override suspend fun rows() = emptyList<PhoneRingtoneRow>()

        override suspend fun writes() = writes.values.toList()

        override suspend fun upsertWrite(write: ContactRingtoneWriteEntity) {
            writes[write.lookupKey] = write
        }

        override suspend fun deleteWrite(lookupKey: String) {
            writes.remove(lookupKey)
        }
    }
}
