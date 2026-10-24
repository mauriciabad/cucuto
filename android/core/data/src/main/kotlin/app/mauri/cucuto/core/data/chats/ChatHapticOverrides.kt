package app.mauri.cucuto.core.data.chats

import app.mauri.cucuto.core.data.repository.AssignmentRepository
import app.mauri.cucuto.core.data.repository.ContactRepository
import app.mauri.cucuto.core.data.routing.HapticOverride
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A chat's voice and its per-contact haptic override.
 *
 * @property contactId The contact the chat belongs to.
 * @property voice The assigned character; its pack haptic applies when [override] is [HapticOverride.PackDefault].
 */
data class ChatHaptic(
    val contactId: Long,
    val voice: ChatVoice,
    val override: HapticOverride,
)

/**
 * Per-contact haptic overrides (#33), addressed by chat like the rest of the UI. An override belongs to the
 * contact's assignment, so a chat without a voice has none. Every change republishes the routing table (the publisher
 * observes the database), so the module plays the new vibration with the next message.
 */
interface ChatHapticOverrides {
    /** [chat]'s voice and override, or null while it has no voice. */
    fun observe(chat: ChatKey): Flow<ChatHaptic?>

    /** Stores [override] for [chat]'s contact. Returns false (nothing stored) when the chat has no voice. */
    suspend fun set(
        chat: ChatKey,
        override: HapticOverride,
    ): Boolean
}

/** [ChatHapticOverrides] over the Room repositories. */
@Singleton
class RoomChatHapticOverrides
    @Inject
    constructor(
        private val contacts: ContactRepository,
        private val assignments: AssignmentRepository,
    ) : ChatHapticOverrides {
        // Assignments are few (one per contact with a voice), and observing all of them also catches the first
        // assignment of a chat that had no contact yet when the screen opened.
        override fun observe(chat: ChatKey): Flow<ChatHaptic?> =
            assignments
                .observeAll()
                .map { all ->
                    val contactId = contacts.findByKey(chat.packageName, chat.conversationKey)?.id
                    all.firstOrNull { it.contactId == contactId }?.let {
                        ChatHaptic(
                            contactId = it.contactId,
                            voice = ChatVoice(it.packId, it.characterId),
                            override = HapticOverride.parse(it.hapticOverride),
                        )
                    }
                }.distinctUntilChanged()

        override suspend fun set(
            chat: ChatKey,
            override: HapticOverride,
        ): Boolean {
            val contact = contacts.findByKey(chat.packageName, chat.conversationKey) ?: return false
            return assignments.setHapticOverride(contact.id, override.toJson())
        }
    }
