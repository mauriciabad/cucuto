package app.mauri.cucuto.spike.xposed

import android.os.VibrationEffect

/**
 * Builds the M0 test haptic, answering the brief's open question: does `VibrationEffect.Builder`
 * exist on the owner's build, or must we fall back to `BasicEnvelopeBuilder` + `Composition`?
 *
 * Everything is reflective on purpose. `VibrationEffect.Builder` ships in the **android-37.2**
 * platform but not in **android-37.0**, and the device runs SDK 37.1, so a direct compile-time
 * reference would both pin the project to a newer platform and risk a `NoClassDefFoundError`
 * inside `system_server` on any build where it is missing. Reflection makes the probe itself the
 * answer, and a miss degrades to the next strategy instead of throwing.
 *
 * Strategies, in the order the brief asks for:
 *  1. `VibrationEffect.Builder` + `Preset` (primary — maps 1:1 onto the pack haptic schema)
 *  2. `VibrationEffect.BasicEnvelopeBuilder` (API 36 fallback)
 *  3. `VibrationEffect.createWaveform` (always available since API 26)
 */
internal object SpikeHaptics {

    /** Human-readable note about which strategy produced the last effect. Logged, not parsed. */
    @Volatile
    var lastStrategy: String = "not-attempted"
        private set

    /** Probe result for the report: is the primary API present on this build? */
    val builderAvailable: Boolean by lazy {
        try {
            Class.forName("android.os.VibrationEffect\$Builder")
            Class.forName("android.os.VibrationEffect\$Preset")
            true
        } catch (_: Throwable) {
            false
        }
    }

    val basicEnvelopeBuilderAvailable: Boolean by lazy {
        try {
            Class.forName("android.os.VibrationEffect\$BasicEnvelopeBuilder")
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun build(): VibrationEffect? =
        tryBuilder() ?: tryBasicEnvelope() ?: tryWaveform()

    /** Strategy 1: the API the brief wants for pack haptics. */
    private fun tryBuilder(): VibrationEffect? = try {
        val builderClass = Class.forName("android.os.VibrationEffect\$Builder")
        val presetClass = Class.forName("android.os.VibrationEffect\$Preset")

        val presetClick = presetClass.getField("PRESET_CLICK").getInt(null)
        val presetTick = presetClass.getField("PRESET_TICK").getInt(null)
        val create = presetClass.getMethod("create", Int::class.javaPrimitiveType, Float::class.javaPrimitiveType)

        val builder = builderClass.getConstructor().newInstance()
        val addPreset = builderClass.getMethod("addPreset", Long::class.javaPrimitiveType, presetClass)

        // A two-beat signature: a firm click, then a lighter tick. Short on purpose.
        addPreset.invoke(builder, 0L, create.invoke(null, presetClick, 0.9f))
        addPreset.invoke(builder, 130L, create.invoke(null, presetTick, 0.5f))

        val effect = builderClass.getMethod("build").invoke(builder) as VibrationEffect
        lastStrategy = "VibrationEffect.Builder(PRESET_CLICK@0 x0.9, PRESET_TICK@130 x0.5)"
        effect
    } catch (t: Throwable) {
        lastStrategy = "Builder unavailable (${t.javaClass.simpleName})"
        null
    }

    /** Strategy 2: API 36 envelope fallback. */
    private fun tryBasicEnvelope(): VibrationEffect? = try {
        val cls = Class.forName("android.os.VibrationEffect\$BasicEnvelopeBuilder")
        val builder = cls.getConstructor().newInstance()
        cls.getMethod("setInitialSharpness", Float::class.javaPrimitiveType).invoke(builder, 0.8f)
        val addControlPoint = cls.getMethod(
            "addControlPoint",
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
        )
        addControlPoint.invoke(builder, 1.0f, 0.8f, 120L)
        // The envelope API requires the effect to end at zero intensity.
        addControlPoint.invoke(builder, 0.0f, 0.4f, 180L)
        val effect = cls.getMethod("build").invoke(builder) as VibrationEffect
        lastStrategy = "VibrationEffect.BasicEnvelopeBuilder(2 control points, ends at 0)"
        effect
    } catch (t: Throwable) {
        lastStrategy = "$lastStrategy; BasicEnvelopeBuilder unavailable (${t.javaClass.simpleName})"
        null
    }

    /** Strategy 3: the floor. Available on every build we care about. */
    private fun tryWaveform(): VibrationEffect? = try {
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 60, 70, 40), intArrayOf(0, 200, 0, 120), -1)
        lastStrategy = "$lastStrategy; fell back to createWaveform"
        effect
    } catch (t: Throwable) {
        lastStrategy = "$lastStrategy; createWaveform failed (${t.javaClass.simpleName})"
        null
    }
}
