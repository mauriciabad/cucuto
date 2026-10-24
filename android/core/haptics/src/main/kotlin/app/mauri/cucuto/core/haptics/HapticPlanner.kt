package app.mauri.cucuto.core.haptics

import app.mauri.cucuto.core.routing.ControlPoint
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import kotlin.math.roundToInt

/** How a haptic will be played, decided from the device's [HapticCapabilities]. Plain data, no Android types. */
internal sealed interface HapticPlan {
    /** Nothing to play. */
    data object None : HapticPlan

    /** `VibrationEffect.Builder` with presets and basic envelopes (API 37.2). The framework handles fallbacks. */
    data class Timeline(
        val events: List<HapticEvent>,
    ) : HapticPlan

    /** One `BasicEnvelopeBuilder` effect: every envelope of the timeline joined, gaps held at zero intensity. */
    data class Envelope(
        val envelope: HapticEvent.Envelope,
    ) : HapticPlan

    /** A `VibrationEffect.Composition` of primitives; [delayMs] counts from the end of the previous primitive. */
    data class Primitives(
        val primitives: List<Primitive>,
    ) : HapticPlan

    /** `VibrationEffect.createPredefined(EFFECT_…)` for a lone preset whose primitive is unsupported (no scale). */
    data class Predefined(
        val preset: HapticPreset,
    ) : HapticPlan

    /** `VibrationEffect.createWaveform(timings, amplitudes, -1)`: the last resort every vibrator can play. */
    data class Waveform(
        val timingsMs: List<Long>,
        val amplitudes: List<Int>,
    ) : HapticPlan

    data class Primitive(
        val preset: HapticPreset,
        val milliScale: Int,
        val delayMs: Int,
    )
}

/**
 * Turns a [Haptic.Custom] into a [HapticPlan] for a device. First the timeline is made valid for the Builder's
 * checks whatever its source (pack, override, auto analysis):
 *
 * - Events are sorted by start time. An event that starts before the previous one is over (an envelope's full
 *   length, or 1 ms for a preset, as `VibrationEffect.Builder.build()` assumes) is moved to the earliest free slot.
 * - Values are clamped to 0..1, envelopes end at zero intensity, and the timeline ends by [MAX_TIMELINE_MS] (the
 *   pack format's limit); later events are dropped.
 * - On devices with envelope support, each envelope is fitted to the device: at most `maxControlPoints` points
 *   (simplified, shape first), segments between `minControlPointMs` and `maxControlPointMs`, at most
 *   `maxEnvelopeMs` long.
 *
 * Then the best API is chosen: the Builder when it exists (only ever in `system_server`, see [HapticEffects]);
 * otherwise, with public APIs only:
 *
 * - one basic envelope for envelope-only timelines, and for mixed timelines on devices with envelope support (each
 *   preset becomes a short envelope bump, [presetBump]);
 * - a composition for preset-only timelines whose primitives are all supported;
 * - `createPredefined` for a lone preset whose primitive is unsupported;
 * - a plain waveform otherwise.
 */
internal object HapticPlanner {
    /** The pack format's longest timeline ("the whole timeline lasts at most 5,000 ms"). */
    const val MAX_TIMELINE_MS: Int = 5000

    /** The duration the Builder's validation assumes for a preset. */
    private const val PRESET_SLOT_MS = 1

    /** Ramp to zero added to envelopes that do not end at zero, on devices without envelope limits. */
    private const val DEFAULT_RAMP_MS = 20

    /** Waveform fallback: envelope sampling step, and the pulse that stands for each preset. */
    private const val WAVEFORM_STEP_MS = 20

    /** Shortest rise (and fall) of the envelope bump that stands in for a preset without the Builder. */
    private const val PRESET_BUMP_MS = 10

    fun plan(
        haptic: Haptic.Custom,
        capabilities: HapticCapabilities,
    ): HapticPlan {
        val events =
            if (!capabilities.builderSupported && capabilities.envelopesSupported && isMixed(haptic.events)) {
                haptic.events.map { if (it is HapticEvent.Preset) presetBump(it, capabilities) else it }
            } else {
                haptic.events
            }
        val timeline = normalize(events, capabilities)
        if (timeline.isEmpty()) return HapticPlan.None
        return when {
            capabilities.builderSupported -> {
                HapticPlan.Timeline(timeline)
            }

            capabilities.envelopesSupported && timeline.all { it is HapticEvent.Envelope } -> {
                HapticPlan.Envelope(merge(timeline.filterIsInstance<HapticEvent.Envelope>(), capabilities))
            }

            timeline.all { it is HapticEvent.Preset && it.preset in capabilities.primitiveDurationsMs } -> {
                primitives(timeline.filterIsInstance<HapticEvent.Preset>(), capabilities)
            }

            timeline.size == 1 && timeline[0] is HapticEvent.Preset -> {
                HapticPlan.Predefined((timeline[0] as HapticEvent.Preset).preset)
            }

            else -> {
                waveform(timeline, capabilities)
            }
        }
    }

