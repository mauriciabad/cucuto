package app.mauri.cucuto.core.data.chats

import app.mauri.cucuto.core.data.db.SeenSenderDao
import app.mauri.cucuto.core.data.repository.AssignmentRepository
import app.mauri.cucuto.core.data.repository.ContactRepository
import app.mauri.cucuto.core.data.repository.SeenSenderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The chats the user can give a voice to, and the one-tap assignment behind the home screen and the character picker.
 * Every change republishes the routing table (the publisher observes the database), so the next notification of an
 * assigned chat plays its character.
 */
interface ChatDirectory {
    /** Every chat, most recently seen first ([mergeChats]). */
    fun observeChats(): Flow<List<Chat>>

    /**
     * Gives [chat] the voice of `(packId, characterId)`. A conversation that was only seen becomes a contact first,
     * named after its notification. Returns the contact ID.
     *
     * A group key assigns the whole group (default policy "one character per group", #56): its members' own voices
     * only apply when the group is switched to per sender.
     */
    suspend fun assign(
        chat: ChatKey,
        packId: String,
        characterId: String,
    ): Long

    /** Removes [chat]'s voice; it keeps WhatsApp's tone again. No-op for a chat without a contact. */
    suspend fun unassign(chat: ChatKey)

    /**
     * Forgets [chat]'s sightings, which hides it from the recent senders strip until its next notification. Contacts
     * and voices are kept; a conversation that never became a contact disappears from the list too.
     */
    suspend fun dismissRecent(chat: ChatKey)
}

/** [ChatDirectory] over the Room repositories. */
@Singleton
class RoomChatDirectory
    @Inject
    constructor(
        private val contacts: ContactRepository,
        private val assignments: AssignmentRepository,
        private val seenSenders: SeenSenderRepository,
    ) : ChatDirectory {
        override fun observeChats(): Flow<List<Chat>> =
            combine(
                contacts.observeContacts(),
                assignments.observeAll(),
                seenSenders.observeRecent(SeenSenderDao.CAPACITY),
                ::mergeChats,
            )

        override suspend fun assign(
            chat: ChatKey,
            packId: String,
            characterId: String,
        ): Long {
            val seen = seenSenders.get(chat.packageName, chat.conversationKey)
            val contactId =
                contacts.findOrCreate(
                    packageName = chat.packageName,
                    key = chat.conversationKey,
                    displayName = seen?.displayName,
                    isGroup = seen?.isGroup == true || chat.isGroupKey,
                )
            assignments.assign(contactId, packId, characterId)
            return contactId
        }

        override suspend fun unassign(chat: ChatKey) {
            val contact = contacts.findByKey(chat.packageName, chat.conversationKey) ?: return
            assignments.unassign(contact.id)
        }

        override suspend fun dismissRecent(chat: ChatKey) = seenSenders.dismiss(chat.packageName, chat.conversationKey)
    }
