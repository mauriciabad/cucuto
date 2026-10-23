package app.mauri.cucuto.spike.xposed

import android.app.Notification
import android.content.pm.ShortcutInfo
import android.net.Uri
import android.os.VibrationEffect
import android.service.notification.StatusBarNotification
import java.lang.reflect.Method

/**
 * Reflective, fail-soft reader for `com.android.server.notification.NotificationRecord`.
 *
 * `NotificationRecord` is framework-internal, so every accessor is resolved by name once and may
 * legitimately be absent on a future build. Every read returns null rather than throwing; the
 * caller logs what it got.
 *
 * Everything this produces is already redacted — see [Redact].
 */
internal class RecordReader(recordClass: Class<*>) {

    private val getSbn: Method? = method(recordClass, "getSbn")
    private val getChannel: Method? = method(recordClass, "getChannel")
    private val getSound: Method? = method(recordClass, "getSound")
    private val getVibration: Method? = method(recordClass, "getVibration")
    private val getShortcutInfo: Method? = method(recordClass, "getShortcutInfo")
    private val isConversation: Method? = method(recordClass, "isConversation")
    private val getImportance: Method? = method(recordClass, "getImportance")
    private val getKey: Method? = method(recordClass, "getKey")

    private fun method(c: Class<*>, name: String): Method? = try {
        c.getDeclaredMethod(name).apply { isAccessible = true }
    } catch (_: Throwable) {
        null
    }

    private fun <T> read(m: Method?, target: Any): T? = try {
        @Suppress("UNCHECKED_CAST")
        m?.invoke(target) as T?
    } catch (_: Throwable) {
        null
    }

    fun packageOf(record: Any): String? = try {
        read<StatusBarNotification>(getSbn, record)?.packageName
    } catch (_: Throwable) {
        null
    }

    /**
     * One redacted line answering the issue #10 questions for a single record.
     */
    fun describe(record: Any): String = try {
        val sb = StringBuilder()
        val sbn = read<StatusBarNotification>(getSbn, record)
        val n: Notification? = sbn?.notification

        sb.append("key=").append(Redact.token(read<String>(getKey, record)))
        sb.append(" importance=").append(read<Int>(getImportance, record))
        sb.append(" conversation=").append(read<Boolean>(isConversation, record))

        // --- channel ------------------------------------------------------
        val channel = read<Any>(getChannel, record)
        val channelId = channel?.let {
            try {
                it.javaClass.getMethod("getId").invoke(it) as? String
            } catch (_: Throwable) {
                null
            }
        }
        sb.append(" channel=").append(Redact.channelId(channelId))

        // --- alert payload the framework picked ---------------------------
        sb.append(" sound=").append(Redact.uri(read<Uri>(getSound, record)))
        sb.append(" vibration=").append(read<VibrationEffect>(getVibration, record)?.let { summarise(it) } ?: "<null>")

        // --- grouping (issue #10: does the child or the summary alert?) ----
        if (sbn != null) {
            sb.append(" isGroup=").append(sbn.isGroup)
            sb.append(" groupKey=").append(Redact.token(sbn.groupKey))
        }
        if (n != null) {
            sb.append(" isSummary=").append(n.flags and Notification.FLAG_GROUP_SUMMARY != 0)
            sb.append(" groupAlertBehavior=").append(groupAlert(n.groupAlertBehavior))
            sb.append(" onlyAlertOnce=").append(n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            sb.append(" category=").append(n.category ?: "<null>")
        }

        // --- contact key candidates (issue #10) ---------------------------
        // The shortcut ID is the strongest candidate for cucuto's per-contact key: WhatsApp sets
        // it to the chat JID for conversation notifications.
        val shortcutFromNotification = n?.shortcutId
        val shortcutFromRecord = read<ShortcutInfo>(getShortcutInfo, record)?.id
        sb.append(" shortcutId(notif)=").append(Redact.jid(shortcutFromNotification))
        sb.append(" shortcutId(record)=").append(Redact.jid(shortcutFromRecord))

        // --- MessagingStyle -----------------------------------------------
        if (n != null) {
            // extractMessagingStyleFromNotification is @hide, so it is not in the public SDK even
            // though it exists in the framework we are running inside. Reflection it is.
            val style = extractMessagingStyle(n)
            if (style == null) {
                sb.append(" messagingStyle=absent")
            } else {
                sb.append(" messagingStyle=present")
                sb.append(" isGroupConversation=").append(style.isGroupConversation)
                sb.append(" conversationTitle=").append(Redact.text(style.conversationTitle))
                val last = style.messages.lastOrNull()
                val person = last?.senderPerson
                sb.append(" lastSender.name=").append(Redact.text(person?.name))
                sb.append(" lastSender.key=").append(Redact.jid(person?.key))
                sb.append(" lastSender.uri=").append(Redact.jid(person?.uri))
                sb.append(" lastSender.isBot=").append(person?.isBot)
                sb.append(" messageCount=").append(style.messages.size)
            }
            // Notification.EXTRA_PEOPLE_LIST is the other identity carrier worth checking.
            val people = try {
                n.extras?.getParcelableArray(Notification.EXTRA_PEOPLE_LIST, android.app.Person::class.java)
            } catch (_: Throwable) {
                null
            }
            sb.append(" peopleList=").append(
                people?.joinToString(",", "[", "]") { Redact.jid((it as? android.app.Person)?.key ?: (it as? android.app.Person)?.uri) }
                    ?: "<null>",
            )
        }
        sb.toString()
    } catch (t: Throwable) {
        "describe-failed: ${t.javaClass.simpleName}"
    }

    private fun groupAlert(value: Int): String = when (value) {
        Notification.GROUP_ALERT_ALL -> "ALL"
        Notification.GROUP_ALERT_SUMMARY -> "SUMMARY"
        Notification.GROUP_ALERT_CHILDREN -> "CHILDREN"
        else -> "unknown($value)"
    }

    /** Shape only — a vibration pattern can encode nothing personal, but keep it short. */
    private fun summarise(effect: VibrationEffect): String {
        // VibrationEffect.getDuration() is @hide in the public SDK.
        val duration = try {
            VibrationEffect::class.java.getMethod("getDuration").invoke(effect) as? Long ?: -1L
        } catch (_: Throwable) {
            -1L
        }
        return "${effect.javaClass.simpleName}(duration=$duration)"
    }

    /**
     * Calls the hidden `Notification.MessagingStyle.extractMessagingStyleFromNotification`.
     * Returns null on any build where it is gone, which is the behaviour we want: the line simply
     * reports `messagingStyle=absent` rather than failing the hook.
     */
    private fun extractMessagingStyle(n: Notification): Notification.MessagingStyle? = try {
        Notification.MessagingStyle::class.java
            .getMethod("extractMessagingStyleFromNotification", Notification::class.java)
            .invoke(null, n) as? Notification.MessagingStyle
    } catch (_: Throwable) {
        null
    }
}
