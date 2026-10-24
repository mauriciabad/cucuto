package app.mauri.cucuto.xposed.hook

import android.content.Context
import android.os.VibratorManager
import app.mauri.cucuto.core.haptics.HapticCapabilities
import app.mauri.cucuto.core.haptics.HapticEffects
import app.mauri.cucuto.xposed.events.systemContext

/**
 * Reads the device vibrator's [HapticCapabilities] through the system context of `system_server`
 * (`ActivityThread.getSystemContext()`, the same Context `NotificationAttentionHelper` holds). Called only from the
 * module's background thread after boot, by [CapabilitiesLoader], never from the notification path (#190, ADR 0044).
 *
 * Read inside `system_server` on purpose: there `VibrationEffect.Builder` passes `HapticEffects.builderAvailable`'s
 * probe even on the owner's API 37.1 build, where it is blocked hidden API for apps (#96, ADR 0012).
 *
 * A read returns null while the vibrator service is not ready, so the caller retries:
 *
 * - before `vibrator_manager` is registered with the service manager. The system context is not even asked for its
 *   `VibratorManager` then: the context caches the manager it creates, and one created that early keeps a null
 *   binder (`SystemVibratorManager` reads it once, in its constructor) for the whole boot, also for
 *   `NotificationManagerService`, which shares the context.
 * - while a vibrator's info is not loaded yet (`getVibrator(id)` is a `NullVibrator` until the service is ready or
 *   the HAL load succeeded). `SystemVibrator` would otherwise report empty capabilities, uncached, for now.
 *
 * Never throws.
 */
internal class VibratorReader(
    private val context: () -> Context? = ::systemContext,
    private val serviceRegistered: () -> Boolean = ::vibratorManagerRegistered,
) {
    /** The device's capabilities, or null while the vibrator service is not ready (or cannot be reached). */
    fun read(): HapticCapabilities? {
        return try {
            if (!serviceRegistered()) return null
            val manager = context()?.getSystemService(VibratorManager::class.java) ?: return null
            if (!infoReady(manager.vibratorIds) { id -> manager.getVibrator(id).hasVibrator() }) return null
            HapticEffects.capabilities(manager.defaultVibrator)
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        /** What effects are built for when the vibrator cannot be read: the Builder, if any, falls back on its own. */
        fun fallback(): HapticCapabilities = HapticCapabilities(builderSupported = HapticEffects.builderAvailable)

        /**
         * Whether the info of every vibrator in [ids] is loaded: [loaded] is `getVibrator(id).hasVibrator()`, false
         * for the `NullVibrator` the manager hands out until then. No vibrators at all is a known answer (a device
         * without one), so it counts as ready.
         */
        internal fun infoReady(
            ids: IntArray?,
            loaded: (Int) -> Boolean,
        ): Boolean = ids != null && ids.all(loaded)
    }
}

/** Whether `vibrator_manager` is registered with the service manager (`ServiceManager.checkService`, no waiting). */
internal fun vibratorManagerRegistered(): Boolean =
    try {
        Class
            .forName("android.os.ServiceManager")
            .getMethod("checkService", String::class.java)
            .invoke(null, Context.VIBRATOR_MANAGER_SERVICE) != null
    } catch (_: Throwable) {
        false
    }
