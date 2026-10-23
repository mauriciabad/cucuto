package app.mauri.cucuto.xposed.hook

import android.os.VibrationEffect
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * Turns a route's [Haptic.Custom] into a `VibrationEffect` built with `VibrationEffect.Builder` (brief, "Haptics";
 * confirmed on the owner's build in M0).
 *
 * The Builder family is reached through reflection, resolved once: it exists in the android-37.2 platform but not in
 * 37.0, and a `NoClassDefFoundError` inside `system_server` is a different risk class from one in the app (M0 report,
 * recommendation 9). When the Builder is missing the timeline's presets fall back to a plain `createWaveform`; when
 * nothing can be built the caller keeps WhatsApp's vibration.
 *
 * Effects are immutable, so each distinct haptic is built once and cached; the cache is bounded.
 */
internal class HapticEffects {
    private val cache =
        object : LinkedHashMap<Haptic.Custom, Any>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Haptic.Custom, Any>?) = size > CACHE_SIZE
        }

    private val api: BuilderApi? by lazy { BuilderApi.resolveOrNull() }

    /** Short description of the strategy in use, for the module log. */
    val strategy: String get() = if (api != null) "VibrationEffect.Builder" else "createWaveform fallback"

    /** The effect for [haptic], or null when it cannot be built (keep the original vibration). */
    fun effectFor(haptic: Haptic.Custom): VibrationEffect? {
        synchronized(cache) {
            cache[haptic]?.let { return it as? VibrationEffect }
        }
        val built =
            try {
                api?.build(haptic) ?: waveformFallback(haptic)
            } catch (_: Throwable) {
                try {
                    waveformFallback(haptic)
                } catch (_: Throwable) {
                    null
                }
            }
        synchronized(cache) { cache[haptic] = built ?: FAILED }
        return built
    }

    private class BuilderApi(
        private val builderCtor: Constructor<*>,
        private val addPreset: Method,
        private val addEnvelope: Method,
        private val build: Method,
        private val presetCreate: Method,
        private val envelopeCreate: Method,
        private val basicCtor: Constructor<*>,
        private val setInitialSharpness: Method,
        private val addControlPoint: Method,
        private val presetIds: Map<HapticPreset, Int>,
    ) {
        fun build(haptic: Haptic.Custom): VibrationEffect {
            val builder = builderCtor.newInstance()
            for (event in haptic.events) {
                val at = event.atMs.coerceAtLeast(0).toLong()
                when (event) {
                    is HapticEvent.Preset -> {
                        val id = presetIds.getValue(event.preset)
                        addPreset.invoke(builder, at, presetCreate.invoke(null, id, fraction(event.milliScale)))
                    }

                    is HapticEvent.Envelope -> {
                        val basic = basicCtor.newInstance()
                        if (event.milliInitialSharpness != HapticEvent.Envelope.DEFAULT_SHARPNESS) {
                            setInitialSharpness.invoke(basic, fraction(event.milliInitialSharpness))
                        }
                        for (p in event.points) {
                            addControlPoint.invoke(
                                basic,
                                fraction(p.milliIntensity),
                                fraction(p.milliSharpness),
                                p.durationMs.coerceAtLeast(1).toLong(),
                            )
                        }
                        addEnvelope.invoke(builder, at, envelopeCreate.invoke(null, basic))
                    }
                }
            }
            return build.invoke(builder) as VibrationEffect
        }

        companion object {
            fun resolveOrNull(): BuilderApi? =
                try {
                    val builder = Class.forName("android.os.VibrationEffect\$Builder")
                    val preset = Class.forName("android.os.VibrationEffect\$Preset")
                    val envelope = Class.forName("android.os.VibrationEffect\$Envelope")
                    val basic = Class.forName("android.os.VibrationEffect\$BasicEnvelopeBuilder")
                    val long = Long::class.javaPrimitiveType
                    val float = Float::class.javaPrimitiveType
                    BuilderApi(
                        builderCtor = builder.getConstructor(),
                        addPreset = builder.getMethod("addPreset", long, preset),
                        addEnvelope = builder.getMethod("addEnvelope", long, envelope),
                        build = builder.getMethod("build"),
                        presetCreate = preset.getMethod("create", Int::class.javaPrimitiveType, float),
                        envelopeCreate = envelope.getMethod("create", basic),
                        basicCtor = basic.getConstructor(),
                        setInitialSharpness = basic.getMethod("setInitialSharpness", float),
                        addControlPoint = basic.getMethod("addControlPoint", float, float, long),
                        presetIds =
                            mapOf(
                                HapticPreset.CLICK to preset.getField("PRESET_CLICK").getInt(null),
                                HapticPreset.TICK to preset.getField("PRESET_TICK").getInt(null),
                                HapticPreset.LOW_TICK to preset.getField("PRESET_LOW_TICK").getInt(null),
                            ),
                    )
                } catch (_: Throwable) {
                    null
                }
        }
    }

    private companion object {
        const val CACHE_SIZE = 256
        val FAILED = Any()

        fun fraction(milli: Int): Float = (milli / 1000f).coerceIn(0f, 1f)

        /** Presets as short pulses on a plain waveform; envelopes are skipped. Null when nothing is left. */
        fun waveformFallback(haptic: Haptic.Custom): VibrationEffect? {
            val pulses =
                haptic.events
                    .filterIsInstance<HapticEvent.Preset>()
                    .sortedBy { it.atMs }
            if (pulses.isEmpty()) return null
            val timings = ArrayList<Long>()
            val amplitudes = ArrayList<Int>()
            var cursor = 0L
            for (pulse in pulses) {
                val at = pulse.atMs.coerceAtLeast(0).toLong()
                if (at > cursor) {
                    timings += at - cursor
                    amplitudes += 0
                    cursor = at
                }
                val (ms, amplitude) =
                    when (pulse.preset) {
                        HapticPreset.CLICK -> 20L to 255
                        HapticPreset.TICK -> 10L to 160
                        HapticPreset.LOW_TICK -> 10L to 90
                    }
                timings += ms
                amplitudes += (amplitude * fraction(pulse.milliScale)).toInt().coerceIn(1, 255)
                cursor += ms
            }
            return VibrationEffect.createWaveform(timings.toLongArray(), amplitudes.toIntArray(), -1)
        }
    }
}
