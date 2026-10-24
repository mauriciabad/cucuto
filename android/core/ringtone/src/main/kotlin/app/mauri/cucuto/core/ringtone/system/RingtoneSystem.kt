package app.mauri.cucuto.core.ringtone.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import app.mauri.cucuto.core.ringtone.PhoneRingtones
import app.mauri.cucuto.core.ringtone.sync.SyncReason
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The next rule boundary (day/night, an hour range, midnight, a new season) as one inexact alarm (ADR 0039). Inexact
 * on purpose: `setAndAllowWhileIdle` needs no exact-alarm permission, wakes the phone a few times a day at most, and a
 * ringtone that switches a few minutes late is fine.
 */
@Singleton
class BoundaryScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        /** Schedules the sync at [epochMs] (replacing any earlier one), or cancels it when null. */
        fun schedule(epochMs: Long?) {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            val intent =
                PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(context, RingtoneEventsReceiver::class.java).setAction(ACTION_BOUNDARY),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            if (epochMs == null) {
                alarms.cancel(intent)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epochMs + SLACK_MS, intent)
            }
        }

        companion object {
            const val ACTION_BOUNDARY = "app.mauri.cucuto.action.RINGTONE_BOUNDARY"
            private const val SLACK_MS = 1_000L
        }
    }

/**
 * Turns the ringtone receivers on and off, so Cucuto is never woken for a call or a reboot while phone ringtones are
 * off, nor for a call while no contact uses "random per call" (minimum intrusion). Both are disabled in the manifest.
 */
@Singleton
class RingtoneReceivers
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun apply(
            sync: Boolean,
            calls: Boolean,
        ) {
            set(RingtoneEventsReceiver::class.java, sync)
            set(CallEndedReceiver::class.java, calls)
        }

        private fun set(
            receiver: Class<out BroadcastReceiver>,
            on: Boolean,
        ) {
            val pm = context.packageManager
            val component = ComponentName(context, receiver)
            val wanted =
                if (on) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
            if (pm.getComponentEnabledSetting(component) != wanted) {
                pm.setComponentEnabledSetting(component, wanted, PackageManager.DONT_KILL_APP)
            }
        }
    }

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RingtoneEntryPoint {
    fun phoneRingtones(): PhoneRingtones
}

/** Runs a sync from a receiver within the broadcast's time budget. */
private fun BroadcastReceiver.syncAsync(
    context: Context,
    reason: SyncReason,
) {
    val pending = goAsync()
    val ringtones =
        EntryPointAccessors
            .fromApplication(
                context.applicationContext,
                RingtoneEntryPoint::class.java,
            ).phoneRingtones()
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        try {
            withTimeoutOrNull(RECEIVER_BUDGET_MS) { ringtones.syncNow(reason) }
        } finally {
            pending.finish()
        }
    }
}

private const val RECEIVER_BUDGET_MS = 8_000L

/**
 * "Random per call" without a hook (#144, ADR 0023 decision 3): when a call ends (`PHONE_STATE` back to idle), every
 * random contact gets a new variant, so the next call rings with a different one. Needs `READ_PHONE_STATE`; it never
 * reads the number. `TelephonyRegistry` sends this broadcast to manifest receivers in the background; the app starts
 * for a fraction of a second after each call, and only while some contact uses the random rule.
 */
class CallEndedReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        if (intent.getStringExtra(TelephonyManager.EXTRA_STATE) != TelephonyManager.EXTRA_STATE_IDLE) return
        syncAsync(context, SyncReason.CallEnded)
    }
}

/**
 * Re-applies the rules after a reboot (alarms are gone), a clock or time zone change, and at the scheduled boundary.
 * All three system broadcasts are exempt from Android's implicit-broadcast limits.
 */
class RingtoneEventsReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            BoundaryScheduler.ACTION_BOUNDARY,
            -> syncAsync(context, SyncReason.Changed)
        }
    }
}
