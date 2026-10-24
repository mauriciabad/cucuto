# 0052. WhatsApp call ringtones: swap the URI at `RingtoneManager.getRingtone`, caller by notification name

- Status: accepted, pending the device run (#228). Defaults settled by the owner in #229: WhatsApp Business is
  included (see [Owner update](#owner-update-2026-10-24-229-whatsapp-business-too)); build: #273.
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
5. **Scope:** `staticScope=true`, and `scope.list` = `system` + `com.whatsapp` + `com.whatsapp.w4b` (owner, #229).
   Listed entries are recommended, never ticked automatically. The app requests each installed WhatsApp app at
   runtime with `requestScope()`, only after the explainer screen. `removeScope()` is offered when the feature is
   turned off.
6. **Defaults (#229):** 1:1 calls only, caller matched by name. ~~`com.whatsapp` only.~~ WhatsApp **and WhatsApp
   Business** (owner update below).
7. **Rejected:** writing WhatsApp's settings database (more intrusive, fragile across updates), and ringing from
   `system_server` (W-b), which risks silent calls.

## Consequences

- The implementation is blocked on #228. If the ringtone often loads before the call notification exists, this ADR
  is revised.
- The implementation PR adds the WhatsApp side to `:xposed`, updates `ModuleMetadataTest`, builds the explainer
  screen with the copy from the report (English and Spanish), and changes the "never hooks WhatsApp" copy as
  ADR 0051 says.
- Name collisions can leave some contacts on WhatsApp's own ringtone. The app explains why.

## Owner update 2026-10-24 (#229): WhatsApp Business too

> I expect WhatsApp Business  to also be fully compatible, unless there's a clear difficulty or downside.

Defaults 1 (1:1 calls only) and 3 (caller by name) stand. Default 2 changes: **WhatsApp Business
(`com.whatsapp.w4b`) gets automatic call ringtones too.** The static spike found no clear difficulty or downside:

- **Same mechanism, expected.** Business is WhatsApp's sibling app, with the same self-managed calls and the same
  call notification. The hook targets a public framework method, not WhatsApp's classes, so if Business rings
  through `RingtoneManager` too, the same single hook applies with no Business-specific code. #228 confirms it.
- **Separate contacts.** As everywhere in Cucuto (ADR 0031), Business has its own contacts: a Business call's
  caller name is matched only against names seen in Business message notifications, and its ring file map is keyed
  by package.
- **Opt-in per app.** `scope.list` lists `com.whatsapp.w4b` next to `com.whatsapp`. Listed entries are only
  recommended. The explainer screen requests the scope of each WhatsApp app that is installed. The status shows each
  scope. Someone who uses only one of the two apps grants only that one.
- **The small costs.** It adds one more process the module may run in, but only if the user grants it. The device
  run (#228) and the WaEnhancer X coexistence check have to cover both apps.

**Unverified until #228:** that Business rings through the same API and can open files in `Ringtones/cucuto/`. If it can't,
the hook falls back to Business's own ringtone, as for WhatsApp. If the device run shows Business rings in a way the
hook can't reach, this ADR records the difficulty and Business stays on its own ringtone (the owner's "unless there's
a clear difficulty"). The spike module gains `com.whatsapp.w4b` for that run (#228). The feature is built in #273.