    /** The waveform fallback for [haptic], used directly and when the platform rejects a richer plan. */
    fun waveform(
        haptic: Haptic.Custom,
        capabilities: HapticCapabilities,
    ): HapticPlan = waveform(normalize(haptic.events, capabilities.copy(envelopesSupported = false)), capabilities)

    /** Sorted, clamped, overlap-free events that end by [MAX_TIMELINE_MS], envelopes fitted to the device. */
    fun normalize(
        events: List<HapticEvent>,
        capabilities: HapticCapabilities,
    ): List<HapticEvent> {
        val limits = capabilities.envelopeLimits
        val out = ArrayList<HapticEvent>(events.size)
        var free = 0
        for (event in events.sortedBy { it.atMs }) {
            val start = maxOf(event.atMs, free)
            if (start >= MAX_TIMELINE_MS) break
            when (event) {
                is HapticEvent.Preset -> {
                    out += HapticEvent.Preset(start, event.preset, event.milliScale.coerceIn(0, 1000))
                    free = start + PRESET_SLOT_MS
                }

                is HapticEvent.Envelope -> {
                    val remaining = MAX_TIMELINE_MS - start
                    val raw = Envelopes.polyline(event)
                    val polyline =
                        if (limits != null) {
                            Envelopes.fit(raw, limits.copy(maxDurationMs = minOf(limits.maxDurationMs, remaining)))
                        } else {
                            Envelopes.truncate(Envelopes.endAtZero(raw, DEFAULT_RAMP_MS), remaining, DEFAULT_RAMP_MS)
                        }
                    out += Envelopes.toEvent(start, polyline)
                    free = start + polyline.last().timeMs
                }
            }
        }
        return out
    }

    private fun isMixed(events: List<HapticEvent>): Boolean =
        events.any { it is HapticEvent.Preset } && events.any { it is HapticEvent.Envelope }

    /**
     * The envelope that stands in for [preset] when the Builder is missing: a rise to the preset's strength and a
     * fall back to zero, each as short as the device allows (its minimum segment, [PRESET_BUMP_MS] at least), at the
     * preset's sharpness. Same strength as the waveform fallback's pulse.
     */
    fun presetBump(
        preset: HapticEvent.Preset,
        capabilities: HapticCapabilities,
    ): HapticEvent.Envelope {
        val segment = maxOf(PRESET_BUMP_MS, capabilities.minControlPointMs)
        val sharpness =
            when (preset.preset) {
                HapticPreset.CLICK -> 900
                HapticPreset.TICK -> 1000
                HapticPreset.LOW_TICK -> 300
            }
        return HapticEvent.Envelope(
            preset.atMs,
            listOf(
                ControlPoint(presetIntensity(preset), sharpness, segment),
                ControlPoint(0, sharpness, segment),
            ),
            sharpness,
        )
    }

    /** A preset's strength in thousandths: its pulse strength, scaled so that scale 0 is weak but not off. */
    private fun presetIntensity(preset: HapticEvent.Preset): Int {
        val strength = pulse(preset.preset).second
        return (strength * (300 + 700 * preset.milliScale.coerceIn(0, 1000) / 1000)) / 1000
    }

    /** Joins the envelopes of an envelope-only timeline into one, holding zero intensity in the gaps. */
    private fun merge(
        envelopes: List<HapticEvent.Envelope>,
        capabilities: HapticCapabilities,
    ): HapticEvent.Envelope {
        val joined = ArrayList<EnvelopePoint>()
        for (envelope in envelopes) {
            val polyline = Envelopes.polyline(envelope)
            if (joined.isEmpty()) {
                joined += EnvelopePoint(0, 0, polyline[0].sharpness)
            }
            if (envelope.atMs > joined.last().timeMs) {
                joined += EnvelopePoint(envelope.atMs, 0, polyline[0].sharpness)
            }
            for (i in 1 until polyline.size) {
                val p = polyline[i]
                joined += p.copy(timeMs = envelope.atMs + p.timeMs)
            }
        }
        val limits = capabilities.envelopeLimits
        val fitted = if (limits != null) Envelopes.fit(joined, limits) else joined
        return Envelopes.toEvent(0, fitted)
    }

