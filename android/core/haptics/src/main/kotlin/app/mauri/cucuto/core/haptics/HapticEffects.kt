package app.mauri.cucuto.core.haptics

import android.annotation.SuppressLint
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset

/**
 * Builds the [VibrationEffect] for a haptic timeline ([Haptic.Custom]: pack `custom` mode, a per-contact override or
 * a clip's auto envelope), following the pack format's one-to-one mapping:
 *
 * - `{ "at", "preset", "scale" }` → `addPreset(at, Preset.create(PRESET_…, scale))`
 * - `{ "at", "initialSharpness", "envelope" }` → `addEnvelope(at, Envelope.create(BasicEnvelopeBuilder()…))`
 *
 * `VibrationEffect.Builder` is used whenever it exists (API 37.2); it falls back on its own, per element, when the
 * hardware lacks a preset or envelope support. Envelopes are first fitted to the device's
 * `VibratorEnvelopeEffectInfo` limits. Without the Builder, the effect is one `BasicEnvelopeBuilder` envelope, a
 * `Composition` or a waveform, whichever the device can play ([HapticPlanner]). `WaveformEnvelopeBuilder` is never
 * used: it has no fallback and depends on the device's frequency profile.
 *
 * Needs no Context and never throws: safe to call from `system_server` (`:xposed`). Cheap enough for the
 * notification path (no IO, a few small allocations), but callers that play the same haptic often can cache the
 * result: [VibrationEffect]s are immutable.
 */
object HapticEffects {
    /** The effect for [haptic] on a device with [capabilities], or null when there is nothing playable. */
    fun create(
        haptic: Haptic.Custom,
        capabilities: HapticCapabilities,
    ): VibrationEffect? {
        val plan =
            try {
                HapticPlanner.plan(haptic, capabilities)
            } catch (_: Exception) {
                return null
            }
        // If the platform rejects the plan (a limit the capabilities did not reveal, or a platform class that differs
        // despite the checks), retry as a plain waveform.
        return safely { build(plan) } ?: safely { build(HapticPlanner.waveform(haptic, capabilities)) }
    }

    private inline fun safely(block: () -> VibrationEffect?): VibrationEffect? =
        try {
            block()
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }

    /** [create] with the capabilities of [vibrator]. Prefer reading them once with [hapticCapabilities]. */
    fun create(
        haptic: Haptic.Custom,
        vibrator: Vibrator,
    ): VibrationEffect? = create(haptic, vibrator.hapticCapabilities())

    internal fun build(plan: HapticPlan): VibrationEffect? =
        when (plan) {
            HapticPlan.None -> {
                null
            }

            is HapticPlan.Timeline -> {
                timeline(plan)
            }

            is HapticPlan.Envelope -> {
                basicEnvelope(plan.envelope).build()
            }

            is HapticPlan.Primitives -> {
                val composition = VibrationEffect.startComposition()
                for (primitive in plan.primitives) {
                    composition.addPrimitive(
                        primitiveId(primitive.preset),
                        fraction(primitive.milliScale),
                        primitive.delayMs,
                    )
                }
                composition.compose()
            }

            is HapticPlan.Waveform -> {
                VibrationEffect.createWaveform(
                    plan.timingsMs.toLongArray(),
                    plan.amplitudes.toIntArray(),
                    -1,
                )
            }
        }

    // Guarded by builderAvailable: API 37.2, or the same classes already present on an earlier build.
    @SuppressLint("NewApi", "InlinedApi")
    private fun timeline(plan: HapticPlan.Timeline): VibrationEffect? {
        if (!builderAvailable) return null
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
                        VibrationEffect.Preset.create(id, fraction(event.milliScale)),
                    )
                }

                is HapticEvent.Envelope -> {
                    builder.addEnvelope(event.atMs.toLong(), VibrationEffect.Envelope.create(basicEnvelope(event)))
                }
            }
        }
        return builder.build()
    }

    private fun basicEnvelope(event: HapticEvent.Envelope): VibrationEffect.BasicEnvelopeBuilder {
        val builder = VibrationEffect.BasicEnvelopeBuilder()
        if (event.milliInitialSharpness != HapticEvent.Envelope.DEFAULT_SHARPNESS) {
            builder.setInitialSharpness(fraction(event.milliInitialSharpness))
        }
        for (point in event.points) {
            builder.addControlPoint(
                fraction(point.milliIntensity),
                fraction(point.milliSharpness),
                point.durationMs.toLong(),
            )
        }
        return builder
    }

    private fun primitiveId(preset: HapticPreset): Int =
        when (preset) {
            HapticPreset.CLICK -> VibrationEffect.Composition.PRIMITIVE_CLICK
            HapticPreset.TICK -> VibrationEffect.Composition.PRIMITIVE_TICK
            HapticPreset.LOW_TICK -> VibrationEffect.Composition.PRIMITIVE_LOW_TICK
        }

    /**
     * Whether `VibrationEffect.Builder` can be used. The reference lists it from API 37.2, but the owner's Android 17
     * QPR1 build (37.1, `CP3A.261005.005`) already ships the same public classes and played a Builder effect in the
     * M0 spike, so their presence is probed as well. In an app process, hidden API restrictions make the probe fail
     * whenever a direct call would.
     */
    val builderAvailable: Boolean by lazy {
        Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.CINNAMON_BUN_2 || hasBuilderClasses()
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

    private fun fraction(milli: Int): Float = milli.coerceIn(0, 1000) / 1000f
}

/**
 * Reads this vibrator's [HapticCapabilities]. Never throws: anything the vibrator cannot answer is reported as
 * unsupported, which only makes [HapticEffects] pick a simpler fallback.
 */
fun Vibrator.hapticCapabilities(): HapticCapabilities {
    val builder = HapticEffects.builderAvailable
    val envelopes = runCatching { areEnvelopeEffectsSupported() }.getOrDefault(false)
    val info = if (envelopes) runCatching { envelopeEffectInfo }.getOrNull() else null
    // Same order as `presets`.
    val presets = listOf(HapticPreset.CLICK, HapticPreset.TICK, HapticPreset.LOW_TICK)
    val supported =
        runCatching {
            arePrimitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_CLICK,
                VibrationEffect.Composition.PRIMITIVE_TICK,
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
            )
        }.getOrNull()
    val durations =
        runCatching {
            getPrimitiveDurations(
                VibrationEffect.Composition.PRIMITIVE_CLICK,
                VibrationEffect.Composition.PRIMITIVE_TICK,
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
            )
        }.getOrNull()
    return HapticCapabilities(
        builderSupported = builder,
        envelopesSupported = info != null && info.maxSize > 0,
        maxControlPoints = info?.maxSize ?: 0,
        minControlPointMs = info?.minControlPointDurationMillis?.toIntClamped() ?: 0,
        maxControlPointMs = info?.maxControlPointDurationMillis?.toIntClamped() ?: 0,
        maxEnvelopeMs = info?.maxDurationMillis?.toIntClamped() ?: 0,
        amplitudeControl = runCatching { hasAmplitudeControl() }.getOrDefault(false),
        primitiveDurationsMs =
            buildMap {
                presets.forEachIndexed { i, preset ->
                    if (supported?.getOrNull(i) == true) put(preset, durations?.getOrNull(i) ?: 0)
                }
            },
    )
}

private fun Long.toIntClamped(): Int = coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
