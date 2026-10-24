# 0024. Routing table updates after an app update: remote file and nudge

- Status: accepted
- Date: 2026-10-24
- Issue: #154

## Context

The app publishes the routing table through libxposed Remote Preferences (ADR 0009). The module in `system_server`
reads it at boot and follows it with an `OnSharedPreferenceChangeListener`. On the owner's phone (LSPosed 2.2.1
(7912), libxposed API 102), once the app had been updated since boot (`adb install -r`), later publications were
stored by LSPosed (`modules_config.db`) but never reached the module: no `Routing table <gen>` line, and the module
kept its boot table until the next reboot. Assigning a voice and hearing it is the app's basic loop, and every app
update (Play, a sideload, development) broke it until a reboot.

**Root cause (framework).** LSPosed keeps one per-module service binder in the daemon (`LSPInjectedModuleService`)
and stores Remote Preferences callbacks in it. The binder reaches `system_server` once, when the module is loaded at
boot. When the module package is updated, the daemon's module cache refresh first drops every cached module whose
APK path no longer exists (the old `/data/app/~~…/base.apk` is deleted), and then rebuilds the entry; because the old
entry is gone, it creates a **new** per-module binder. The app's `IXposedService` for the updated package points at
that new binder, so `updateRemotePreferences` writes the database and notifies a binder with no callbacks. The
module's callback stays registered on the old binder, which nobody notifies any more. The module cannot recover
through the API either: `XposedInterface.getRemotePreferences` caches one preferences object per group, still bound
to the old binder.

