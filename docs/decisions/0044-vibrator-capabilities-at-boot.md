# 0044. Vibrator capabilities read at boot, off the notification path

- Status: accepted
- Date: 2026-10-24
- Issue: #190 (amends [ADR 0010](0010-production-hook-and-module-channels.md) "Haptics" and
  [ADR 0012](0012-haptics-effects-and-auto-haptics.md))

## Context

The module builds custom haptics for the device's `HapticCapabilities` (ADR 0012). Since #105 it read them lazily,
through `NotificationAttentionHelper`'s Context, on the first custom haptic, **inside `playVibration`**. On the
owner's phone (release v0.1.1) the first alert after boot logged `Slow playVibration: 26075us (budget 5000us)`,
followed by `Vibrator: builder, envelopes 255 points …`: the vibrator service calls and the class loading for the
envelope path cost five times the brief's 5 ms budget, on the path the owner wants as fast as stock WhatsApp
(ADR 0010, owner answer in #88).

Reading them earlier has one trap. `SystemVibratorManager` looks up the `vibrator_manager` binder once, in its
constructor, and every `Context` caches the manager it creates. The module's Context in `system_server` is the
system context (`ActivityThread.getSystemContext()`, also `NotificationAttentionHelper`'s), shared with
`NotificationManagerService`. Asking it for the `VibratorManager` before the service is registered would leave a
manager with a null binder in that cache for the rest of the boot, breaking vibration for the framework too. And
until the service is ready (or its HAL load succeeded) `getVibrator(id)` returns a `NullVibrator`, for which
`SystemVibrator.getInfo()` reports empty capabilities.

## Decision

- `CapabilitiesLoader` reads the capabilities on the module's background thread (`cucuto-events`, the one the
  warm-up and routing attach use), 2 s after `onSystemServerStarting`, then every 2 s while they are not ready, for
  at most 150 reads (5 minutes). The first read goes to `AlertHooks.useCapabilities`; if every read fails, generic
  capabilities do (`Vibrator unreadable, assuming: …`), as before.
- `VibratorReader.read()` returns "not ready" unless `ServiceManager.checkService("vibrator_manager")` is non-null
  (checked by reflection **before** the system context is asked for anything) and every vibrator's info is loaded
  (`getVibrator(id).hasVibrator()`; no vibrator at all is a valid answer).
- `useCapabilities` logs the one `Vibrator: …` line and builds a preset and an envelope for the real capabilities
  (uncached), so the first custom haptic does not load the build path this device takes.
- `playVibration` only does the volatile read of `HapticVibrations.capabilities`: a custom haptic keeps WhatsApp's
  vibration until they are known, and the notification path never calls a binder.
- The boot warm-up also runs a throwaway `AlertRouter` over a one-chat table, so the first routed alert does not load
  the shuffle bag, `forClip` or the decision event classes. The real router's bags stay untouched.

## Consequences

- On a normal boot the `Vibrator:` line appears within about 10 s of `Hooks installed`, well before the first alert. The device check is in [`docs/device-testing.md`](../device-testing.md).
- An alert in the few seconds before the read keeps WhatsApp's vibration (its sound is still swapped).
- The readiness check relies on two hidden but long-stable framework details (`ServiceManager.checkService` and
  `SystemVibratorManager` caching its binder). If the check breaks, the reads never succeed and custom haptics fall
  back to generic capabilities after 5 minutes. They never fall back to a read on the notification path.
