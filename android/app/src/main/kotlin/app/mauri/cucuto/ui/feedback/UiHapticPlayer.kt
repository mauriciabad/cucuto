package app.mauri.cucuto.ui.feedback

import android.annotation.SuppressLint
import android.content.Context
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.ui.hapticfeedback.HapticFeedback

/**
 * Plays [UiHaptic]s following Android's haptics design principles: predefined Compose types for standard touches
 * (they go through `View.performHapticFeedback`, which honors the system haptics setting), and
 * `VibrationEffect.Composition` primitives for the few moments of delight, with `USAGE_TOUCH` so the system "Touch
 * feedback" intensity (including off) applies. Never one-shots or waveforms: they ring on and feel buzzy.
 *
 * A small local builder: `:core:haptics` is reserved for the pack haptic schema (character vibrations). Move this
 * there if both ever need the same capability checks.
 */
internal class UiHapticPlayer(
    context: Context,
) {
    private val vibrator: Vibrator? = context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    private val supported = HashMap<List<Int>, Boolean>()
    private val touch = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)

    /**
     * Whether rich [haptic]s play as designed on this device (otherwise their fallback type plays).
     * `HapticPrimitive.id` only ever holds `VibrationEffect.Composition.PRIMITIVE_*` constants (see [Feedback]).
     */
    @SuppressLint("WrongConstant")
    fun supportsRich(haptic: UiHaptic.Rich): Boolean {
        val ids = haptic.primitives.map { it.id }.distinct()
        return synchronized(supported) {
            supported.getOrPut(ids) {
                vibrator?.hasVibrator() == true && vibrator.areAllPrimitivesSupported(*ids.toIntArray())
            }
        }
    }

    fun play(
        haptic: UiHaptic,
        standard: HapticFeedback?,
    ) {
        when (haptic) {
            is UiHaptic.Standard -> {
                standard?.performHapticFeedback(haptic.type)
            }

            is UiHaptic.Rich -> {
                if (supportsRich(haptic)) {
                    vibrator?.vibrate(haptic.toEffect(), touch)
                } else {
                    standard?.performHapticFeedback(haptic.fallback)
                }
            }
        }
    }
}

/**
 * Builds the composition with start offsets (`DELAY_TYPE_RELATIVE_START_OFFSET`, API 36), so each primitive starts
 * exactly [HapticPrimitive.atMs] after the first one whatever the device's primitive durations are.
 */
internal fun UiHaptic.Rich.toEffect(): VibrationEffect {
    val composition = VibrationEffect.startComposition()
    var previousAt = 0
    primitives.forEachIndexed { index, primitive ->
        if (index == 0) {
            composition.addPrimitive(primitive.id, primitive.scale, primitive.atMs)
        } else {
            composition.addPrimitive(
                primitive.id,
                primitive.scale,
                primitive.atMs - previousAt,
                VibrationEffect.Composition.DELAY_TYPE_RELATIVE_START_OFFSET,
            )
        }
        previousAt = primitive.atMs
    }
    return composition.compose()
}
