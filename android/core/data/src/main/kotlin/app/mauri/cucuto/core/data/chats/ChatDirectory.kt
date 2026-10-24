package app.mauri.cucuto.core.data.chats

import app.mauri.cucuto.core.data.db.GroupPolicy
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
     *
     * The voice and the phone ringtone are independent (ADR 0063): a ringtone the user never picked follows the new
     * voice when it has ringtones, or keeps what rang before; a picked one stays ([PhoneRingtoneFollow]).
     */
    suspend fun assign(
        chat: ChatKey,
        packId: String,
        characterId: String,
    ): Long

    /**
     * Removes [chat]'s voice; it keeps WhatsApp's tone again. No-op for a chat without a contact. Its phone ringtone
     * stays (ADR 0063).
     */
    suspend fun unassign(chat: ChatKey)

    /**
     * [chat]'s contact, created (named after its notification) when the conversation was only seen, so it can get a
     * phone ringtone before it has a voice (ADR 0063). Returns the contact ID.
     */
    suspend fun ensureContact(chat: ChatKey): Long

    /**
     * Sets how the group [chat] picks its voice (#20): one voice for the whole group (the default), or each
     * sender's own voice, with the group's voice (if any) for senders without one. A group that was only seen
     * becomes a contact first, so a per-sender group needs no voice of its own. Returns the contact ID.
     */
    suspend fun setGroupPolicy(
        chat: ChatKey,
        policy: GroupPolicy,
    ): Long

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
        private val ringtoneFollow: PhoneRingtoneFollow,
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
            val contactId = ensureContact(chat)
            val old = assignments.get(contactId)?.let { ChatVoice(it.packId, it.characterId) }
            assignments.assign(contactId, packId, characterId)
            ringtoneFollow.voiceChanged(contactId, old, ChatVoice(packId, characterId))
            return contactId
        }

        override suspend fun unassign(chat: ChatKey) {
            val contact = contacts.findByKey(chat.packageName, chat.conversationKey) ?: return
            val old = assignments.get(contact.id)?.let { ChatVoice(it.packId, it.characterId) }
            assignments.unassign(contact.id)
            ringtoneFollow.voiceChanged(contact.id, old, null)
        }

        override suspend fun ensureContact(chat: ChatKey): Long {
            val seen = seenSenders.get(chat.packageName, chat.conversationKey)
            return contacts.findOrCreate(
                packageName = chat.packageName,
                key = chat.conversationKey,
                displayName = seen?.displayName,
                isGroup = seen?.isGroup == true || chat.isGroupKey,
            )
        }

        override suspend fun setGroupPolicy(
            chat: ChatKey,
            policy: GroupPolicy,
        ): Long {
            val contactId = ensureContact(chat)
            contacts.setGroupPolicy(contactId, policy)
            return contactId
        }

        override suspend fun dismissRecent(chat: ChatKey) = seenSenders.dismiss(chat.packageName, chat.conversationKey)
    }
