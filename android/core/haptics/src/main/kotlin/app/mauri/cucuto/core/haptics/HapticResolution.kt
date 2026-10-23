package app.mauri.cucuto.core.haptics

import app.mauri.cucuto.core.routing.Haptic

/**
 * The haptic to play with one clip of a voice ([app.mauri.cucuto.core.routing.Voice.haptic]): [Haptic.Auto] becomes
 * the clip's own envelope ([app.mauri.cucuto.core.routing.Clip.haptic]), or [Haptic.Keep] when the clip has not
 * been analysed (or is silent). The result is never [Haptic.Auto]:
 *
 * - [Haptic.Keep]: leave the notification's vibration as it is.
 * - [Haptic.Off]: play no vibration.
 * - [Haptic.Custom]: play [HapticEffects.create]'s effect, or keep the original when it returns null.
 */
fun Haptic.forClip(clipHaptic: Haptic.Custom?): Haptic = if (this == Haptic.Auto) clipHaptic ?: Haptic.Keep else this
