package app.mauri.cucuto.core.data.chats

import app.mauri.cucuto.core.data.db.AssignmentEntity
import app.mauri.cucuto.core.data.db.ContactKeyEntity
import app.mauri.cucuto.core.data.db.ContactKeyKind
import app.mauri.cucuto.core.data.db.ContactWithKeys
import app.mauri.cucuto.core.data.db.GroupPolicy
import app.mauri.cucuto.core.data.db.PhoneRingtoneEntity
import app.mauri.cucuto.core.data.db.RingtoneChoice
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
 * @property awaitingLink Added from Android's contacts and waiting for its first message: its only keys are phone
 *   JIDs, which never reach the module, so its voice plays once that first message links its `@lid` (ADR 0065).
 * @property linkCandidates A recent sender that is not a contact yet, whose name matches chats waiting for their first
 *   message but not uniquely enough to link by itself: the user picks which one it is ([ChatLinkRules]).
 * @property phoneNumbers E.164 numbers of the chat's contact (from its Android contact or phone JIDs), to tell people
 *   with the same name apart.
 * @property ringtone The stored phone ringtone choice, or null when none is stored (ADR 0073); [ringingVoice] says
 *   which character rings.
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
    val awaitingLink: Boolean = false,
    val linkCandidates: List<LinkCandidate> = emptyList(),
    val phoneNumbers: List<String> = emptyList(),
    val ringtone: ChatRingtone? = null,
) {
    /** Whether the chat lives in WhatsApp Business rather than WhatsApp; its keys and voices are its own (#21). */
    val isBusiness: Boolean get() = key.packageName == ChatKey.WHATSAPP_BUSINESS

    /**
     * The character that would ring for calls: the ringtone's own, else the message voice while the ringtone follows
     * it (nothing stored yet, ADR 0063). Null for "Android default" and "Leave as is" (ADR 0073). Whether that
     * character has ringtones is the caller's to check.
     */
    val ringingVoice: ChatVoice?
        get() =
            when (val r = ringtone) {
                null -> voice
                is ChatRingtone.Character -> r.own ?: voice
                ChatRingtone.AndroidDefault, ChatRingtone.Unchanged -> null
            }
}

/** A chat's stored phone ringtone (ADR 0063, ADR 0073). */
sealed interface ChatRingtone {
    /**
     * A pack character: [own] is the one stored (picked, or kept while the voice has no ringtones), null for the
     * message voice's. [followsVoice] is false once the user picked it.
     */
    data class Character(
        val own: ChatVoice?,
        val followsVoice: Boolean,
    ) : ChatRingtone

    /** The phone's default ringtone. */
    data object AndroidDefault : ChatRingtone

    /** Leave as is: Cucuto doesn't touch the contact's ringtone. */
    data object Unchanged : ChatRingtone

    companion object {
        /** The choice stored in [row]. */
        fun of(row: PhoneRingtoneEntity): ChatRingtone =
            when (row.choice) {
                RingtoneChoice.ANDROID_DEFAULT -> {
                    AndroidDefault
                }

                RingtoneChoice.UNCHANGED -> {
                    Unchanged
                }

                RingtoneChoice.CHARACTER -> {
                    val own =
                        if (row.packId != null && row.characterId != null) {
                            ChatVoice(row.packId, row.characterId)
                        } else {
                            null
                        }
                    Character(own, row.followsVoice || own == null)
                }
            }
    }
}

/**
 * A chat waiting for its first message that a recent sender may be ([Chat.linkCandidates]).
 *
 * @property key The waiting chat's key (a phone JID).
 */
data class LinkCandidate(
    val contactId: Long,
    val key: ChatKey,
    val displayName: String?,
    val phoneNumbers: List<String>,
    val voice: ChatVoice?,
)

/** Whether [contact] is a chat waiting for its first message ([Chat.awaitingLink]). */
internal fun isWaiting(contact: ContactWithKeys): Boolean {
    if (contact.contact.isGroup) return false
    val keys = contact.keys.filter { it.packageName != ContactKeyEntity.NO_PACKAGE }
    return keys.isNotEmpty() && keys.all { it.kind == ContactKeyKind.PHONE_JID }
}

/** [contact] as a [WaitingChat], or null when it is not waiting. */
internal fun waitingChat(contact: ContactWithKeys): WaitingChat? =
    if (!isWaiting(contact)) {
        null
    } else {
        WaitingChat(
            contactId = contact.contact.id,
            packageName = contact.keys.first { it.packageName != ContactKeyEntity.NO_PACKAGE }.packageName,
            name = contact.contact.displayName,
            lookupKey = contact.contact.androidLookupKey,
        )
    }

/** E.164 numbers of [contact]: its phone number keys, else its phone JIDs. */
internal fun phoneNumbersOf(contact: ContactWithKeys): List<String> {
    val numbers = contact.keys.filter { it.kind == ContactKeyKind.PHONE_NUMBER }.map { it.value }
    val fromJids =
        contact.keys
            .filter { it.kind == ContactKeyKind.PHONE_JID }
            .map { "+" + it.value.substringBefore('@') }
            .filter { ContactKeyKind.classify(it) == ContactKeyKind.PHONE_NUMBER }
    return (numbers + fromJids).distinct()
}

/** Whether a sighting is a 1:1 chat a waiting chat could be (not a group). */
internal fun SeenSenderEntity.isPerson(): Boolean =
    !isGroup && ContactKeyKind.classify(conversationKey) != ContactKeyKind.GROUP_JID

/**
 * Joins contacts, their assignments and the recent senders feed into chats, most recently seen first (chats never
 * seen, such as restored contacts and chats waiting for their first message, go last, by name). Contacts with no
 * messenger key (only a phone number) are not chats and are left out. Recent senders that match waiting chats by name
 * carry them as [Chat.linkCandidates] ([ChatLinkRules], without the namesake check: whatever has not linked by itself
 * is asked).
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
                awaitingLink = isWaiting(contact),
                phoneNumbers = phoneNumbersOf(contact),
            )
    }
    val waiting = contacts.mapNotNull(::waitingChat)
    val questions =
        ChatLinkRules
            .plan(
                waiting = waiting,
                sightings =
                    sightings
                        .filter { (key, row) -> key !in claimed && row.isPerson() }
                        .map { (key, row) -> UnlinkedSighting(key, row.displayName) },
                namesakes = null,
            ).questions
    val candidates =
        if (questions.isEmpty()) {
            emptyMap()
        } else {
            contacts.filter(::isWaiting).associate { contact ->
                contact.contact.id to
                    LinkCandidate(
                        contactId = contact.contact.id,
                        key =
                            contact.keys.first { it.kind == ContactKeyKind.PHONE_JID }.let {
                                ChatKey(
                                    it.packageName,
                                    it.value,
                                )
                            },
                        displayName = contact.contact.displayName,
                        phoneNumbers = phoneNumbersOf(contact),
                        voice = voices[contact.contact.id],
                    )
            }
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
                linkCandidates = questions[key].orEmpty().mapNotNull { candidates[it] },
            )
    }
    return chats.sortedWith(ChatOrder)
}

private val ChatOrder: Comparator<Chat> =
    compareByDescending<Chat> { it.lastSeenAt ?: Long.MIN_VALUE }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName.orEmpty() }
        .thenBy { it.key.packageName }
        .thenBy { it.key.conversationKey }
