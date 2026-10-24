package app.mauri.cucuto.core.data.chats

import app.mauri.cucuto.core.data.db.ContactKeyKind
import app.mauri.cucuto.core.data.db.GroupPolicy
import app.mauri.cucuto.core.data.repository.ContactRepository
import app.mauri.cucuto.core.data.repository.SeenSenderRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns one alert the module reported into "recent senders" sightings.
 *
 * Every alerting conversation is recorded. In a group switched to per sender (#20), the message's sender is also
 * recorded as a chat of its own, so the user can give a voice to a member who never wrote to them directly. This
 * only happens when the sender's `Person` key is a WhatsApp person JID (`…@lid` or `…@s.whatsapp.net`): that is
 * exactly the key their 1:1 chat has, so the voice also plays there, and a key of any other shape is not a chat.
 * Groups with the default policy add nobody, so busy groups do not flood the list with strangers.
 */
@Singleton
class AlertSightings
    @Inject
    constructor(
        private val seenSenders: SeenSenderRepository,
        private val contacts: ContactRepository,
    ) {
        suspend fun record(
            packageName: String,
            conversationKey: String,
            displayName: String?,
            isGroup: Boolean,
            seenAt: Long,
            senderKey: String?,
            senderName: String?,
        ) {
            seenSenders.record(
                packageName = packageName,
                conversationKey = conversationKey,
                displayName = displayName,
                isGroup = isGroup,
                seenAt = seenAt,
                senderKey = senderKey,
                senderName = senderName,
            )
            if (!isGroup || senderKey == null || senderKey == conversationKey || !isPersonJid(senderKey)) return
            val group = contacts.findByKey(packageName, conversationKey) ?: return
            if (!group.isGroup || group.groupPolicy != GroupPolicy.PER_SENDER) return
            seenSenders.record(
                packageName = packageName,
                conversationKey = senderKey,
                displayName = senderName,
                isGroup = false,
                seenAt = seenAt,
            )
        }

        private fun isPersonJid(key: String): Boolean =
            when (ContactKeyKind.classify(key)) {
                ContactKeyKind.LID, ContactKeyKind.PHONE_JID -> true
                else -> false
            }
    }
