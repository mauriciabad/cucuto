package app.mauri.cucuto.core.routing

/**
 * Vibration to play with a clip, in a form that maps one-to-one onto `VibrationEffect.Builder` (pack format
 * "Haptics"). All fractional values are integers in thousandths so the module parses without floating point
 * strings; `:core:haptics` and the module turn them into `VibrationEffect`s.
 */
public sealed interface Haptic {
    /** Leave the notification's own vibration (WhatsApp's) untouched. Pack mode `none` maps here. */
    public data object Keep : Haptic

    /** Play no vibration at all. Only a per-contact override can ask for this. */
    public data object Off : Haptic

    /** Use each clip's own envelope ([Clip.haptic]); clips without one behave like [Keep]. Pack mode `auto`. */
    public data object Auto : Haptic

    /** A timeline of presets and basic envelopes. Pack mode `custom`, or a per-contact override. */
    public data class Custom(
        val events: List<HapticEvent>,
    ) : Haptic {
        init {
            require(events.isNotEmpty()) { "A custom haptic needs at least one event" }
        }
    }
}

/** One event of a [Haptic.Custom] timeline. */
public sealed interface HapticEvent {
    /** Start time in milliseconds from the start of the clip. */
    public val atMs: Int

    /** `addPreset(atMs, Preset.create(preset, scale))`. */
    public data class Preset(
        override val atMs: Int,
        val preset: HapticPreset,
        val milliScale: Int = 1000,
    ) : HapticEvent

    /**
     * `addEnvelope(atMs, Envelope.create(BasicEnvelopeBuilder()…))`.
     *
     * @property milliInitialSharpness Initial sharpness in thousandths, or [DEFAULT_SHARPNESS] for "the first
     *   point's sharpness".
     */
    public data class Envelope(
        override val atMs: Int,
        val points: List<ControlPoint>,
        val milliInitialSharpness: Int = DEFAULT_SHARPNESS,
    ) : HapticEvent {
        init {
            require(points.isNotEmpty()) { "An envelope needs at least one control point" }
        }

        public companion object {
            public const val DEFAULT_SHARPNESS: Int = -1
        }
    }
}

/** A basic envelope control point: intensity and sharpness in thousandths, reached after [durationMs]. */
public data class ControlPoint(
    val milliIntensity: Int,
    val milliSharpness: Int,
    val durationMs: Int,
)

/** The presets the pack format allows, with their one-letter code in the routing table. */
public enum class HapticPreset(
    internal val code: Char,
) {
    CLICK('C'),
    TICK('T'),
    LOW_TICK('L'),
    ;

    internal companion object {
        fun fromCode(code: Char): HapticPreset =
            when (code) {
                'C' -> CLICK
                'T' -> TICK
                'L' -> LOW_TICK
                else -> throw RoutingFormatException("Unknown haptic preset '$code'")
            }
    }
}
