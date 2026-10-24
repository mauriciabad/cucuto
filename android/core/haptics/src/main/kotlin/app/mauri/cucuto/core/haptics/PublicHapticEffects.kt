package app.mauri.cucuto.core.haptics

import android.os.VibrationEffect
import android.os.Vibrator
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset

/**
 * Builds haptic effects in the **app process** (picker previews, auto-haptic analysis, anything that is not the
 * module), with public SDK APIs only: one `BasicEnvelopeBuilder` envelope, a `Composition` of primitives,
 * `createPredefined` or `createWaveform` with amplitudes, chosen from the vibrator's capabilities
 * (`areEnvelopeEffectsSupported`, `arePrimitivesSupported`, `hasAmplitudeControl`).
 *
 * `VibrationEffect.Builder`, `Envelope` and `Preset` are blocked hidden API for apps on API 37.1 (issue #139), so
 * this path never references them, not even behind a check: the choice is made per process, up front. The module in
 * `system_server` uses [HapticEffects] instead, which adds the Builder. Previews therefore match the module's
 * output closely but not exactly; ADR 0012 lists the differences.
 */
object PublicHapticEffects {
    /**
     * The effect for [haptic] on a device with [capabilities], or null when there is nothing playable. Never throws.
     * [HapticCapabilities.builderSupported] is ignored: this path never uses the Builder.
     */
    fun create(
        haptic: Haptic.Custom,
        capabilities: HapticCapabilities,
    ): VibrationEffect? =
        PublicEffects.create(haptic, capabilities.copy(builderSupported = false), PublicEffects::build)

    /** What [vibrator] can play through public APIs (never the Builder). Read it once and reuse it. Never throws. */
    fun capabilities(vibrator: Vibrator): HapticCapabilities = vibrator.readCapabilities(builderSupported = false)
}

/** Maps [HapticPlan]s onto public `VibrationEffect` APIs. Shared by [PublicHapticEffects] and [HapticEffects]. */
internal object PublicEffects {
    /**
     * Plans [haptic] and builds it with [build]. If the platform rejects the plan (a limit the capabilities did not
     * reveal), retries once as a plain waveform. Never throws.
     */
    inline fun create(
        haptic: Haptic.Custom,
        capabilities: HapticCapabilities,
        build: (HapticPlan) -> VibrationEffect?,
    ): VibrationEffect? {
        val plan =
            try {
                HapticPlanner.plan(haptic, capabilities)
            } catch (_: Exception) {
                return null
            }
        return safely { build(plan) } ?: safely { build(HapticPlanner.waveform(haptic, capabilities)) }
    }

    inline fun safely(block: () -> VibrationEffect?): VibrationEffect? =
        try {
            block()
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }

    /** The effect for [plan], or null for [HapticPlan.None] and [HapticPlan.Timeline] (the Builder is not public). */
    fun build(plan: HapticPlan): VibrationEffect? =
        when (plan) {
            HapticPlan.None, is HapticPlan.Timeline -> {
                null
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

            is HapticPlan.Predefined -> {
                VibrationEffect.createPredefined(
                    when (plan.preset) {
                        HapticPreset.CLICK -> VibrationEffect.EFFECT_CLICK

                        // There is no predefined low tick; a tick is the closest.
                        HapticPreset.TICK, HapticPreset.LOW_TICK -> VibrationEffect.EFFECT_TICK
                    },
                )
            }

            is HapticPlan.Waveform -> {
                VibrationEffect.createWaveform(
                    plan.timingsMs.toLongArray(),
                    plan.amplitudes.toIntArray(),
                    -1,
                )
            }
        }

    fun basicEnvelope(event: HapticEvent.Envelope): VibrationEffect.BasicEnvelopeBuilder {
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

    fun fraction(milli: Int): Float = milli.coerceIn(0, 1000) / 1000f
}

/**
 * Reads this vibrator's [HapticCapabilities] with public APIs only; [builderSupported] is decided by the caller's
 * process ([HapticEffects] or [PublicHapticEffects]). Never throws: anything the vibrator cannot answer is reported
 * as unsupported, which only makes the planner pick a simpler fallback.
 */
internal fun Vibrator.readCapabilities(builderSupported: Boolean): HapticCapabilities {
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
        builderSupported = builderSupported,
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
