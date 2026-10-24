package app.mauri.cucuto.core.creator

import app.mauri.cucuto.core.routing.ControlPoint
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import kotlin.math.roundToInt
import app.mauri.cucuto.packspec.ControlPoint as PackControlPoint
import app.mauri.cucuto.packspec.HapticEvent as PackHapticEvent

/**
 * Converts between the haptic editor's timeline (`:core:routing`, thousandths) and a pack's `haptic.events`
 * (pack-spec, fractions), so a character's custom vibration can be edited with the haptic editor (#185) and written
 * into the pack exactly as `docs/pack-format.md` "Haptics" describes. The same rules as `HapticJson` in `:core:data`
 * (which reads installed packs): a scale of 1 and the default initial sharpness are left out, fractions keep three
 * decimals, and anything unexpected degrades instead of throwing.
 */
object CreatorHaptics {
    /** The pack events for [haptic]. */
    fun toPack(haptic: Haptic.Custom): List<PackHapticEvent> =
        haptic.events.map { event ->
            when (event) {
                is HapticEvent.Preset -> {
                    PackHapticEvent(
                        at = event.atMs.toLong(),
                        preset = event.preset.name,
                        scale = event.milliScale.takeIf { it != MILLI_ONE }?.let(::fraction),
                    )
                }

                is HapticEvent.Envelope -> {
                    PackHapticEvent(
                        at = event.atMs.toLong(),
                        initialSharpness =
                            event.milliInitialSharpness
                                .takeIf { it != HapticEvent.Envelope.DEFAULT_SHARPNESS }
                                ?.let(::fraction),
                        envelope =
                            event.points.map {
                                PackControlPoint(
                                    intensity = fraction(it.milliIntensity),
                                    sharpness = fraction(it.milliSharpness),
                                    ms = it.durationMs.toLong(),
                                )
                            },
                    )
                }
            }
        }

    /** The editor's timeline for pack [events], or null when there is nothing to edit (no readable event). */
    fun fromPack(events: List<PackHapticEvent>): Haptic.Custom? =
        events.mapNotNull(::event).takeIf { it.isNotEmpty() }?.let(Haptic::Custom)

    private fun event(event: PackHapticEvent): HapticEvent? {
        val at = event.at.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        val preset = event.preset
        if (preset != null) {
            val type = HapticPreset.entries.firstOrNull { it.name == preset } ?: return null
            return HapticEvent.Preset(at, type, milli(event.scale ?: 1.0))
        }
        val points =
            event.envelope
                ?.map {
                    ControlPoint(
                        milliIntensity = milli(it.intensity),
                        milliSharpness = milli(it.sharpness),
                        durationMs = it.ms.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                    )
                }?.takeIf { it.isNotEmpty() } ?: return null
        val sharpness = event.initialSharpness?.let(::milli) ?: HapticEvent.Envelope.DEFAULT_SHARPNESS
        return HapticEvent.Envelope(at, points, sharpness)
    }

    private fun fraction(milli: Int): Double = milli.coerceIn(0, MILLI_ONE) / MILLI_ONE.toDouble()

    private fun milli(value: Double): Int = (value.coerceIn(0.0, 1.0) * MILLI_ONE).roundToInt()

    private const val MILLI_ONE = 1000
}
