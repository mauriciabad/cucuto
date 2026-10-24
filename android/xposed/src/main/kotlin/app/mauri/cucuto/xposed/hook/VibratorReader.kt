package app.mauri.cucuto.xposed.hook

import android.content.Context
import android.os.VibratorManager
import app.mauri.cucuto.core.haptics.HapticCapabilities
import app.mauri.cucuto.core.haptics.HapticEffects
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Reads the device vibrator's [HapticCapabilities] through `NotificationAttentionHelper`'s own `Context` (its
 * `mContext` field, or else its first `Context` field), the system context of `system_server`.
 *
 * Read inside `system_server` on purpose: there `VibrationEffect.Builder` passes `HapticEffects.builderAvailable`'s
 * probe even on the owner's API 37.1 build, where it is blocked hidden API for apps (#96, ADR 0012). The field is
 * resolved once; a read never throws and returns null when anything is missing.
 */
internal class VibratorReader(
    helperClass: Class<*>,
) {
    private val contextField: Field? = contextField(helperClass)

    val usable: Boolean get() = contextField != null

    fun capabilities(helper: Any?): HapticCapabilities? =
        try {
            val context = helper?.let { contextField?.get(it) } as? Context
            context
                ?.getSystemService(VibratorManager::class.java)
                ?.defaultVibrator
                ?.let(HapticEffects::capabilities)
        } catch (_: Throwable) {
            null
        }

    companion object {
        /** What effects are built for when the vibrator cannot be read: the Builder, if any, falls back on its own. */
        fun fallback(): HapticCapabilities = HapticCapabilities(builderSupported = HapticEffects.builderAvailable)

        private fun contextField(owner: Class<*>): Field? =
            try {
                val fields =
                    generateSequence(owner) { it.superclass }
                        .flatMap { it.declaredFields.asSequence() }
                        .filter { !Modifier.isStatic(it.modifiers) && Context::class.java.isAssignableFrom(it.type) }
                        .toList()
                (fields.firstOrNull { it.name == "mContext" } ?: fields.firstOrNull())?.apply { isAccessible = true }
            } catch (_: Throwable) {
                null
            }
    }
}
