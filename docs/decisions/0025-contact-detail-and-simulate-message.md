# 0025. Contact detail and "Simulate message"

- Status: accepted (defaults open for the owner in #169)
- Date: 2026-10-24
- Issue: #26

## Context

The brief's contact detail (screen 4) shows the chat's character, its clip pool with per-clip toggles, the ringtone,
the haptic override, the group policy and a **"Simulate message" button that runs the real pipeline**. The owner's
priority is a phone that can be tested without waiting for a real WhatsApp message ("it is late at night and no one
will probably text me", owner log). So the simulation has to play exactly what the chat's next message will do: the
clip the module would pick (same table, same rotation, never the same clip twice in a row), with the same haptic,
through the system's notification sound and vibration.

Two things already existed. The Diagnostics self-test posts a Cucuto notification on channel `cucuto_self_test` that
the module swaps for a fixed chirp (ADR 0010). `AlertRouter` (`:xposed`) is pure Kotlin: it decides a notification
from the in-memory routing table and a per-speaker shuffle bag.

Options considered for the simulation:

1. **In-app only.** Run the same rules in the app and play the clip with `MediaPlayer`. Works without the module, but
   it is not the real pipeline: it does not show whether the module holds the current table, whether SystemUI can open
   the clip, or how the vibration feels with notification attributes in `system_server` (app-side haptics also hit
   hidden-API limits, #139).
2. **Post a notification that looks like WhatsApp's.** Impossible and unwanted: the module keys on the posting
   package, which `system_server` sets from the caller's uid. Cucuto cannot post as WhatsApp, and the module must
   never trust a package name it did not verify.
3. **A simulate channel on Cucuto's own notification (chosen).** Like the self-test, but the notification names the
   chat to simulate, and the module decides it exactly as a real alert of that chat.

## Decision

**Module path.** Cucuto posts a notification on a new channel, `cucuto_simulate`, with two string extras:
`app.mauri.cucuto.simulate.package` (the messenger) and `app.mauri.cucuto.simulate.key` (the conversation key).
`RecordReader` reads those extras only when the record's package is `app.mauri.cucuto` and the channel is
`cucuto_simulate`. `AlertRouter.simulate` runs the normal messenger path for that `(package, key)`: global switch,
routes, per-sender fallback, the **same shuffle bag** as real messages (`package + speaker key`), and the auto haptic
resolved against the drawn clip. Then it reports the decision under Cucuto's own package with detail `simulated`, so
the app logs it in the decision log but never counts it as a message in "recent senders".

Minimum intrusion and spoofing:

- Only a notification that `system_server` attributes to `app.mauri.cucuto` is read, and `NotificationManagerService`
  only lets the app that owns a package post as it. No other app can trigger a simulation.
- The only effect is on that notification's own sound and vibration. No other app's notification changes, and
  nothing is returned to the poster: the decision goes to Cucuto's signature-checked event provider (ADR 0010).
- The target must be WhatsApp or WhatsApp Business, and the key at most 256 characters. Anything else is reported as
  `NO_KEY` and the notification keeps its channel defaults.
- A simulation draws from the chat's real rotation, so its clip counts as played, and the next real message won't
  repeat it. That is the faithful choice: the simulation is the chat's next alert.

The notification uses `CATEGORY_REMINDER`, like the self-test, so it is exempt from the post-boot "avalanche"
muting. It clears itself after 30 s. Its channel has the default notification sound and the default vibration (no
custom pattern), so a contact that keeps WhatsApp's vibration feels the system default. Android's per-app cooldown
mutes an app's second alert within 10 s and lowers it within 60 s, so the self-test and the simulation share one
12 s spacing (`OwnAlertSpacing`), and the screen says "Wait N s".

**Choosing the path.** A module loaded before this build ignores `cucuto_simulate` and would just play the default
sound, so the app only uses the module path when `system_server` runs this build. `SimulatePaths.choose` asks LSPosed
(`getRunningTargets`, libxposed API 102): `system_server` `UP_TO_DATE` means the installed APK's module code is
running. `STALE` (updated since boot) or no `system_server` target means a reboot is needed. When LSPosed cannot answer,
a module report from this boot counts if the app was installed before the boot. A report from this boot that lists
missing hooks means the module cannot play anything. The screen re-checks on every resume and again on each tap.

**In-app fallback.** Otherwise the same `AlertRouter` runs in the app, on the table the app publishes
(`RoutingTableSource`), and the result plays with `VoicePreview.playClip`: notification audio attributes and the same
`:core:haptics` builder. The app has its own shuffle bags, so the rotation follows the same rules, but not the same
position as the module's. The screen labels it clearly ("In-app preview: the module runs older code until the next
reboot…"), and says when a real message would keep WhatsApp's vibration or tone instead.

**The rest of the screen.** The header (shared-element avatar from Home, "Speaks as …", the pack) leads to the picker
("Change character"). The trash action removes the voice after a confirmation. The clip pool lists every clip with a
play button and a per-contact switch (`AssignmentEntity.disabledClips`); the last enabled clip can't be switched off,
because that would silently drop the voice. Groups show the group policy control from #20 (`GroupPolicySetting`,
stored with `ChatDirectory.setGroupPolicy`). Vibration is a read-only section for now, the slot for the per-contact
haptic override (#32/#33). "Recent alerts" shows
the chat's last 8 decision log entries (its keys, its contact, and its simulations), each marked "Simulated" or
"Message". When a simulation went through the module, its report replaces "Sent through the module" with "The module
played <clip>".

**Navigation.** Home opens contact detail for a chat that has a voice. A chat without one still goes straight to the
picker, so setting up a contact stays one tap shorter. The recent-senders strip and the "Assign a voice" sheet also go
to the picker. The ringtone slot waits for M9 (ADR 0023).

## Consequences

- After installing this build, "Simulate message" is an in-app preview until the next reboot. After the reboot it
  goes end to end through the module, and the decision log proves it ("Simulated", with the clip).
- The module change is small and fail-soft: two string reads on Cucuto's own simulate channel, and a router branch
  that reuses the messenger path. Older apps never post on that channel.
- The decision log now holds simulated entries under `app.mauri.cucuto` with the chat's key. Diagnostics shows them
  like the self-test entries.
- `VoiceCharacter` gained `clipPaths` (pack paths next to the files), which the clip toggles need.
