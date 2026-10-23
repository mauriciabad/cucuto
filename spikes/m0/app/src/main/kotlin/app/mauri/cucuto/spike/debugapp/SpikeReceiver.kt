package app.mauri.cucuto.spike.debugapp

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Lets the whole M0 matrix be driven from adb instead of by tapping, which matters because the
 * owner has to run the device session in person and their time is the scarce resource here.
 *
 *   adb shell am broadcast -a app.mauri.cucuto.spike.POST \
 *       -n app.mauri.cucuto.spike/.debugapp.SpikeReceiver --es what one_to_one
 *
 * Exported on purpose, and acceptable only because this is a throwaway debug app: the worst a
 * third party can do with it is post a notification into our own test channel. The production app
 * must not ship anything like it.
 */
class SpikeReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "app.mauri.cucuto.spike.POST"
        private const val TAG = "cucuto-m0"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val what = intent.getStringExtra("what") ?: "one_to_one"
        Log.i(TAG, "SPIKE_RECEIVER what=$what")
        try {
            when (what) {
                "one_to_one" -> NotificationFactory.postOneToOne(context)
                "one_to_one_lid" -> NotificationFactory.postOneToOne(context, lid = true)
                "alert_once" -> NotificationFactory.postOneToOne(context, onlyAlertOnce = true)
                "group" -> NotificationFactory.postGroupConversation(context)
                "group_summary_alert" ->
                    NotificationFactory.postGroupConversation(context, Notification.GROUP_ALERT_SUMMARY)
                "group_alert_all" ->
                    NotificationFactory.postGroupConversation(context, Notification.GROUP_ALERT_ALL)
                "burst" -> NotificationFactory.postBurst(context)
                "cancel" -> NotificationFactory.cancelAll(context)
                else -> Log.w(TAG, "SPIKE_RECEIVER unknown what=$what")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "SPIKE_RECEIVER failed for what=$what", t)
        }
    }
}
