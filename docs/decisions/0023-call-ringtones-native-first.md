# 0023. Call ringtones: native first, Telecom hooks only as a fallback, WhatsApp calls guided

- Status: accepted for the static phase; the device run (#143) confirms or revises it. The #144 default stands (issue closed);
  decision 6 (WhatsApp calls) is amended by the owner's answer on #145 (see the end, and ADR 0051).
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

## Amendment (2026-10-24, owner answer on #145): automatic WhatsApp call ringtones

> Not hooking the WhatsApp app is a preference, not a requirement. [...] if we inform the user why we need each
> permision they will understand, and ultimately they dont have to give the permission if they wont use ringtones.
> [...] It is pretty important that ringtones can be set automatically in whatsapp calls too. so i would change the
> rule just for this feature.

Decision 6 changes from "guided fallback only" to "guided fallback by default, automatic with an opt-in WhatsApp
scope":

- Automatic per-contact WhatsApp call ringtones (fixed, rule-based or random per call) become a v1 goal, through an
  **optional WhatsApp scope** the user enables in LSPosed after a full-screen explanation ([ADR 0051](0051-optional-whatsapp-scope.md)).
  The feature works only while that scope is enabled.
- The guided per-chat ringtone (W-d) stays for everyone who does not enable it.
- Spike #215 finds the least intrusive hook from the M9 findings (report §5): identify the caller in `system_server`
  from WhatsApp's call notification, and substitute only the ringtone WhatsApp's player opens, ideally through one
  stable framework method in WhatsApp's process, reading nothing of messages. It re-evaluates W-b (ring the
  CallStyle notification from `system_server`) against that hook, and checks coexistence with WaEnhancer X on the
  device.
- Consequences above that said "WhatsApp call ringtones stay manual" and "No hook touches WhatsApp's audio" hold only
  for users without the optional scope. Phone calls are unchanged.
