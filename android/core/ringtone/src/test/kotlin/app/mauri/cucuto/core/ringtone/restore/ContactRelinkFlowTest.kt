package app.mauri.cucuto.core.ringtone.restore

import android.Manifest
import android.app.Application
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mauri.cucuto.core.data.db.ContactRingtoneWriteEntity
import app.mauri.cucuto.core.data.db.PhoneRingtoneRow
import app.mauri.cucuto.core.ringtone.contacts.AndroidContact
import app.mauri.cucuto.core.ringtone.contacts.AndroidContacts
import app.mauri.cucuto.core.ringtone.contacts.ResolverAndroidContacts
import app.mauri.cucuto.core.ringtone.store.PhoneRingtoneStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/**
 * Re-linking restored contacts on a new phone (#43) through the real `ContactsContract` queries: [ContactRelinker] over
 * [ResolverAndroidContacts], against [FakeContactsProvider]. The backup's lookup keys (`0r…`) mean nothing on this
 * phone; its contacts have their own keys.
 */
@RunWith(AndroidJUnit4::class)
class ContactRelinkFlowTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var provider: FakeContactsProvider
    private val androidContacts by lazy { ResolverAndroidContacts(app) }
    private val links by lazy { Links(androidContacts) }
    private val store = Store()
    private val relinker by lazy { ContactRelinker(androidContacts, links, store) { 42L } }

    @Before
    fun setUp() {
        provider = Robolectric.setupContentProvider(FakeContactsProvider::class.java, ContactsContract.AUTHORITY)
        provider.add("here-ada", "Ada Lovelace", "+34600111222")
        provider.add("here-bruno", "Bruno", "+34 600 333 444")
        provider.add("here-twin-1", "Twin")
        provider.add("here-twin-2", "Twin")
    }

    private fun grantRead() = shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)

    @Test
    fun `reading contacts is enough to match, and without it nothing is read`() =
        runTest {
            assertFalse(androidContacts.canRead())
            assertEquals(emptyList<AndroidContact>(), androidContacts.byPhoneNumber("+34600111222"))
            assertTrue(provider.queried.isEmpty())

            grantRead()
            assertTrue(androidContacts.canRead())
            assertFalse("phone ringtones still need WRITE_CONTACTS", androidContacts.canReadAndWrite())
        }

    @Test
    fun `every restored Android link finds its contact by phone number, ringtone or not`() =
        runTest {
            grantRead()
            links.keys[1] = "0r1-ABC"
            links.numbers[1] = listOf("+34600111222")
            links.keys[2] = "0r2-DEF"
            links.numbers[2] = listOf("+34600333444")

            val ada = relinker.relink(RestoredLink(1, "Ada", androidName = "Ada Lovelace", ringtone = true))
            val bruno = relinker.relink(RestoredLink(2, "Bruno", androidName = "Bruno", ringtone = false))

            assertEquals(RelinkOutcome.LINKED, ada.outcome)
            assertEquals("here-ada", links.keys[1])
            assertEquals(RelinkOutcome.LINKED, bruno.outcome)
            assertEquals("here-bruno", links.keys[2])
            assertEquals("Bruno", links.names[2])
            // The linked contact's numbers are read back in E.164 for the next restore.
            assertEquals(listOf("+34600333444"), links.added[2])
        }

    @Test
    fun `without a number, the exact name finds a single contact and a namesake is never guessed`() =
        runTest {
            grantRead()
            links.keys[3] = "0r3-GHI"
            links.keys[4] = "0r4-JKL"

            val byName = relinker.relink(RestoredLink(3, "Ada", androidName = "ada lovelace", ringtone = false))
            assertEquals(RelinkOutcome.LINKED, byName.outcome)
            assertEquals("here-ada", links.keys[3])

            val twin = relinker.relink(RestoredLink(4, "Twin", androidName = "Twin", ringtone = false))
            assertEquals(RelinkOutcome.MISSING, twin.outcome)
            assertTrue("the stale key is removed", 4 in links.keys)
            assertNull(links.keys[4])
        }

    @Test
    fun `a contact picked by hand resolves through the picker's URI`() =
        runTest {
            // No permission: the picker grants access to the one contact it returns.
            val picked = androidContacts.resolve(provider.pickerUri("here-twin-2").toString())
            assertEquals(AndroidContact("here-twin-2", "Twin"), picked)
            assertNull(androidContacts.resolve("content://media/external/audio/media/1"))
        }

    @Test
    fun `contacts queries never run on the caller's thread, so screens can call them from the main thread`() =
        runTest {
            grantRead()
            val caller = Thread.currentThread()
            androidContacts.byPhoneNumber("+34600111222")
            androidContacts.byName("Bruno")
            androidContacts.phoneNumbers("here-ada")
            androidContacts.find("here-ada")
            androidContacts.resolve(provider.pickerUri("here-bruno").toString())

            assertEquals(6, provider.threads.size)
            assertTrue(provider.threads.none { it === caller })
        }

    private class Links(
        private val contacts: AndroidContacts,
    ) : ContactLinks {
        val keys = HashMap<Long, String?>()
        val names = HashMap<Long, String?>()
        val numbers = HashMap<Long, List<String>>()
        val added = HashMap<Long, List<String>>()

        override suspend fun linkedKey(contactId: Long) = keys[contactId]

        override suspend fun phoneNumbers(contactId: Long) = numbers[contactId].orEmpty()

        override suspend fun link(
            contactId: Long,
            contact: AndroidContact?,
        ) {
            keys[contactId] = contact?.lookupKey
            names[contactId] = contact?.displayName
            if (contact != null) added[contactId] = contacts.phoneNumbers(contact.lookupKey)
        }
    }

    private class Store : PhoneRingtoneStore {
        override suspend fun rows() = emptyList<PhoneRingtoneRow>()

        override suspend fun writes() = emptyList<ContactRingtoneWriteEntity>()

        override suspend fun upsertWrite(write: ContactRingtoneWriteEntity) = Unit

        override suspend fun deleteWrite(lookupKey: String) = Unit
    }
}
