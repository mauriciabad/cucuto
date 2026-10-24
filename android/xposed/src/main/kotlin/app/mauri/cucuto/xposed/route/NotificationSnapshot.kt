package app.mauri.cucuto.xposed.route

/**
 * What the hook reads off one alerting `NotificationRecord`, as plain values. Reading happens once per alert in
 * `RecordReader` (reflection, fail-soft); everything after that, key extraction and routing, works on this snapshot
 * and is unit-tested on the JVM.
 *
 * Every field is optional except [packageName]: a missing value only narrows what can be matched.
 *
 * @property channelId The notification channel, e.g. `individual_chat_defaults_5` or `group_chat_defaults_6`.
 * @property shortcutId `Notification.getShortcutId()`. On WhatsApp's alerting record (the group summary) this is the
 *   conversation that just alerted: `…@lid` for 1:1 chats, `…@g.us` for groups (M0 supervised run).
 * @property shortcutInfoId `NotificationRecord.getShortcutInfo().getId()`, the resolved shortcut. Normally equal to
 *   [shortcutId]; read as a second source.
 * @property shortcutLabel The resolved shortcut's label: the chat's display name.
 * @property title `Notification.EXTRA_TITLE`.
 * @property conversationTitle `Notification.EXTRA_CONVERSATION_TITLE`.
 * @property isGroupConversation `Notification.EXTRA_IS_GROUP_CONVERSATION`.
 * @property senderKey `Person.getKey()` of the newest message's sender (MessagingStyle), when present.
 * @property senderName `Person.getName()` (or the legacy sender text) of the newest message.
 * @property simulatePackage Cucuto's own simulate notifications only ([Targets.SIMULATE_CHANNEL]): the messenger
 *   package of the chat to simulate ([Targets.EXTRA_SIMULATE_PACKAGE]). Never read from any other app.
 * @property simulateKey Likewise: the chat's conversation key ([Targets.EXTRA_SIMULATE_KEY]).
 */
data class NotificationSnapshot(
    val packageName: String,
    val channelId: String? = null,
    val shortcutId: String? = null,
    val shortcutInfoId: String? = null,
    val shortcutLabel: String? = null,
    val title: String? = null,
    val conversationTitle: String? = null,
    val isGroupConversation: Boolean = false,
    val senderKey: String? = null,
    val senderName: String? = null,
    val simulatePackage: String? = null,
    val simulateKey: String? = null,
)

/**
 * The conversation identity Cucuto derives from a [NotificationSnapshot]. The stable contact key is
 * `package + conversation key` (brief, "Contact identity").
 *
 * @property keys Keys to look up in the routing table, strongest first. Empty when the notification carries nothing
 *   usable.
 * @property isGroup Whether the conversation is a group chat.
 * @property displayName Best human label for the "recent senders" feed. Never logged.
 * @property senderKey For groups only: the newest message's sender, for per-sender groups.
 */
data class ConversationIdentity(
    val keys: List<String>,
    val isGroup: Boolean,
    val displayName: String?,
    val senderKey: String?,
    val senderName: String?,
) {
    /** The key reported to the app when nothing matched: the strongest one. */
    val primaryKey: String? get() = keys.firstOrNull()
}

/** Turns a [NotificationSnapshot] into a [ConversationIdentity]. Pure and allocation-light. */
object ConversationKeys {
    /** WhatsApp's channel for 1:1 chats; the numeric suffix changes, so match the prefix (M0). */
    const val INDIVIDUAL_CHANNEL_PREFIX: String = "individual_chat_defaults_"

    /** WhatsApp's channel for group chats. */
    const val GROUP_CHANNEL_PREFIX: String = "group_chat_defaults_"

    /** JID domain of WhatsApp groups. */
    const val GROUP_JID_SUFFIX: String = "@g.us"

    /** Longest key accepted; anything longer is not a WhatsApp JID or a chat title and is ignored. */
    const val MAX_KEY_LENGTH: Int = 256

    fun identify(snapshot: NotificationSnapshot): ConversationIdentity {
        val keys = candidateKeys(snapshot)
        val group = isGroup(snapshot, keys.firstOrNull())
        return ConversationIdentity(
            keys = keys,
            isGroup = group,
            displayName = displayName(snapshot),
            senderKey = if (group) clean(snapshot.senderKey) else null,
            senderName = if (group) clean(snapshot.senderName) else null,
        )
    }

    /**
     * Shortcut IDs first (the JID). Only when the notification has no shortcut at all does the conversation title
     * stand in, as the brief allows: it is a display name, so it matches only when the app stored it as a key.
     */
    fun candidateKeys(snapshot: NotificationSnapshot): List<String> {
        val shortcut = clean(snapshot.shortcutId)
        val resolved = clean(snapshot.shortcutInfoId)
        if (shortcut != null || resolved != null) {
            return when {
                shortcut == null -> listOf(resolved!!)
                resolved == null || resolved == shortcut -> listOf(shortcut)
                else -> listOf(shortcut, resolved)
            }
        }
        val conversationTitle = clean(snapshot.conversationTitle)
        if (conversationTitle != null) return listOf(conversationTitle)
        val title = clean(snapshot.title)
        return if (title != null) listOf(title) else emptyList()
    }

    /** The channel is the most reliable signal on WhatsApp, then the JID domain, then the MessagingStyle flag. */
    fun isGroup(
        snapshot: NotificationSnapshot,
        key: String?,
    ): Boolean {
        val channel = snapshot.channelId
        if (channel != null) {
            if (channel.startsWith(GROUP_CHANNEL_PREFIX)) return true
            if (channel.startsWith(INDIVIDUAL_CHANNEL_PREFIX)) return false
        }
        if (key != null && key.endsWith(GROUP_JID_SUFFIX)) return true
        return snapshot.isGroupConversation
    }

    fun displayName(snapshot: NotificationSnapshot): String? =
        clean(snapshot.shortcutLabel) ?: clean(snapshot.conversationTitle) ?: clean(snapshot.title)

    private fun clean(value: String?): String? {
        if (value == null) return null
        val trimmed = value.trim()
        return if (trimmed.isEmpty() || trimmed.length > MAX_KEY_LENGTH) null else trimmed
    }
}
