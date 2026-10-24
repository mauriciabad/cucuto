package app.mauri.cucuto.core.ringtone.restore

import app.mauri.cucuto.core.data.db.ContactRingtoneWriteEntity
import app.mauri.cucuto.core.ringtone.contacts.AndroidContactRingtone
import app.mauri.cucuto.core.ringtone.contacts.AndroidContacts

/** A chat as [LinkRepair] sees it: its id, its Android link, and whether it is a group. */
data class ChatLink(
    val contactId: Long,
    val lookupKey: String?,
    val isGroup: Boolean = false,
)

/**
 * Gives back the Android link of chats that lost it while Cucuto's undo record still names their Android contact
 * (#369, ADR 0039's amendment). Up to 0.2.1 the "Change" button under the "Phone calls" row cleared the link at once,
 * so a user who opened it and picked nobody left the chat "Not linked yet", with Cucuto's ring file still on the
 * contact. Runs once.
 *
 * Only unambiguous cases: a 1:1 chat with no link, exactly one live record ([ContactRingtoneWriteEntity.userChanged]
 * false) naming it, no chat linked to that Android contact, and the contact still has what Cucuto left there.
 */
object LinkRepair {
    /** The Android contact each chat in [chats] gets back, by chat id (before checking the contacts themselves). */
    fun plan(
        chats: List<ChatLink>,
        writes: List<ContactRingtoneWriteEntity>,
    ): Map<Long, ContactRingtoneWriteEntity> {
        val byId = chats.associateBy { it.contactId }
        val linkedKeys = chats.mapNotNullTo(HashSet()) { it.lookupKey }
        return writes
            .filter { !it.userChanged }
            .groupBy { it.contactId }
            .mapNotNull { (contactId, records) ->
                val chat = byId[contactId] ?: return@mapNotNull null
                val record = records.singleOrNull() ?: return@mapNotNull null
                if (chat.isGroup || chat.lookupKey != null || record.lookupKey in linkedKeys) return@mapNotNull null
                contactId to record
            }.toMap()
    }

    /**
     * Re-links the chats [plan] finds whose Android contact still exists and still has what Cucuto wrote, through
     * [link]. Returns the ids of the chats it linked.
     */
    suspend fun repair(
        chats: List<ChatLink>,
        writes: List<ContactRingtoneWriteEntity>,
        contacts: AndroidContacts,
        link: suspend (contactId: Long, contact: AndroidContactRingtone) -> Unit,
    ): List<Long> =
        plan(chats, writes).mapNotNull { (contactId, record) ->
            val android = contacts.find(record.lookupKey) ?: return@mapNotNull null
            if (!record.stillOn(android.ringtone)) return@mapNotNull null
            link(contactId, android)
            contactId
        }

    private fun ContactRingtoneWriteEntity.stillOn(current: String?): Boolean =
        current == writtenUri || (writtenUri == ContactRingtoneWriteEntity.DEFAULT_RINGTONE && current == null)
}
