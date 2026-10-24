# 0052. WhatsApp call ringtones: swap the URI at `RingtoneManager.getRingtone`, caller by notification name

- Status: accepted, pending the device run (#228). The owner can override the defaults in #229.
- Date: 2026-10-24
- Issue: #215 (report: [WhatsApp ringtone spike](../whatsapp-ringtone-spike.md))

## Context

ADR 0051 allows an optional, opt-in WhatsApp scope for one feature only: automatic WhatsApp call ringtones. The spike
found three things:

- WhatsApp plays its call ringtone through the public `RingtoneManager`/`Ringtone` API, defaulting to the system
  ringtone, and loops by starting playback again.
- No contact ID reaches that API. The only caller detail is the display name on WhatsApp's incoming-call notification.
- WaEnhancer X doesn't hook this path.

## Decision

1. **One framework hook in WhatsApp's process:** `RingtoneManager.getRingtone(Context, Uri)`. It replaces the URI
   argument only. It never hooks WhatsApp's own classes. With the feature off, the WhatsApp side installs no hook.
2. **The caller comes from WhatsApp's own incoming-call notification**, read with the public
   `NotificationManager.getActiveNotifications()`. Only the caller name, post time and group flag are read, and
   message notifications are skipped. Names are matched against the contacts Cucuto already knows.
3. **Any doubt keeps WhatsApp's ringtone:** no notification yet, a stale one, a group call, an unknown or ambiguous
   name, or a file that won't open.
4. **The ring file map comes from libxposed Remote Preferences** (`RoutingContract`), never from Cucuto's provider.
5. **Scope:** `staticScope=true`, and `scope.list` = `system` + `com.whatsapp`. Listed entries are recommended,
   never ticked automatically. The app requests WhatsApp at runtime with `requestScope()`, only after the explainer
   screen. `removeScope()` is offered when the feature is turned off.
6. **Defaults (#229):** 1:1 calls only, `com.whatsapp` only, caller matched by name.
7. **Rejected:** writing WhatsApp's settings database (more intrusive, fragile across updates), and ringing from
   `system_server` (W-b), which risks silent calls.

## Consequences

- The implementation is blocked on #228. If the ringtone often loads before the call notification exists, this ADR
  is revised.
- The implementation PR adds the WhatsApp side to `:xposed`, updates `ModuleMetadataTest`, builds the explainer
  screen with the copy from the report (English and Spanish), and changes the "never hooks WhatsApp" copy as
  ADR 0051 says.
- Name collisions can leave some contacts on WhatsApp's own ringtone. The app explains why.
