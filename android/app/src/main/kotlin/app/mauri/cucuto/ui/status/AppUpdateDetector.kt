package app.mauri.cucuto.ui.status

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether Cucuto was updated after this boot. After an update, LSPosed 2.2.1 stops giving the app its framework
 * service until the phone reboots (ADR 0043), so Setup and Diagnostics tell the user to reboot instead of to enable
 * the module.
 */
@Singleton
class AppUpdateDetector
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        /** Reads the package's install times (a local package manager call, cheap). False when unreadable. */
        fun updatedSinceBoot(): Boolean =
            try {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                StatusChecks.updatedSinceBoot(info.firstInstallTime, info.lastUpdateTime, bootTimeMillis())
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }

        companion object {
            /**
             * LSPosed hands the service to a module app shortly after its process starts (the daemon waits about
             * 0.4 s before it calls the app's provider). Until this much time has passed since the process started,
             * "no service" may only mean "not yet", so the status card keeps checking instead of reporting it.
             */
            const val SERVICE_GRACE_MILLIS: Long = 3_000

            /** Milliseconds left of [SERVICE_GRACE_MILLIS] for this process, or 0. */
            fun serviceGraceLeftMillis(): Long =
                (SERVICE_GRACE_MILLIS - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()))
                    .coerceAtLeast(0)

            /** Wall-clock time of this boot. */
            fun bootTimeMillis(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        }
    }
