# 0048. Routing table pull from the app's provider when the framework service is missing

- Status: accepted
- Date: 2026-10-24
- Issue: #204 (follows #201, [ADR 0043](0043-framework-service-lost-after-app-update.md))

## Context

The app publishes the routing table through the libxposed framework service: Remote Preferences
([ADR 0009](0009-data-layer-and-routing-table.md)) and, since #154, a remote file plus a nudge broadcast
([ADR 0024](0024-routing-updates-after-app-update.md)). Both need `XposedService`. On the owner's phone, LSPosed
2.2.1 sometimes never hands that service to an updated app until the next reboot, and logs nothing (the daemon's
executors are idle: the binder was dropped silently, ADR 0043). Until then no voice change reaches the module; #209
only tells the user to reboot.

ADR 0024 rejected a pull from the app's provider as the *primary* path, because it makes the module depend on the
app process. As a fallback that only runs when the service-based copies do not have the table, it is worth it.

## Decision

**App.**

1. Every new table also goes to the app's own copy, `noBackupFilesDir/routing/routing.table`
   (`PublishedTableFile`), framed by `RoutingFile` like the remote file and replaced atomically (temporary file and
   rename), whatever the connection.
2. Every publication ends with the existing `ROUTING_TABLE_PUBLISHED` nudge to package `android` with the sender
   identity shared, now **also without a service** and also after a failed service write. The nudge carries the
   generation (`RoutingContract.EXTRA_GENERATION`).
3. Without a service the publisher still publishes: `PublishState.Published(throughProvider = true)`. It does not
   prune the clip mirror then, because the module may still play an older table. When the service connects, the table
   is republished through it with a newer generation (generations are above both copies).
4. The event provider (`content://app.mauri.cucuto.events`) serves the copy with
   `openFile(content://app.mauri.cucuto.events/routing, "r")`. Only uid 1000 may open it, the same rule as
   `call("report")`: the table holds contact keys, and the module's identity in `system_server` cannot be spoofed by
   another app. A file descriptor avoids putting up to 0.5 MiB in a Binder reply. The provider remembers the
   generation it served, for Setup.

**Module** (`RoutingTableHolder`, `ProviderTableReader`).

1. On a nudge for generation *N* (coalesced: a burst runs one refresh, which catches up with the newest *N*):
   nothing if the table held is already *N* or newer; otherwise read the remote file; if that still does not give
   *N*, pull from the provider (`shouldPull`). A nudge without a generation (an app before this ADR) pulls only when
   the file is missing or unreadable.
2. Before the pull, the provider is verified exactly like the event channel: same package as the module and signed
   by the module APK's certificate (`AppProvider`, shared with `ProviderEventSender`, `providerRefusal`). The read
   uses an unstable `ContentProviderClient`, at most `RoutingFile.MAX_BYTES + 1` bytes, on the `cucuto-events`
   background thread. It never runs on the notification path and nothing escapes it.
3. Every source installs a table only when its generation is **newer** than the one held (it used to be "different",
   which with three sources could step back). Each generation logs one line naming its path:
   `Routing table <gen> (via preferences|file|provider): N keys, M voices`.
4. At boot, after attaching, the module reads the remote file once and pulls only when it does not exist. While the
   provider does not resolve (before the user unlocks the phone), that check is retried with the attach loop (every
   15 s, at most 10 min), quietly and without starting the app.
5. After a pull replaces the table, the module reports its status as after any reload, so Diagnostics' "Table in
   the module" catches up within seconds.

Order of preference: Remote Preferences, then the remote file, then the provider.

**Setup and Diagnostics.** "Reboot needed after an update" (#209) now shows only when the pull also failed: the
module neither fetched the app's latest generation from the provider in this process nor reported it since this
boot (`StatusChecks.providerDelivered`). A new publication counts as delivered once the module fetches it; the card
holds a change to "not delivered" back for 5 s, so it does not flash on every voice change. Diagnostics says the
module fetches voice changes from Cucuto meanwhile, and the routing table row says when a generation is published
for the module to fetch.

## Battery

- The module pulls only after a nudge that the app itself sent. The app is running then, so the pull starts no
  process: one in-process provider call, one file read (typically a few KiB, at most 1 MiB) and one decode (1 to 3 ms).
  No polling, alarm, wake lock or service.
- With the service working, a nudge finds the table already held (Remote Preferences) or in the remote file, so the
  provider is never called.
- At boot, the app is started only when the remote file is missing (one start per boot in that state, for example an
  install that never held the service). A present but stale file does not cause a boot pull; the next publication or
  the first event delivery after boot (which starts the app, which then publishes through the service) catches up.

## Consequences

- This is module code: it takes effect from the boot **after** the release that ships it. Until then the old module
  ignores the generation and keeps reading the remote file only, and Setup keeps asking for a reboot after updates.
- The module depends on the app process only as a fallback, and only for tables it could not get from LSPosed.
- The table is stored a third time, in the app's private storage (up to about 0.5 MiB).
- Device check: [`docs/device-testing.md`](../device-testing.md), section 4b, part C.