Evidence. The build on the phone is closed source, so it was read from its own bytecode (`daemon.apk` and
`framework.dex` pulled read-only from `/data/adb/modules/zygisk_lsposed/`, decompiled with jadx; R8 names):
the cache refresh `j7.b()` runs `u.values().removeIf(apk file missing)` and then
`p9.g = previous != null ? previous.g : new o8(packageName)`; `o8` is the per-module service with the callback map
keyed by (group, user); the app's `IXposedService` (`y8`) captures `p9.g` when it is created and notifies it after
writing; the framework's `getRemotePreferences` is `computeIfAbsent` on a per-module map, and its preferences hold
listeners in a strong `HashSet`. The public JingMatrix LSPosed source has the same code at tag v2.0
([`76141fe`](https://github.com/JingMatrix/Vector/tree/76141fed151f49b818144d54f2ebb6ab9a2df11c)):

- the refresh drops modules whose APK is gone:
  [`ConfigManager.java` L570–L576](https://github.com/JingMatrix/Vector/blob/76141fed151f49b818144d54f2ebb6ab9a2df11c/daemon/src/main/java/org/lsposed/lspd/service/ConfigManager.java#L570-L576),
  so the lookup at [L589](https://github.com/JingMatrix/Vector/blob/76141fed151f49b818144d54f2ebb6ab9a2df11c/daemon/src/main/java/org/lsposed/lspd/service/ConfigManager.java#L589)
  finds nothing and [L626](https://github.com/JingMatrix/Vector/blob/76141fed151f49b818144d54f2ebb6ab9a2df11c/daemon/src/main/java/org/lsposed/lspd/service/ConfigManager.java#L626)
  creates a new `LSPInjectedModuleService`;
- callbacks live in that instance:
  [`LSPInjectedModuleService.java` L26 and L38–L52](https://github.com/JingMatrix/Vector/blob/76141fed151f49b818144d54f2ebb6ab9a2df11c/daemon/src/main/java/org/lsposed/lspd/service/LSPInjectedModuleService.java#L26-L52);
- the app's service notifies the instance of the module it was created for:
  [`LSPModuleService.java` L55, L71 and L221](https://github.com/JingMatrix/Vector/blob/76141fed151f49b818144d54f2ebb6ab9a2df11c/daemon/src/main/java/org/lsposed/lspd/service/LSPModuleService.java#L221);
- the module side caches the preferences per group:
  [`LSPosedContext.java` L352–L362](https://github.com/JingMatrix/Vector/blob/76141fed151f49b818144d54f2ebb6ab9a2df11c/core/src/main/java/org/lsposed/lspd/impl/LSPosedContext.java#L352-L362).

Our usage was correct: the listener is strongly held and registered off the notification path. (ADR 0009 said Remote
Preferences hold listeners weakly; in LSPosed they are strong. Holding a reference is harmless either way.)

Remote **files** do not have this problem: the per-module binder resolves them by package name and user
([L54–L64](https://github.com/JingMatrix/Vector/blob/76141fed151f49b818144d54f2ebb6ab9a2df11c/daemon/src/main/java/org/lsposed/lspd/service/LSPInjectedModuleService.java#L54-L64);
`o8` transaction 3 in 7912), so the old binder still opens the file the updated app writes.

## Decision

Keep Remote Preferences as the primary path, and add a second path that survives an app update:

1. **App (`XposedRoutingSink.write`).** After committing the preferences, write the same encoded table to the remote
   file `routing.table` (`XposedService.openRemoteFile`, overwrite then truncate), framed by `RoutingFile`
   (`cucuto-routing-file 1 <bytes> <crc32>` and the UTF-8 table) because remote files have no atomic replace and the
   module may read during a write. Then send `app.mauri.cucuto.action.ROUTING_TABLE_PUBLISHED` with
   `setPackage("android")`, `FLAG_RECEIVER_REGISTERED_ONLY | FLAG_RECEIVER_FOREGROUND` and
   `BroadcastOptions.setShareIdentityEnabled(true)`. Both steps are best effort and logged; the preferences stay the
   source of truth, and the broadcast carries no data.
2. **Module.** Right after boot, on its background thread, register a receiver with the system context (`RECEIVER_EXPORTED`,
   delivered on the `cucuto-events` handler; retried with the attach loop). `onReceive` accepts only
   `getSentFromPackage() == moduleApplicationInfo.packageName`, then asks a coalescer to re-read the file once on the
   background thread (`XposedInterface.openRemoteFile`, at most 1 MiB). A torn or corrupt file is ignored; the next
   nudge, which the app sends after it finishes writing, reads the complete one.
3. **One load per generation.** Both paths install a table only if its generation differs from the one held, under one
   lock, so each publication logs one `Routing table <gen>` line whichever path arrives first.
4. **Truthful Diagnostics.** After a publication replaces the table, the module asks the event reporter for a
   status-only delivery on the usual schedule (debounced 2 s, at most one per 10 s; never for the first read at boot,
   so a boot does not wake the app). The Diagnostics row "Table in the module" compares the reported generation with
   the app's: "the app's latest", "not yet the app's latest (N)", or "reported before the last reboot".

The notification path is unchanged: a volatile read of the table, no Binder call, no IO. Nothing new can throw in
`system_server`: the receiver, the coalescer, the file read and the reload all catch everything and keep the
previous table.

### Alternatives considered

- **Fix our usage:** nothing to fix (see Context).
- **Re-request the preferences on `PACKAGE_REPLACED`:** the framework returns the cached object bound to the old
  binder, and a new group name would only read once and leak a callback per call.
- **Hot reload** (`XposedService.hotReloadModule`, API 102): built for loading new module code after an update, it
  re-runs the module inside `system_server` (unhook and rehook the notification path). Far more intrusive than a
  file read, and libxposed says not to use it for configuration.
- **Polling the generation or checking it on each alert:** polling costs battery while idle; a check after each alert
  plays the old voice once after every change.
- **Pulling the table from the app's provider on nudge:** works, but makes the module depend on the app process for
  its data and adds a provider method; the remote file keeps LSPosed as the only transport.

## Consequences

- The table is stored twice in LSPosed (preferences and file, up to about 0.5 MiB each for the largest tables).
- Module changes take effect after a reboot (`system_server` keeps the module it loaded at boot); the app side works
  as soon as the new APK is installed. Until the phone reboots onto the new module, the old module ignores the nudge.
- A table published while the module runs code from before the update is still decoded by that old code; tables in
  a newer format are ignored as before (ADR 0009).
- If LSPosed fixes the callback loss, both paths still converge and the nudge only costs one file read per
  publication.
- Device check: [`docs/device-testing.md`](../device-testing.md), section 4b.
- Follow-up: when the app has no framework service at all (LSPosed dropped it after an update), the module pulls the
  table from the app's provider instead ([ADR 0048](0048-routing-table-pull-without-the-service.md)).
