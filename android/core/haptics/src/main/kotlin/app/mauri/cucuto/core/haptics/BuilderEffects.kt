package app.mauri.cucuto.core.haptics

import android.annotation.SuppressLint
import android.os.Build
import android.os.VibrationEffect
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset

/**
 * The only code that touches `VibrationEffect.Builder`, `Envelope` and `Preset` (API 37.2). On the owner's API 37.1
 * build those classes exist but are blocked hidden API for apps, so this object must only ever load in
 * `system_server`, through [HapticEffects]. The app process uses [PublicHapticEffects], which never references it
 * (`HiddenApiSurfaceTest` in `:app` walks the class graph to keep it that way, issue #139).
 */
internal object BuilderEffects {
    /**
     * Whether `VibrationEffect.Builder` can be used. The reference lists it from API 37.2, but the owner's Android 17
     * QPR1 build (37.1, `CP3A.261005.005`) already ships the same classes and played a Builder effect from
     * `system_server` in the M0 spike, so their presence is probed as well. Never call this from an app process: on
     * 37.1 the probe itself is a blocked hidden API access (one `hiddenapi` denial per member).
     */
    val available: Boolean by lazy {
        Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.CINNAMON_BUN_2 || hasBuilderClasses()
    }

    // Guarded by `available`: API 37.2, or the same classes already present on an earlier build.
    @SuppressLint("NewApi", "InlinedApi")
    fun timeline(plan: HapticPlan.Timeline): VibrationEffect? {
        if (!available) return null
        val builder = VibrationEffect.Builder()
        for (event in plan.events) {
            when (event) {
                is HapticEvent.Preset -> {
                    val id =
                        when (event.preset) {
                            HapticPreset.CLICK -> VibrationEffect.Preset.PRESET_CLICK
                            HapticPreset.TICK -> VibrationEffect.Preset.PRESET_TICK
                            HapticPreset.LOW_TICK -> VibrationEffect.Preset.PRESET_LOW_TICK
                        }
                    builder.addPreset(
                        event.atMs.toLong(),
                        VibrationEffect.Preset.create(id, PublicEffects.fraction(event.milliScale)),
                    )
                }

                is HapticEvent.Envelope -> {
                    builder.addEnvelope(
                        event.atMs.toLong(),
                        VibrationEffect.Envelope.create(PublicEffects.basicEnvelope(event)),
                    )
                }
            }
        }
        return builder.build()
    }

    private fun hasBuilderClasses(): Boolean =
        try {
            val effect = VibrationEffect::class.java
            val builder = Class.forName("android.os.VibrationEffect\$Builder")
            val preset = Class.forName("android.os.VibrationEffect\$Preset")
            val envelope = Class.forName("android.os.VibrationEffect\$Envelope")
            builder.getConstructor()
            builder.getMethod("addPreset", Long::class.javaPrimitiveType, preset)
            builder.getMethod("addEnvelope", Long::class.javaPrimitiveType, envelope)
            builder.getMethod("build").returnType == effect &&
                preset.getMethod("create", Int::class.javaPrimitiveType, Float::class.javaPrimitiveType) != null &&
                envelope.getMethod("create", VibrationEffect.BasicEnvelopeBuilder::class.java) != null
        } catch (_: ReflectiveOperationException) {
            false
        } catch (_: LinkageError) {
            false
        } catch (_: SecurityException) {
            false
        }
}
