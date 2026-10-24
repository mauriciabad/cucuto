package app.mauri.cucuto.xposed.hook

import android.app.Notification
import android.app.Person
import android.content.pm.ShortcutInfo
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.StatusBarNotification
import app.mauri.cucuto.xposed.route.ConversationKeys
import app.mauri.cucuto.xposed.route.NotificationSnapshot
import java.lang.reflect.Method

/**
 * Fail-soft reader for the framework-internal `com.android.server.notification.NotificationRecord`.
 *
 * Accessors are resolved once by name; any of them may be missing on a future build, and every read returns null
 * rather than throwing. Everything past `getSbn()` and `getShortcutInfo()` is public SDK API.
 *
 * MessagingStyle extras are read key by key with the expected types (the M0 spike's reader went through the hidden
 * `extractMessagingStyleFromNotification` and a wrongly typed `EXTRA_PEOPLE_LIST` read, which logged Bundle type
 * warnings and came back empty). A type mismatch on one key loses that key only. Of a message, only the sender
 * (`sender_person`, else the legacy `sender` name) is read, and only for groups.
 */
internal class RecordReader(
    recordClass: Class<*>,
) {
    private val getSbn: Method? = method(recordClass, "getSbn")
    private val getShortcutInfo: Method? = method(recordClass, "getShortcutInfo")

    /** Whether the one accessor the hook cannot work without was found. */
    val usable: Boolean get() = getSbn != null

    /** The posting package, or null. The cheapest read: done first so other packages cost nothing more. */
    fun packageOf(record: Any?): String? = sbn(record)?.packageName

    fun snapshot(record: Any): NotificationSnapshot? {
        val sbn = sbn(record) ?: return null
        val pkg = sbn.packageName ?: return null
        val notification: Notification? = sbn.notification
        val shortcut = invoke(getShortcutInfo, record) as? ShortcutInfo
        val extras: Bundle? = safe { notification?.extras }
        val channelId = safe { notification?.channelId }
        val shortcutId = safe { notification?.shortcutId }
        val shortcutInfoId = safe { shortcut?.id }
        val isGroupConversation =
            extras?.let { safe { it.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) } } ?: false
        // Minimum intrusion (#20): only a group's newest message is opened, and only for its sender. 1:1 chats never
        // need it (the shortcut ID is the person), and the message text is never read.
        val group = ConversationKeys.isGroup(channelId, shortcutId ?: shortcutInfoId, isGroupConversation)
        val lastMessage = if (group) extras?.let { lastMessage(it) } else null
        val person = lastMessage?.let { safe { it.getParcelable(KEY_SENDER_PERSON, Person::class.java) } }
        return NotificationSnapshot(
            packageName = pkg,
            channelId = channelId,
            shortcutId = shortcutId,
            shortcutInfoId = shortcutInfoId,
            shortcutLabel = safe { (shortcut?.shortLabel ?: shortcut?.longLabel)?.toString() },
            title = extras?.let { text(it, Notification.EXTRA_TITLE) },
            conversationTitle = extras?.let { text(it, Notification.EXTRA_CONVERSATION_TITLE) },
            isGroupConversation = isGroupConversation,
            senderKey = safe { person?.key },
            senderName = safe { person?.name?.toString() } ?: lastMessage?.let { text(it, KEY_SENDER) },
        )
    }

    /** The newest entry of `EXTRA_MESSAGES` (an array of Bundles written by `MessagingStyle.Message`). */
    private fun lastMessage(extras: Bundle): Bundle? =
        safe {
            val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java)
            messages?.lastOrNull { it is Bundle } as? Bundle
        }

    private fun text(
        bundle: Bundle,
        key: String,
    ): String? = safe { bundle.getCharSequence(key)?.toString() }

    private fun sbn(record: Any?): StatusBarNotification? = record?.let { invoke(getSbn, it) as? StatusBarNotification }

    private fun invoke(
        method: Method?,
        target: Any,
    ): Any? =
        try {
            method?.invoke(target)
        } catch (_: Throwable) {
            null
        }

    private inline fun <T> safe(block: () -> T?): T? =
        try {
            block()
        } catch (_: Throwable) {
            null
        }

    private companion object {
        // Notification.MessagingStyle.Message's Bundle keys (private constants in the framework).
        const val KEY_SENDER_PERSON = "sender_person"
        const val KEY_SENDER = "sender"

        fun method(
            owner: Class<*>,
            name: String,
        ): Method? {
            var c: Class<*>? = owner
            while (c != null) {
                try {
                    return c.getDeclaredMethod(name).apply { isAccessible = true }
                } catch (_: Throwable) {
                    c = c.superclass
                }
            }
            return null
        }
    }
}