    private fun primitives(
        presets: List<HapticEvent.Preset>,
        capabilities: HapticCapabilities,
    ): HapticPlan {
        var previousEnd = 0
        return HapticPlan.Primitives(
            presets.map { preset ->
                val delay = (preset.atMs - previousEnd).coerceAtLeast(0)
                previousEnd = previousEnd + delay + (capabilities.primitiveDurationsMs[preset.preset] ?: 0)
                HapticPlan.Primitive(preset.preset, preset.milliScale, delay)
            },
        )
    }

    /**
     * Rasterizes [timeline] into on/off segments: envelopes sampled every [WAVEFORM_STEP_MS], presets as short
     * pulses. Without amplitude control a vibrator plays every non-zero amplitude at full strength, so only the
     * stronger half of an envelope vibrates there.
     */
    private fun waveform(
        timeline: List<HapticEvent>,
        capabilities: HapticCapabilities,
    ): HapticPlan {
        // (start, end, amplitude 0..255), in timeline order and non-overlapping.
        val segments = ArrayList<IntArray>()

        fun add(
            start: Int,
            end: Int,
            amplitude: Int,
        ) {
            val from = maxOf(start, segments.lastOrNull()?.get(1) ?: 0)
            if (end > from) segments += intArrayOf(from, end, amplitude)
        }

        fun amplitude(milliIntensity: Int): Int =
            when {
                milliIntensity <= 0 -> 0
                capabilities.amplitudeControl -> (milliIntensity * 255 / 1000.0).roundToInt().coerceIn(1, 255)
                milliIntensity >= 500 -> 255
                else -> 0
            }

        for ((index, event) in timeline.withIndex()) {
            val next = timeline.getOrNull(index + 1)?.atMs ?: Int.MAX_VALUE
            when (event) {
                is HapticEvent.Preset -> {
                    val pulseMs = pulse(event.preset).first
                    // Scale 0 is "the weakest perceptible", not off.
                    val intensity = presetIntensity(event)
                    val pulseAmplitude =
                        if (capabilities.amplitudeControl) {
                            amplitude(
                                intensity,
                            ).coerceAtLeast(1)
                        } else {
                            255
                        }
                    add(event.atMs, minOf(event.atMs + pulseMs, next), pulseAmplitude)
                }

                is HapticEvent.Envelope -> {
                    val polyline = Envelopes.polyline(event)
                    val length = polyline.last().timeMs
                    var t = 0
                    while (t < length) {
                        val step = minOf(WAVEFORM_STEP_MS, length - t)
                        val value = Envelopes.interpolate(polyline, t + step / 2).intensity
                        add(event.atMs + t, minOf(event.atMs + t + step, next), amplitude(value))
                        t += step
                    }
                }
            }
        }
        if (segments.none { it[2] > 0 }) return HapticPlan.None

        val timings = ArrayList<Long>()
        val amplitudes = ArrayList<Int>()
        var time = 0
        for ((start, end, amplitude) in segments.map { Triple(it[0], it[1], it[2]) }) {
            if (start > time) append(timings, amplitudes, (start - time).toLong(), 0)
            append(timings, amplitudes, (end - start).toLong(), amplitude)
            time = end
        }
        while (amplitudes.isNotEmpty() && amplitudes.last() == 0) {
            timings.removeAt(timings.lastIndex)
            amplitudes.removeAt(amplitudes.lastIndex)
        }
        return HapticPlan.Waveform(timings, amplitudes)
    }

    private fun append(
        timings: MutableList<Long>,
        amplitudes: MutableList<Int>,
        durationMs: Long,
        amplitude: Int,
    ) {
        if (durationMs <= 0) return
        if (amplitudes.isNotEmpty() && amplitudes.last() == amplitude) {
            timings[timings.lastIndex] = timings.last() + durationMs
        } else {
            timings += durationMs
            amplitudes += amplitude
        }
    }

    /** Pulse length and strength (thousandths) standing in for a preset in the waveform fallback. */
    private fun pulse(preset: HapticPreset): Pair<Int, Int> =
        when (preset) {
            HapticPreset.CLICK -> 20 to 1000
            HapticPreset.TICK -> 10 to 700
            HapticPreset.LOW_TICK -> 15 to 500
        }
}
