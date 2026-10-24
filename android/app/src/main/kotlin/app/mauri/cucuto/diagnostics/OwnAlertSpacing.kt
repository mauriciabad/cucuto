package app.mauri.cucuto.diagnostics

import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps Cucuto's own alerting notifications (the Diagnostics self-test and "Simulate message") far enough apart to
 * escape Android's per-app notification cooldown, which mutes an app's alert posted within 10 s of its previous one
 * (see [SelfTestNotifier]). The cooldown counts per app, so both notifications share one clock.
 */
@Singleton
class OwnAlertSpacing
    @Inject
    constructor() {
        private val gate = SpacingGate(MIN_SPACING_MILLIS)

        /** Claims the next slot now and returns null, or returns the seconds to wait (nothing is claimed). */
        fun tryAcquire(): Int? = gate.tryAcquire(SystemClock.elapsedRealtime())

        companion object {
            /**
             * Android mutes an app's alert posted within `config_notifCooldownMuteMillis` (10 s in AOSP and on the
             * Pixel) of its previous one; a little margin on top.
             */
            const val MIN_SPACING_MILLIS: Long = 12_000
        }
    }

/** The pure part of [OwnAlertSpacing]: at most one acquisition per [spacingMillis]. */
class SpacingGate(
    private val spacingMillis: Long,
) {
    private var last: Long? = null

    @Synchronized
    fun tryAcquire(nowMillis: Long): Int? {
        last?.let { previous ->
            val wait = previous + spacingMillis - nowMillis
            if (wait > 0) return ((wait + 999) / 1000).toInt()
        }
        last = nowMillis
        return null
    }
}
