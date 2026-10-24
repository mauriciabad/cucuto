package app.mauri.cucuto.core.ringtone.restore

import app.mauri.cucuto.core.data.db.ContactRingtoneWriteEntity
import app.mauri.cucuto.core.ringtone.contacts.AndroidContact
import app.mauri.cucuto.core.ringtone.contacts.AndroidContactRingtone
import app.mauri.cucuto.core.ringtone.contacts.AndroidContacts
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The one-time repair of chats that lost their Android link while an undo record names their contact (#369). */
class LinkRepairTest {
    private val file = "content://media/external/audio/media/15910"

    private fun write(
        lookupKey: String,
        contactId: Long,
        writtenUri: String? = file,
        userChanged: Boolean = false,
    ) = ContactRingtoneWriteEntity(lookupKey, contactId, null, writtenUri, null, userChanged, writtenAt = 1)

    private val ringtones = HashMap<String, String?>()
    private val contacts =
        object : AndroidContacts {
            override fun canReadAndWrite() = true

            override suspend fun find(lookupKey: String) =
                if (lookupKey in ringtones) AndroidContactRingtone(lookupKey, "Contact", ringtones[lookupKey]) else null

            override suspend fun setRingtone(
                lookupKey: String,
                uri: String?,
            ) = error("The repair never writes contacts")

            override suspend fun byPhoneNumber(number: String) = emptyList<AndroidContact>()

            override suspend fun byName(name: String) = emptyList<AndroidContact>()

            override suspend fun resolve(contactUri: String): AndroidContact? = null

            override suspend fun phoneNumbers(lookupKey: String) = emptyList<String>()
        }

    private suspend fun repair(
        chats: List<ChatLink>,
        writes: List<ContactRingtoneWriteEntity>,
    ): Map<Long, String> {
        val linked = LinkedHashMap<Long, String>()
        LinkRepair.repair(chats, writes, contacts) { id, android -> linked[id] = android.lookupKey }
        return linked
    }

    @Test
    fun aChatThatLostItsLinkGetsTheRecordsContactBack() =
        runTest {
            ringtones["k3"] = file
            ringtones["k4"] = file
            val linked =
                repair(
                    listOf(ChatLink(3, null), ChatLink(4, "k4")),
                    listOf(write("k3", 3), write("k4", 4)),
                )
            assertEquals(mapOf(3L to "k3"), linked)
        }

    @Test
    fun aClearedContactStillCountsAsCucutos() =
        runTest {
            ringtones["k3"] = null
            val linked =
                repair(listOf(ChatLink(3, null)), listOf(write("k3", 3, ContactRingtoneWriteEntity.DEFAULT_RINGTONE)))
            assertEquals(mapOf(3L to "k3"), linked)
        }

    @Test
    fun ambiguousOrChangedCasesAreLeftAlone() =
        runTest {
            ringtones["changed"] = "content://users-own"
            ringtones["taken"] = file
            ringtones["flagged"] = file
            ringtones["twoA"] = file
            ringtones["twoB"] = file
            ringtones["group"] = file
            val linked =
                repair(
                    listOf(
                        ChatLink(1, null),
                        ChatLink(2, null),
                        ChatLink(3, null),
                        ChatLink(4, null),
                        ChatLink(5, null, isGroup = true),
                        ChatLink(6, null),
                        ChatLink(9, "taken"),
                    ),
                    listOf(
                        // Someone else's ringtone is on the contact now.
                        write("changed", 1),
                        // Another chat links that contact.
                        write("taken", 2),
                        // The user changed it in Contacts.
                        write("flagged", 3, userChanged = true),
                        // Two records name the same chat.
                        write("twoA", 4),
                        write("twoB", 4),
                        write("group", 5),
                        // The contact is gone.
                        write("deleted", 6),
                        // The chat is gone.
                        write("orphan", 7),
                    ),
                )
            assertEquals(emptyMap<Long, String>(), linked)
        }
}
