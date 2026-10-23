package app.mauri.cucuto.core.haptics

import app.mauri.cucuto.core.routing.HapticPreset

/**
 * What a device's vibrator can play, as plain data so the planner runs (and is tested) without Android. Read it
 * once with [android.os.Vibrator.hapticCapabilities] and reuse it: it does not change while the device is running.
 *
 * @property builderSupported `VibrationEffect.Builder` exists (Android 17 QPR2, API 37.2). It sequences presets and
 *   basic envelopes and falls back on its own when the hardware lacks one.
 * @property envelopesSupported `Vibrator.areEnvelopeEffectsSupported()`. The envelope limits below are only
 *   meaningful (and positive) when this is true.
 * @property maxControlPoints `VibratorEnvelopeEffectInfo.getMaxSize()`, at least 16 on supporting devices.
 * @property minControlPointMs `getMinControlPointDurationMillis()`, at most 20 on supporting devices.
 * @property maxControlPointMs `getMaxControlPointDurationMillis()`, at least 1,000 on supporting devices.
 * @property maxEnvelopeMs `getMaxDurationMillis()`, at least `maxControlPoints × maxControlPointMs`.
 * @property amplitudeControl `Vibrator.hasAmplitudeControl()`, used by the waveform fallback.
 * @property primitiveDurationsMs The presets whose composition primitive is supported, with its duration (0 when
 *   unknown). Used only by the `Composition` fallback when the Builder is missing.
 */
data class HapticCapabilities(
    val builderSupported: Boolean = false,
    val envelopesSupported: Boolean = false,
    val maxControlPoints: Int = 0,
    val minControlPointMs: Int = 0,
    val maxControlPointMs: Int = 0,
    val maxEnvelopeMs: Int = 0,
    val amplitudeControl: Boolean = false,
    val primitiveDurationsMs: Map<HapticPreset, Int> = emptyMap(),
) {
    /** The envelope limits to fit envelopes to, or null when the device has no envelope support. */
    internal val envelopeLimits: EnvelopeLimits?
        get() =
            if (envelopesSupported && maxControlPoints > 0) {
                EnvelopeLimits(
                    maxControlPoints = maxControlPoints,
                    minSegmentMs = minControlPointMs.coerceAtLeast(1),
                    maxSegmentMs = maxControlPointMs.takeIf { it > 0 } ?: Int.MAX_VALUE,
                    maxDurationMs = maxEnvelopeMs.takeIf { it > 0 } ?: Int.MAX_VALUE,
                )
            } else {
                null
            }

    companion object {
        /**
         * The minimum every device with envelope support guarantees (`VibratorEnvelopeEffectInfo` reference): 16
         * control points, 20 ms segments, 1 s per segment. For tests and for analysing clips off-device.
         */
        val GUARANTEED_ENVELOPES: HapticCapabilities =
            HapticCapabilities(
                builderSupported = true,
                envelopesSupported = true,
                maxControlPoints = 16,
                minControlPointMs = 20,
                maxControlPointMs = 1000,
                maxEnvelopeMs = 16_000,
                amplitudeControl = true,
                primitiveDurationsMs = HapticPreset.entries.associateWith { 0 },
            )
    }
}

/** Limits an envelope must respect on the target device. All values are positive. */
internal data class EnvelopeLimits(
    val maxControlPoints: Int,
    val minSegmentMs: Int,
    val maxSegmentMs: Int,
    val maxDurationMs: Int,
)
