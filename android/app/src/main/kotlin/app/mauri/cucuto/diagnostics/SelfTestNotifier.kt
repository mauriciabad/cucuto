package app.mauri.cucuto.diagnostics

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import app.mauri.cucuto.R
import app.mauri.cucuto.xposed.route.Targets
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts the Diagnostics test notification on channel [Targets.SELF_TEST_CHANNEL]. With the module active, the hook
 * swaps its sound for the provider's self-test chirp and its vibration for a click-tick, then reports the decision
 * back: an end-to-end check of hook, clip provider, haptics and event delivery without a WhatsApp message.
 * Without the module, the phone plays the default notification sound.
 */
@Singleton
class SelfTestNotifier
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        /** Returns false when notifications are blocked for the app. */
        suspend fun post(): Boolean =
            withContext(Dispatchers.IO) {
                SelfTestSound.ensureFile(context.filesDir)
                val manager = context.getSystemService(NotificationManager::class.java)
                if (!manager.areNotificationsEnabled()) return@withContext false
                ensureChannel(manager)
                val notification =
                    Notification
                        .Builder(context, Targets.SELF_TEST_CHANNEL)
                        .setSmallIcon(R.drawable.ic_launcher_monochrome)
                        .setContentTitle(context.getString(R.string.selftest_notification_title))
                        .setContentText(context.getString(R.string.selftest_notification_text))
                        .setCategory(Notification.CATEGORY_STATUS)
                        .setAutoCancel(true)
                        .build()
                manager.notify(NOTIFICATION_ID, notification)
                true
            }

        private fun ensureChannel(manager: NotificationManager) {
            if (manager.getNotificationChannel(Targets.SELF_TEST_CHANNEL) != null) return
            // A sound and a vibration are required: without them the framework never reaches playSound or
            // playVibration, and there would be nothing for the hook to swap.
            val channel =
                NotificationChannel(
                    Targets.SELF_TEST_CHANNEL,
                    context.getString(R.string.selftest_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.selftest_channel_description)
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                        AudioAttributes
                            .Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 400, 200, 400)
                }
            manager.createNotificationChannel(channel)
        }

        private companion object {
            const val NOTIFICATION_ID = 0xC0C0
        }
    }
