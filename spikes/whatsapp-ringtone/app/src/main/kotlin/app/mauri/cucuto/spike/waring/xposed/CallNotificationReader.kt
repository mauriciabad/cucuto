package app.mauri.cucuto.spike.waring.xposed

import android.app.Notification
import android.app.NotificationManager
import android.app.Person
import android.content.Context

/**
 * Finds WhatsApp's own incoming-call notification through the public `NotificationManager.getActiveNotifications()`,
 * which returns only the calling app's notifications. Runs inside WhatsApp's process, so these are WhatsApp's own.
 *
 * Privacy: the category is checked first, and only notifications with category `call` and CallStyle type
 * "incoming" are read further, for three values: the caller's display name, the post time and WhatsApp's
 * `isGroupCall` flag. Message notifications are skipped without reading any extra; `EXTRA_TEXT`, `EXTRA_MESSAGES` and
 * the like are never touched.
 *
 * One Binder call (to `system_server`'s notification service). Never throws: any failure reads as "no call".
 */
class CallNotificationReader(
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun read(context: Context): IncomingCall? =
        try {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager
                ?.activeNotifications
                ?.asSequence()
                ?.filter { it.notification?.category == Notification.CATEGORY_CALL }
                ?.filter { it.notification.extras?.getInt(Notification.EXTRA_CALL_TYPE) == INCOMING }
                ?.maxByOrNull { it.postTime }
                ?.let { record ->
                    val extras = record.notification.extras
                    val person = extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)
                    IncomingCall(
                        name = person?.name?.toString() ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                        ageMillis = now() - record.postTime,
                        isGroupCall = extras.getBoolean(WHATSAPP_GROUP_CALL_EXTRA, false),
                    )
                }
        } catch (_: Throwable) {
            null
        }

    private companion object {
        /** `Notification.CallStyle.CALL_TYPE_INCOMING`. */
        const val INCOMING = 1

        /** WhatsApp's own boolean extra on its call notification (seen in 2.26.42.12). */
        const val WHATSAPP_GROUP_CALL_EXTRA = "isGroupCall"
    }
}
