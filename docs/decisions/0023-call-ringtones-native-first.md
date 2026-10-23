# 0023. Call ringtones: native first, Telecom hooks only as a fallback, WhatsApp calls guided

- Status: accepted for the static phase; the device run (#143) confirms or revises it. The owner may override the
  two defaults in #144 and #145.
- Date: 2026-10-24
- Issue: #50

## Context

Ringtones are part of v1 (owner, #59). The brief plans phone-call ringtones through `CUSTOM_RINGTONE`, an optional
Telecom hook for "random per call" (which "also allows a custom vibration per contact"), and an investigation of
WhatsApp calls. The M9 static spike ([report](../m9-report.md)) found on the owner's Android 17 Pixel:

- Telecom runs inside `system_server` (`service-telecom.jar` from the `com.android.telephonycore` APEX, on
  `SYSTEMSERVERCLASSPATH`), so the `system` scope covers it.
- Telecom plays `CUSTOM_RINGTONE` itself, through a local `MediaPlayer` in `system_server`, and falls back to the
  default ringtone when the URI cannot be opened.
- Android 17's ringtone haptics customisation is enabled on this Pixel: a ringtone URI with a `vibration_uri`
  parameter makes Telecom play that vibration file instead of the default pattern, with no hook.
- WhatsApp calls are self-managed (Telecom does not ring for them), and WhatsApp's call channel has no sound.
  WhatsApp rings in its own process.
- Telecom loops the whole file; pack loop points have no runtime equivalent.

The owner's "minimum intrusion" principle (ADR 0002) favours public APIs over hooks wherever they reach.

## Decision

1. **Phone calls use only public APIs by default.** `CUSTOM_RINGTONE` with files exported to `Ringtones/cucuto/`
   through MediaStore (#52). Ringtones never go through Cucuto's content provider (a cold start would delay the ring).
2. **Per-contact call vibration uses `vibration_uri`** when `config_ringtoneVibrationSettingsSupported` is true and
   the device run finds a path `system_server` can read (test V2). Otherwise calls keep the system vibration.
3. **"Random per call" re-rolls `CUSTOM_RINGTONE` after each call** (phone-state idle), plus at boundary alarms and
   on app start (#144). No hook.
4. **Two Telecom hooks are prepared but not shipped**: `Call.getRingtone()` (return value) and
   `Ringer.vibrateIfNeeded(…)` (argument 2). They are substitutions downstream of every Telecom decision, ignore
   self-managed calls, and go into `:xposed` only if the device run passes and a native path above fails.
5. **Ring files are rendered for looping**: the intro once, then the loop section repeated to at least 90 s, at export
   time (#51/#52).
6. **WhatsApp calls get the guided fallback in v1** (#145): export the character's ringtone and guide the user to
   WhatsApp's per-chat call ringtone. No hook touches WhatsApp's audio. Making WhatsApp's call notification ring
   from `system_server` is recorded as a possible later opt-in experiment, not planned.

## Consequences

- M9 needs no new hook in the default path. The module's hooks stay `playSound` and `playVibration`.
- The app needs `READ_CONTACTS`, `WRITE_CONTACTS` (already in the brief) and `READ_PHONE_STATE` (for the re-roll).
- Pack haptics for calls must be serialisable as Android vibration XML (`VibrationXmlParser`); `:core:haptics` gains a
  writer (part of #52).
- Devices without the ringtone-vibration config ignore `vibration_uri`; the app says so in the ringtone picker.
- WhatsApp call ringtones stay manual (one file per chat, no variants), as the brief's risk table anticipated.
- `spikes/m9/` holds the hooks and a supervised test script; the device run appends its results to the report.
