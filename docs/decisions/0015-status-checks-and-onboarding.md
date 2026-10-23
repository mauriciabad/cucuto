# 0015. Status checks and first-run onboarding

- Status: accepted (owner may override, see the `question` issue linked from the PR)
- Date: 2026-10-24
- Issue: #24

## Context

The brief's first screen is "one card that turns green when everything works: module active in `system_server`,
correct LSPosed scope, WhatsApp detected, at least one pack installed", with a one-tap fix or a clear instruction
for each failing check. The app needs an honest onboarding as well: root, LSPosed, enabling the module, rebooting
and the self-test. The app has three sources of truth about the module:

- the module's own health report (hooks installed and missing, boot time), which rides along with every batch of
  decision events from `system_server` (ADR 0010) and is kept by `ModuleEventSink`;
- the libxposed service the framework binds to the app (`XposedServiceHelper`, already held by
  `XposedRoutingSink`): `getScope()` returns the module's scope, and API 102's `getRunningTargets()` lists the
  processes running the module, each `UP_TO_DATE` or `STALE` (old code after an app update). The framework calls
  uid and process name "diagnostic values only";
- the self-test notification (`SelfTestNotifier`), which makes the module report within about 10 s.

## Decision

**Module active in `system_server`.** Pass only on a report from **this boot** (the module's boot time,
`currentTimeMillis - elapsedRealtime`, within 60 s of the app's) with no missing hooks. A report from an earlier boot
proves nothing. Otherwise, in order: no framework service means "not enabled" (instruction and the install guide);
an `Unsupported` framework fails with its name; a `STALE` target in `system_server` means "reboot after the update";
a readable target list without `system_server` (uid 1000 in any user, or the name) means "enabled but not loaded:
reboot" (the self-test is offered too); anything else, including an unreadable target list, asks for the self-test (one tap). Missing hooks fail
and link to Diagnostics. A this-boot report wins over the framework state, so the check is right even before the
service binds.

**Scope.** Read with `getScope()`: pass on exactly `system` (System Framework, ADR 0002); `android` or nothing means
System Framework is missing; any other entry (WhatsApp included) fails with the list. When the scope cannot be read
(module disabled, framework error) the check is "Unknown" and says how to verify it in the LSPosed manager, unless
the module reported from `system_server` this boot: then System Framework is necessarily in the scope and
`staticScope=true` keeps other apps out, so it passes as "inferred".

**WhatsApp.** `com.whatsapp` and `com.whatsapp.w4b` in the manifest's `<queries>` (the documented package
visibility for specific packages; no `QUERY_ALL_PACKAGES`), read with `getApplicationInfo`. Either one installed and
enabled passes; a disabled one opens its app settings; none opens the Play Store page.

**Packs.** At least one row in `installed_packs`, not counting the debug test voice (`app.mauri.cucuto.devtest`).
The fix opens the Packs screen and the website's free packs.

The checks are a pure function (`StatusChecks.evaluate`) over plain inputs, unit-tested. `StatusViewModel` gathers
the inputs, re-reading the framework and packages on every resume (they change in LSPosed and the Play Store).

**Turning green.** The card is themed with the leaf character accent (`CharacterTheme`, animated cross-fade) when
every check passes. The first time ever, and each time it turns green while the screen is open, it fires
`Feedback.Success` and the cuckoo door pops open with a particle burst, all from that beat. Opening the app on an
already green card stays quiet (the door is drawn open).

**Onboarding.** Five steps: what Cucuto does; requirements and risks (boot loops from a `system_server` module,
root and LSPosed weakening security and Play Integrity, Android updates breaking hooks, the module reading sender
names and chat IDs, how to recover, a link to https://cucuto.mauri.app/install/); enable the module; reboot; post
the self-test. Module and scope checks are live in the steps. It is the start destination until finished or skipped
(`onboarding_completed` in the settings DataStore); a first-run user returning after the reboot resumes at the
matching step. Status reopens it as the "Setup guide". Back inside the flow goes to the previous step
(`NavigationBackHandler`, androidx.navigationevent 1.2.0).

## Consequences

- `:core:data` exposes `XposedFramework` (implemented by `XposedRoutingSink`, which owns the single
  `XposedServiceHelper` listener) and `OnboardingSettings`.
- Right after a reboot the module check asks for the self-test until the first alert: the module reports only
  with decision events. A periodic report would remove that step but costs work in `system_server`.
- The "reboot" readings rely on LSPosed's API 102 target list. If a framework lists `system_server` differently,
  the check could ask for a reboot that already happened; the reboot instruction therefore also offers the
  self-test, whose report from this boot settles the check.
- Device verification of the live checks is part of the next supervised session (`docs/device-testing.md`).
