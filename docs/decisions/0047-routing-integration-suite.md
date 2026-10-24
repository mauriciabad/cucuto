# 0047. Routing integration suite

- Status: accepted
- Date: 2026-10-24
- Issue: #207

## Context

The routing table connects the app and the module in `system_server`. Many features changed it, each in its own PR
with its own unit tests: voices and the shuffle bag, the self-test `S` line and the `file://` clip mirror (#151),
compact clip envelopes (`H` lines and the `h` field, #149), haptic overrides (#172), group policy, per-sender voices
and WhatsApp Business (#159), Simulate (#171), day and night `W` lines (#200), the remote file and nudge after an app
update (#160) and the vibrator's capabilities at boot (#205). Nothing tested them together, nor the table's size with
all of them on, nor whether the module that shipped in a release still reads what today's app writes.

## Decision

**One JVM suite in `:xposed`'s unit tests** (`src/test/kotlin/.../integration/`), because it must reach the module's
`internal` hooks. It runs the real code end to end and fakes only the platform edges:

- App: Room in memory, the real repositories and settings, `RoutingTableSource`, `RoutingTableBuilder` with the real
  `PackManifestCatalog` and `ClipMirrorStore`, and `RoutingPublisher`.
- Edge: `FakeXposedService` (Remote Preferences, the remote file, the nudge; the app's provider is the real
  `PublishedTableFile`, pulled when the service is missing, ADR 0048), `FakeFramework` (a libxposed
  `XposedInterface` that records hooks, `NotificationAttentionHelper` and `NotificationRecord` stand-ins), and the
  clip analysis (`AutoHapticAnalyzer` needs MediaCodec, so envelopes are written as its output file).
- Module: `RoutingTableHolder`, `AlertRouter`, `HapticVibrations` with `HapticEffects`, and `AlertHooks`, fed real
  `Notification`, `Person`, `ShortcutInfo` and `StatusBarNotification` objects. Robolectric provides the framework,
  pinned to API 37 (`src/test/resources/robolectric.properties`).

`:xposed` gets `:core:data`, Room, DataStore and Robolectric as **test** dependencies only; nothing ships in
`system_server`.

**Demo packs.** The suite installs the CC0 packs of `packs/` from the packer's `pack.json` output, checked in as
`src/test/resources/integration/demo-packs/<id>.json`, and copies each clip from `packs/` at test time (no media
enters `android/`). CI's `packs` job fails when a checked-in manifest differs from what the packer builds, and
`packs/**` now triggers the Android jobs.

**Compatibility.**

- `ReleasedRoutingReader` is the table reader of Cucuto 0.1.1 and 0.1.2 (`RoutingCodec` at 4801934), frozen. That
  module keeps running after an app update until the next reboot (ADR 0024), so the suite checks that it reads
  today's live table and every golden, ignoring only the day and night conditions it does not know.
- Golden tables (`src/test/resources/integration/golden/`) are frozen snapshots of what the app published, recorded
  with `-Pcucuto.recordGolden=<file>`. Each must decode, route and re-encode to the same text with today's code.
  Goldens are never rewritten; a new one is added when the format grows.

**Size.** A heavy user with everything on goes through the real builder and publisher: 500 contacts with two keys
each, 100 Business chats, 50 groups (15 per sender), 120 characters of 12 clips with a distinct 16-point envelope on
every clip, six distinct clip conditions, 60 contacts with toggles or their own haptic, and mirrored clips. The table
must publish with every envelope and stay under `MAX_ENCODED_CHARS`, measured with the phone's mirror path.

**Speed and allocation.** On the newest golden, a decision takes well under a microsecond and must stay under
100 us (median) and 1 ms (99th percentile). In steady state it allocates only its result (the decision, its
identity and the table's match, 128 bytes): bound 160 bytes. Through both hooks under Robolectric, 200 warm alerts
stay inside the brief's 5 ms.

## Consequences

- The suite found a growing shuffle bag: with one clip heavier than all others together (the demo frog by day), the
  no-repeat rule left its extra copies in the bag at every refill, so the bag and every draw grew for as long as
  `system_server` ran (6.5 KB and 11 us per draw after 20,000 alerts). Fixed, and draws no longer allocate.
- The heavy user's table is 250,364 of 262,144 characters (95 %). The publisher's fallback still drops clip envelopes
  first, but any growth of the format must now look at size; tracked in #223.
- A change to the pack format, the routing table, the hooks or the decision event is exercised end to end by
  `./gradlew :xposed:testDebugUnitTest` (about 30 s).
