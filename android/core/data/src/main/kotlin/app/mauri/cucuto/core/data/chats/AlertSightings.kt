package app.mauri.cucuto.core.data.chats

import app.mauri.cucuto.core.data.db.ContactKeyKind
import app.mauri.cucuto.core.data.db.GroupPolicy
import app.mauri.cucuto.core.data.repository.ContactRepository
import app.mauri.cucuto.core.data.repository.SeenSenderRepository
import app.mauri.cucuto.core.routing.ContactKeys
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns one alert the module reported into "recent senders" sightings.
 *
 * Every alerting conversation is recorded. In a group switched to per sender (#20), the message's sender is also
 * recorded as a chat of its own, so the user can give a voice to a member who never wrote to them directly. This
 * only happens when the sender's `Person` key is a WhatsApp person JID (`…@lid` or `…@s.whatsapp.net`): that is
 * exactly the key their 1:1 chat has, so the voice also plays there, and a key of any other shape is not a chat. On
 * Signal and Molly, a sender who is a saved contact has the contact key `contact:<lookupKey>` (ADR 0081), which is
 * their 1:1 key too.
 * Groups with the default policy add nobody, so busy groups do not flood the list with strangers.
 *
 * A 1:1 chat that belongs to no contact may be someone added from Android's contacts, waiting for this first message:
 * [ContactChats.linkWaiting] links it when its name matches exactly and uniquely (ADR 0065). This message still played
 * WhatsApp's tone; the voice plays from the next one.
 */
@Singleton
class AlertSightings
    @Inject
    constructor(
        private val seenSenders: SeenSenderRepository,
        private val contacts: ContactRepository,
        private val linker: ContactChats,
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
            if (!isGroup) {
                if (contacts.findByKey(packageName, conversationKey) == null) linker.linkWaiting()
                return
            }
            if (senderKey == null || senderKey == conversationKey || !isPersonJid(senderKey)) return
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

                // Signal and Molly: a sender who is a saved contact is keyed by that contact, as their 1:1 chat is.
                else -> ContactKeys.lookupKeyOf(key) != null
            }
    }
