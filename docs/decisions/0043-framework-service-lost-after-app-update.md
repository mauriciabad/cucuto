# 0043. Framework service lost after an app update: tell the user to reboot

- Status: accepted
- Date: 2026-10-24
- Issue: #201

## Context

The signed release v0.1.2 was installed over v0.1.1 with `adb install -r` (same key, data kept). From then on,
Diagnostics said "Xposed framework: Not connected" and "Routing table: Not published yet", even after a force-stop
and relaunch. Without the libxposed framework service (`XposedService`), the app can write neither the Remote
Preferences nor the remote file of ADR 0024, so no voice change reaches the module. The Setup card still passed,
because the module had reported from `system_server` this boot.

**What is established.** LSPosed never handed the service to the app's process:

- libxposed's `XposedProvider.call("SendBinder")` logs `XposedProvider: binder received` (debug level) for every
  delivery. The relaunched process (04:59:55) never logs it.
- `/dev/binderfs/binder_logs/proc/<lspd pid>` lists which processes hold references to the daemon's binder nodes.
  `app.mauri.sukrara.poc`, another libxposed service module started at boot, holds one. The Cucuto process holds none.

**What is ruled out.**

- *R8.* The release dex keeps `XposedProvider`, the descriptor `io.github.libxposed.service.IXposedService`,
  `SendBinder` and the log line, the same as v0.1.1, which did connect. The decompiled release provider logs and then
  wraps the binder as it should. libxposed `service` 102 ships only `-dontwarn` rules and needs no more: AIDL
  dispatch uses the descriptor string and fixed transaction codes, and the manifest keeps the provider.
- *Our sink.* The provider logs before any Cucuto code runs, and `XposedServiceHelper` caches a binder that arrives
  before `registerListener`.

**The daemon side** (LSPosed 2.2.1 (7912), `daemon.apk` decompiled read-only; R8 names). `LSPModuleService` (`y8`) sends
the binder from `s(uid)` on a single-thread executor. Several events trigger it: process start (`IProcessObserver`,
forced), uid active (`IUidObserver`), "module enabled while running", and "cache updated, sending binder to late-start
module" after a module cache refresh. Deliveries are tracked per (package, uid) in two sets, sent and in flight. Both
are cleared when the uid is gone. None of the logged failures (`failed to send module binder …`, `failed to load
module`) appear in LSPosed's verbose log. The paths that fail silently in this build, because their debug logs are
stripped, are:

- the module lookup for the uid comes back empty;
- `modules_state` says the module is disabled;
- no provider after 3 attempts;
- the executor is busy.

LSPosed processed the update about 78 s after the install. That is when it posted its "module updated" notification,
and when its cache refresh replaced the module entry. App processes that started before then can still get the
previous entry's binder: that matches #154, where an updated debug build kept publishing. The processes started
after then got nothing. Which silent path applies needs a daemon stack dump or a reproduction (device steps in #201).
Either way, the fix is in the framework. A reboot restores the link.

## Decision

Make the app say the truth. Leave the transport and the build as they are.

1. **Detect "updated since boot".** `AppUpdateDetector` reads `PackageInfo.firstInstallTime` and `lastUpdateTime`.
   `StatusChecks.updatedSinceBoot` is true when an install replaced an earlier one after this boot. A fresh install
   after boot does not count: its module was never enabled, so "not enabled" stays the answer.
2. **Setup.** No service and updated since boot gives a new status, `ModuleCheck.NeedsReboot(afterUpdate = true,
   frameworkLost = true)`, a failing check. It wins over a module report from this boot, because no voice change can
   reach the module. Missing hooks still take precedence. Its only action is the instruction to reboot (the
   self-test cannot fix it). Onboarding resumes at its Reboot step.
3. **Diagnostics.** The framework row reads "Not connected: Cucuto was updated since the last boot, and LSPosed only
   reconnects to it after a reboot. Reboot your phone to publish voice changes." It replaces "Enable Cucuto in
   LSPosed …".
4. **No flicker.** LSPosed hands the service over shortly after the process starts (the daemon waits about 0.4 s). The
   status card holds back "no service" until 3 s after process start and shows "Checking" meanwhile. So the card
   does not flash a failure on every launch, and it does not celebrate when it turns green a moment later.
5. **Follow-up (#204).** A fallback that needs no service: when nudged, the module pulls the table from the app's
   event provider, which it already calls. It is tracked separately. It only helps once a module that has it was
   loaded at boot.

## Consequences

- After an update, the user is told to reboot, instead of being told to enable a module that is already enabled.
- If LSPosed delivers the service after an update (a process started before the daemon's refresh, or a future
  LSPosed), the connected path applies unchanged: `Active`, with the existing reboot hint for stale module code.
- The status card may show "Checking" for up to 3 s right after a cold start when no service ever arrives (module
  not enabled, or this bug).
- Nothing changes in the module, the routing transport or the R8 rules.
