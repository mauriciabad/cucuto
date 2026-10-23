package app.mauri.cucuto.diagnostics

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.SystemClock
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
 *
 * Android 15+ "notification cooldown" (politeness, `NotificationAttentionHelper`) mutes alerts before the framework
 * ever calls `playSound`/`playVibration`, so the hook never sees them (device finding, issue #15):
 * - Avalanche: for 2 minutes after boot, turning airplane mode off or a profile becoming available, every alert
 *   that is not exempt is muted. The test is posted as [Notification.CATEGORY_REMINDER], which is exempt at full
 *   volume (like WhatsApp's conversations, which are exempt as conversations).
 * - Per-app cooldown: a second alert from the same app within 60 s plays at reduced volume (the hook still runs),
 *   and one within 10 s of the previous post is muted. So two tests must be at least [MIN_SPACING_MILLIS] apart.
 */
@Singleton
class SelfTestNotifier
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        sealed interface Result {
            data object Posted : Result

            /** Notifications are blocked for the app. */
            data object Blocked : Result

            /** Posting now would be muted by Android's per-app cooldown; try again in [seconds]. */
            data class TooSoon(
                val seconds: Int,
            ) : Result
        }

        @Volatile private var lastPostedAt: Long? = null

        suspend fun post(): Result =
            withContext(Dispatchers.IO) {
                val now = SystemClock.elapsedRealtime()
                lastPostedAt?.let { last ->
                    val wait = last + MIN_SPACING_MILLIS - now
                    if (wait > 0) return@withContext Result.TooSoon(((wait + 999) / 1000).toInt())
                }
                SelfTestSound.ensureFile(context.filesDir)
                val manager = context.getSystemService(NotificationManager::class.java)
                if (!manager.areNotificationsEnabled()) return@withContext Result.Blocked
                ensureChannel(manager)
                val notification =
                    Notification
                        .Builder(context, Targets.SELF_TEST_CHANNEL)
                        .setSmallIcon(R.drawable.ic_stat_cucuto)
                        .setContentTitle(context.getString(R.string.selftest_notification_title))
                        .setContentText(context.getString(R.string.selftest_notification_text))
                        .setCategory(Notification.CATEGORY_REMINDER)
                        .setAutoCancel(true)
                        .build()
                manager.notify(NOTIFICATION_ID, notification)
                lastPostedAt = now
                Result.Posted
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

        companion object {
            private const val NOTIFICATION_ID = 0xC0C0

            /**
             * Android mutes an app's alert posted within `config_notifCooldownMuteMillis` (10 s in AOSP and on the
             * Pixel) of its previous one; a little margin on top.
             */
            const val MIN_SPACING_MILLIS: Long = 12_000
        }
    }
