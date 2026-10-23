package app.mauri.cucuto.spike.debugapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Person
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri

/**
 * Posts MessagingStyle notifications shaped like WhatsApp's, so the alerting path can be exercised
 * without waiting for (or sending) a real message (issue #11).
 *
 * The shapes we imitate, taken from what WhatsApp actually posts:
 *  - a conversation notification: MessagingStyle + a shortcut ID that is the chat JID, with the
 *    sender carried as a [Person] whose key is the JID;
 *  - a group chat: the same, with `isGroupConversation = true`, a `@g.us`-style JID and a
 *    per-sender [Person] on each message;
 *  - a group of notifications: children plus a summary, so we can see which record alerts.
 *
 * The JIDs here are obviously fake (`15550000001@s.whatsapp.net`) and belong to no one.
 */
object NotificationFactory {

    const val CHANNEL_ID = "spike_messages"
    private const val CHANNEL_NAME = "Spike messages"

    const val GROUP_KEY = "app.mauri.cucuto.spike.GROUP"

    const val ID_ONE_TO_ONE = 1001
    const val ID_GROUP_CHILD_A = 2001
    const val ID_GROUP_CHILD_B = 2002
    const val ID_GROUP_SUMMARY = 2000
    const val ID_BURST_BASE = 3000

    /** Fake identities. These are reserved/unroutable test numbers, not real contacts. */
    private const val JID_ALICE = "15550000001@s.whatsapp.net"
    private const val JID_BOB = "15550000002@s.whatsapp.net"
    private const val JID_LID = "19876543210987@lid"
    private const val JID_GROUP = "120363000000000001@g.us"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return

        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
            description = "cucuto M0 spike — imitation WhatsApp messages"
            // A sound and a vibration must be set, otherwise the framework never reaches
            // playSound()/playVibration() and there is nothing for the hook to swap.
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 250, 150, 250)
        }
        nm.createNotificationChannel(channel)
    }

    /**
     * Pushes a conversation shortcut, which is what makes Android treat the notification as a
     * conversation. WhatsApp uses the chat JID as the shortcut ID; we do the same so the hook sees
     * the same shape.
     */
    private fun pushShortcut(context: Context, jid: String, label: String) {
        val sm = context.getSystemService(ShortcutManager::class.java) ?: return
        val shortcut = ShortcutInfo.Builder(context, jid)
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(Icon.createWithResource(context, android.R.drawable.sym_action_chat))
            .setIntent(Intent(Intent.ACTION_VIEW).setPackage(context.packageName))
            .setLongLived(true)
            .setPerson(personFor(jid, label))
            .build()
        runCatching { sm.pushDynamicShortcut(shortcut) }
    }

    private fun personFor(jid: String, name: String): Person =
        Person.Builder()
            .setKey(jid)
            .setName(name)
            // No setUri: WhatsApp holds READ_CONTACTS and a contacts URI; the spike does not, and the
            // framework rejects an ungranted Person URI with a SecurityException.
            .setBot(false)
            .build()

    private fun baseBuilder(context: Context): Notification.Builder =
        Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)

    /** A 1:1 chat notification. */
    fun postOneToOne(context: Context, onlyAlertOnce: Boolean = false, lid: Boolean = false) {
        ensureChannel(context)
        val jid = if (lid) JID_LID else JID_ALICE
        val label = if (lid) "Spike LID contact" else "Spike Alice"
        pushShortcut(context, jid, label)

        val person = personFor(jid, label)
        val style = Notification.MessagingStyle(person)
            .setGroupConversation(false)
            .addMessage("Imitation message for the M0 spike", System.currentTimeMillis(), person)

        val n = baseBuilder(context)
            .setStyle(style)
            .setShortcutId(jid)
            .setOnlyAlertOnce(onlyAlertOnce)
            .addPerson(person)
            .build()

        context.getSystemService(NotificationManager::class.java).notify(ID_ONE_TO_ONE, n)
    }

    /**
     * A group chat posted the way a messaging app does it: two children and a summary, so M0 can
     * see which record actually reaches the alerting stage.
     */
    fun postGroupConversation(context: Context, groupAlertBehavior: Int = Notification.GROUP_ALERT_CHILDREN) {
        ensureChannel(context)
        pushShortcut(context, JID_GROUP, "Spike group")

        val self = personFor("self@s.whatsapp.net", "Me")
        val alice = personFor(JID_ALICE, "Spike Alice")
        val bob = personFor(JID_BOB, "Spike Bob")
        val nm = context.getSystemService(NotificationManager::class.java)

        val style = Notification.MessagingStyle(self)
            .setGroupConversation(true)
            .setConversationTitle("Spike group")
            .addMessage("First imitation group message", System.currentTimeMillis() - 1000, alice)
            .addMessage("Second imitation group message", System.currentTimeMillis(), bob)

        val child = baseBuilder(context)
            .setStyle(style)
            .setShortcutId(JID_GROUP)
            .setGroup(GROUP_KEY)
            .setGroupAlertBehavior(groupAlertBehavior)
            .addPerson(alice)
            .addPerson(bob)
            .build()

        val summary = baseBuilder(context)
            .setStyle(Notification.MessagingStyle(self).setGroupConversation(true).setConversationTitle("Spike group"))
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setGroupAlertBehavior(groupAlertBehavior)
            .build()

        nm.notify(ID_GROUP_CHILD_A, child)
        nm.notify(ID_GROUP_SUMMARY, summary)
    }

    /** Quick burst: does the system collapse these into one sound? (issue #12) */
    fun postBurst(context: Context, count: Int = 5) {
        ensureChannel(context)
        pushShortcut(context, JID_BOB, "Spike Bob")
        val person = personFor(JID_BOB, "Spike Bob")
        val nm = context.getSystemService(NotificationManager::class.java)
        repeat(count) { i ->
            val style = Notification.MessagingStyle(person)
                .setGroupConversation(false)
                .addMessage("Burst message ${i + 1}", System.currentTimeMillis(), person)
            val n = baseBuilder(context)
                .setStyle(style)
                .setShortcutId(JID_BOB)
                .build()
            nm.notify(ID_BURST_BASE + i, n)
        }
    }

    fun cancelAll(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancelAll()
    }

    /** Exposed for the on-screen readout. */
    fun clipUri(): Uri = Uri.parse("content://${ClipProvider.AUTHORITY}/clip/spike_chirp.wav")
}
