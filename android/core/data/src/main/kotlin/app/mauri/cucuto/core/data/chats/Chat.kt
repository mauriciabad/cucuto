package app.mauri.cucuto.core.data.chats

import app.mauri.cucuto.core.data.db.AssignmentEntity
import app.mauri.cucuto.core.data.db.ContactKeyEntity
import app.mauri.cucuto.core.data.db.ContactKeyKind
import app.mauri.cucuto.core.data.db.ContactWithKeys
import app.mauri.cucuto.core.data.db.GroupPolicy
import app.mauri.cucuto.core.data.db.SeenSenderEntity
import kotlinx.serialization.Serializable

/**
 * A WhatsApp conversation as the module reports it: the messenger package plus the conversation key (the alerting
 * notification's shortcut ID, `…@lid` for 1:1 chats and `…@g.us` for groups; ADR 0010). It is the stable identity
 * the app uses to address a chat in navigation and when assigning a voice.
 *
 * Serializable so it can live in Navigation 3 keys (the back stack is saved state, private to the app). Never log
 * it: the key identifies a person.
 */
@Serializable
data class ChatKey(
    val packageName: String,
    val conversationKey: String,
) {
    /** Whether the key is a WhatsApp group (`@g.us`). */
    val isGroupKey: Boolean get() = ContactKeyKind.classify(conversationKey) == ContactKeyKind.GROUP_JID

    companion object {
        /** WhatsApp's package. */
        const val WHATSAPP: String = "com.whatsapp"

        /** WhatsApp Business's package: same flows, separate keys (#21). */
        const val WHATSAPP_BUSINESS: String = "com.whatsapp.w4b"
    }

    /** Redacted form for logs and test names: only the key's JID domain. */
    override fun toString(): String = "ChatKey($packageName, …@${conversationKey.substringAfterLast('@', "?")})"
}

/** The character that speaks for a chat, by its stable `(packId, characterId)`. */
data class ChatVoice(
    val packId: String,
    val characterId: String,
)

/**
 * One row of the home screen: a contact the app knows, or a conversation the module has seen alerting that has no
 * contact yet. A contact that WhatsApp moved from one key to another (phone JID to `@lid`) is still one chat.
 *
 * @property key The key to address this chat by: the key of its newest sighting, else its newest messenger key.
 * @property contactId The contact, or null for a conversation that was only seen (created on first assignment).
 * @property displayName Name from the notification (or a rename), or null if the notification carried none.
 * @property voice The assigned character, or null when the chat keeps WhatsApp's tone.
 * @property lastSeenAt When the module last saw one of its notifications (ms since the epoch), or null if never.
 * @property messageCount Notifications seen across all its keys.
 * @property keys Every messenger key of the chat ([key] included).
 * @property groupPolicy Groups only: one voice for the whole group, or each sender's own voice (#20). A group that is
 *   not a contact yet has the default, [GroupPolicy.GROUP].
 */
data class Chat(
    val key: ChatKey,
    val contactId: Long?,
    val displayName: String?,
    val isGroup: Boolean,
    val voice: ChatVoice?,
    val lastSeenAt: Long?,
    val messageCount: Int,
    val keys: List<ChatKey> = listOf(key),
    val groupPolicy: GroupPolicy = GroupPolicy.GROUP,
) {
    /** Whether the chat lives in WhatsApp Business rather than WhatsApp; its keys and voices are its own (#21). */
    val isBusiness: Boolean get() = key.packageName == ChatKey.WHATSAPP_BUSINESS
}

/**
 * Joins contacts, their assignments and the recent senders feed into chats, most recently seen first (chats never
 * seen, such as restored contacts, go last, by name). Contacts with no messenger key (only a phone number) are not
 * chats and are left out.
 */
internal fun mergeChats(
    contacts: List<ContactWithKeys>,
    assignments: List<AssignmentEntity>,
    seen: List<SeenSenderEntity>,
): List<Chat> {
    val voices = assignments.associate { it.contactId to ChatVoice(it.packId, it.characterId) }
    val sightings = seen.associateBy { ChatKey(it.packageName, it.conversationKey) }
    val claimed = HashSet<ChatKey>()
    val chats = ArrayList<Chat>(contacts.size + seen.size)

    for (contact in contacts) {
        val keys = contact.keys.filter { it.packageName != ContactKeyEntity.NO_PACKAGE }
        if (keys.isEmpty()) continue
        val chatKeys = keys.map { ChatKey(it.packageName, it.value) }
        claimed += chatKeys
        val seenRows = chatKeys.mapNotNull { sightings[it] }
        val newest = seenRows.maxByOrNull { it.lastSeenAt }
        val key =
            newest?.let { ChatKey(it.packageName, it.conversationKey) }
                ?: keys.maxBy { it.lastSeenAt ?: Long.MIN_VALUE }.let { ChatKey(it.packageName, it.value) }
        chats +=
            Chat(
                key = key,
                contactId = contact.contact.id,
                displayName =
                    contact.contact.displayName
                        ?: newest?.displayName
                        ?: keys.firstNotNullOfOrNull { it.displayName },
                isGroup = contact.contact.isGroup,
                voice = voices[contact.contact.id],
                lastSeenAt = newest?.lastSeenAt,
                messageCount = seenRows.sumOf { it.messageCount },
                keys = chatKeys,
                groupPolicy = contact.contact.groupPolicy,
            )
    }
    for ((key, row) in sightings) {
        if (key in claimed) continue
        chats +=
            Chat(
                key = key,
                contactId = null,
                displayName = row.displayName,
                isGroup = row.isGroup || key.isGroupKey,
                voice = null,
                lastSeenAt = row.lastSeenAt,
                messageCount = row.messageCount,
            )
    }
    return chats.sortedWith(ChatOrder)
}

private val ChatOrder: Comparator<Chat> =
    compareByDescending<Chat> { it.lastSeenAt ?: Long.MIN_VALUE }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName.orEmpty() }
        .thenBy { it.key.packageName }
        .thenBy { it.key.conversationKey }
