package app.mauri.cucuto.core.haptics

import android.os.VibrationEffect
import android.os.Vibrator
import app.mauri.cucuto.core.routing.Haptic

/**
 * Builds the [VibrationEffect] for a haptic timeline ([Haptic.Custom]: pack `custom` mode, a per-contact override or
 * a clip's auto envelope) **inside `system_server`** (the module, `:xposed`), following the pack format's one-to-one
 * mapping:
 *
 * - `{ "at", "preset", "scale" }` → `addPreset(at, Preset.create(PRESET_…, scale))`
 * - `{ "at", "initialSharpness", "envelope" }` → `addEnvelope(at, Envelope.create(BasicEnvelopeBuilder()…))`
 *
 * `VibrationEffect.Builder` is used whenever it exists ([builderAvailable]); it falls back on its own, per element,
 * when the hardware lacks a preset or envelope support. Envelopes are first fitted to the device's
 * `VibratorEnvelopeEffectInfo` limits. Without the Builder, the effect is built with public APIs exactly like
 * [PublicHapticEffects] does ([HapticPlanner]). `WaveformEnvelopeBuilder` is never used: it has no fallback and
 * depends on the device's frequency profile.
 *
 * **Never use this object in the app process.** On API 37.1 the Builder is blocked hidden API for apps, and even the
 * [builderAvailable] probe logs `hiddenapi` denials there (issue #139). The app uses [PublicHapticEffects].
 *
 * Needs no Context and never throws: safe to call from `system_server`. Cheap enough for the notification path (no
 * IO, a few small allocations), but callers that play the same haptic often can cache the result:
 * [VibrationEffect]s are immutable.
 */
object HapticEffects {
    /** The effect for [haptic] on a device with [capabilities], or null when there is nothing playable. */
    fun create(
        haptic: Haptic.Custom,
        capabilities: HapticCapabilities,
    ): VibrationEffect? = PublicEffects.create(haptic, capabilities, ::build)

    /** [create] with the capabilities of [vibrator]. Prefer reading them once with [capabilities]. */
    fun create(
        haptic: Haptic.Custom,
        vibrator: Vibrator,
    ): VibrationEffect? = create(haptic, capabilities(vibrator))

    /** What [vibrator] can play in this process, the Builder included when [builderAvailable]. Never throws. */
    fun capabilities(vibrator: Vibrator): HapticCapabilities = vibrator.readCapabilities(builderAvailable)

    internal fun build(plan: HapticPlan): VibrationEffect? =
        if (plan is HapticPlan.Timeline) BuilderEffects.timeline(plan) else PublicEffects.build(plan)

    /**
     * Whether `VibrationEffect.Builder` can be used in this process: API 37.2, or its classes probed on an earlier
     * build (the owner's 37.1 phone has them, usable from `system_server` only).
     */
    val builderAvailable: Boolean get() = BuilderEffects.available
}
