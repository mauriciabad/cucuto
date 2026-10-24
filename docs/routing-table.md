# Routing table, format version 1

The routing table is everything the module needs to swap a WhatsApp alert: which contact keys have a voice, which
clips that voice plays, and which vibration goes with them. The app builds it from its database and publishes it to
the module through libxposed Remote Preferences, a libxposed remote file and its own provider; the module keeps it
in memory. This page is the contract between
the two. The code lives in `android/core/routing` (`RoutingTable`, `RoutingCodec`, `RoutingContract`).

## Transport

| | App (`:core:data`) | Module (`:xposed`, in `system_server`) |
| --- | --- | --- |
| API | `XposedServiceHelper.registerListener` → `XposedService.getRemotePreferences("routing")` | `XposedInterface.getRemotePreferences("routing")` |
| Access | read/write (`edit().putString(…).commit()`) | read-only, plus `OnSharedPreferenceChangeListener` |
| Keys | `table` (string, the encoded table), `generation` (long, the latest publication's; see size limits) | same |

- The app republishes whenever contacts, keys, assignments, installed packs or the global switch change
  (`RoutingPublisher`). A table equal to the published one is not written again. Tables with conditional clips
  also change when the day and night place changes and once a day while the app runs (their span moves forward);
  there is no background job, since the published span covers 400 days.
- The service only exists while the module is enabled in LSPosed and the framework supports Remote Preferences
  (`PROP_CAP_REMOTE`). Until then nothing is written; the database stays the source of truth and the table is
  published as soon as the service connects.
- The module attaches lazily on the first lookup (not during boot), decodes on the change callback, and swaps one
  volatile reference. The notification path only does hash map lookups.
- LSPosed stops notifying a module that has been running since before an app update (until the next reboot), so
  after each write the app also mirrors the table to the remote file `routing.table` (`XposedService.openRemoteFile`,
  framed by `RoutingFile`: a `cucuto-routing-file 1 <bytes> <crc32>` line, then the encoded table in UTF-8) and sends
  the broadcast `app.mauri.cucuto.action.ROUTING_TABLE_PUBLISHED` to package `android` with its identity shared and
  the generation in the long extra `generation`. The module accepts it only from its own package and re-reads the
  file (`XposedInterface.openRemoteFile`) on its background thread
  ([ADR 0024](decisions/0024-routing-updates-after-app-update.md)).
- LSPosed sometimes never hands an updated app its service until a reboot
  ([ADR 0043](decisions/0043-framework-service-lost-after-app-update.md)). So the app also keeps its own copy
  (`noBackupFilesDir/routing/routing.table`, framed by `RoutingFile`) and nudges after every publication, with or
  without the service. When the nudge announces a generation that neither the preferences nor the remote file gave
  it, the module pulls the copy with `openFile(content://app.mauri.cucuto.events/routing, "r")`, after checking that
  the provider is Cucuto's (package and signing certificate); the app serves it to uid 1000 only. At boot the module
  pulls only when the remote file does not exist
  ([ADR 0048](decisions/0048-routing-table-pull-without-the-service.md)).
- Order of preference: Remote Preferences, the remote file, the provider. A table is installed only when its
  generation is newer than the one held, so each table loads once, and the module logs which path delivered it:
  `Routing table <gen> (via preferences|file|provider): N keys, M voices`.
- **Size limits** ([ADR 0054](decisions/0054-routing-table-size-headroom.md)). Remote Preferences travel through
  Binder, so they take at most 256 Ki characters (`RoutingContract.MAX_PREFERENCES_CHARS`). The remote file and the
  provider pass a file descriptor; their only limit is the module's read limit, `RoutingFile.MAX_BYTES` (1 MiB plus
  the frame header, the same in every released module), so the publisher writes tables of up to 1 MiB in UTF-8
  (`RoutingContract.MAX_TABLE_BYTES`).
  - A table over 256 Ki characters goes in full to the remote file and the app's copy at generation *N* + 1, and
    Remote Preferences get the same table without clip envelopes at generation *N* (or keep their table when even
    that is too large). The `generation` key is always the latest publication's, *N* + 1. Every module installs only
    newer (0.1.3 on) or different (0.1.2) generations and re-reads the file on the nudge for *N* + 1, so it ends on
    the full table; a module that misses the file pulls it from the provider
    (`PublishState.Published.tooLargeForPreferences`).
  - A table over 1 MiB is retried without clip envelopes (`PublishState.Published.clipHapticsLeftOut`: sounds still
    change, `auto` keeps WhatsApp's vibration), and only then refused (`PublishState.TooLarge`).
  - A heavy user with every feature on (1,150 keys, 180 voices of 12 clips, a 16-point envelope on every clip, six
    clip conditions, mirrored clips; `HeavyUserTest` in `:xposed`) needs about 250 Ki characters: about a quarter of
    the limit.

## Model

- **Route**: `(package, key)` → voice, plus the contact's shuffle seed, flags and database ID. Every key of a
  contact gets a route: phone JIDs (`…@s.whatsapp.net`), `@lid` JIDs, group JIDs (`…@g.us`) and sender keys.
  Phone numbers are not routed.
- **Voice**: pack ID, character ID, the enabled clips (provider URI, weight in thousandths, optional per-clip
  envelope) and the haptic. Contacts with the same character, clip toggles and haptic share one voice. The app puts
  clip envelopes (auto haptics analysed at import) only in voices whose haptic is `Auto`, the only case that uses
  them.
- **Haptic**: `Keep` (leave WhatsApp's vibration), `Off` (no vibration), `Auto` (each clip's own envelope; clips
  without one behave like `Keep`) or `Custom` (presets and basic envelopes, one-to-one with
  `VibrationEffect.Builder`). Pack mode `none` maps to `Keep`; a per-contact override of `none` maps to `Off`.
- **Clip URIs** are what SystemUI opens, chosen per clip by the app
  ([ADR 0018](decisions/0018-clip-mirror-for-instant-sounds.md)):
  `file:///storage/emulated/0/Android/media/app.mauri.cucuto/clips/<packId>/<version>/<file>` when the clip is
  mirrored to shared storage (SystemUI reads it without starting Cucuto's process), otherwise the clip provider's
  `content://app.mauri.cucuto.clips/<packId>/<characterId>/<file>`. `file` is the clip's path from `pack.json`.
  Pack IDs, versions, character IDs and pack paths are URI-safe by construction. The module passes the URI through
  unchanged.
- **Clip condition** (ADR 0037): when a clip with a pack `when` (day or night only, an hour range, seasons,
  weekdays) may play, compiled by the app (`:core:conditions`) into plain instants: the condition holds from
  `fromMs` (start of yesterday) to `untilMs` (400 days later), starts `activeAtFrom` and flips at each toggle. Day
  and night use sunrise and sunset at the place set on the Day and night screen (07:00 to 19:00 without one). Each
  distinct condition is stored once. Outside its span a condition is unknown and ignored.
- **Self-test clip**: the mirrored Diagnostics chirp (`…/clips/selftest/chirp.wav`), or absent, in which case the
  module plays the provider's `content://app.mauri.cucuto.clips/selftest/chirp.wav`.

Lookup (`RoutingTable.resolve(package, conversationKey, senderKey)`):

1. If the table is disabled (global switch off), change nothing.
2. Look up the conversation key (the notification's shortcut ID). Unknown: change nothing.
3. If that route is a per-sender group and the sender's key has a voice, play the sender's voice.
4. Otherwise play the conversation's voice, or change nothing if it has none.
5. Draw from the voice's clips that may play now (`RoutingTable.isPlayable`): clips without a condition, and clips
   whose condition is active or unknown. If none may play, draw from all of them. The clock is read only for voices
   with conditional clips; each check is a per-day bucket lookup plus a scan of that day's few toggles, with no
   allocation.

A group with the default policy (one voice per group) never uses its members' voices. The sender key is the
`Person` key of the alerting record's newest MessagingStyle message, read only for groups; every lookup stays within
one package, so WhatsApp and WhatsApp Business never share a route
([ADR 0031](decisions/0031-group-policy-and-whatsapp-business.md)).

## Encoding

UTF-8 text, one record per line (`\n`), fields separated by tabs. Strings escape only `%`, tab, CR and LF as `%25`,
`%09`, `%0D` and `%0A`. All numbers are decimal integers; fractions (weights, scales, intensities, sharpness) are
thousandths.

```text
cucuto-routing	1	<generation>	<enabled 0|1>	<package count>	<voice count>	<route count>	<clip haptic count>	[<condition count>]
S	<self-test clip URI>                                    optional, at most once
P	<package>
H	<clip haptic>
W	<fromMs>	<untilMs>	<active at from 0|1>	<toggle deltas in seconds, comma-separated, or ->
V	<packId>	<characterId>	<haptic>	<uriBase>	<clip count>	{<uriSuffix>	<milliWeight>	<clipHaptic or ->}	[h<ref>,<ref>…]	[w<ref>,<ref>…]
R	<package index>	<key>	<voice index or ->	<seed>	<flags>	<contactId>
```

- Line 1 is the header. `generation` is the publication time in milliseconds (strictly increasing per device). The
  clip haptic count was added with the dictionary; a header without it means 0.
- The optional `S` line (added in #121, right after the header) is the self-test clip. Readers that predate it
  skip it and keep the provider's chirp.
- `P` lines list packages; routes refer to them by index (order of appearance).
- `H` lines are the clip haptic dictionary: every distinct clip envelope once, referred to by index (order of
  appearance). They come before the `V` lines that use them.
- `W` lines are the clip condition dictionary (ADR 0037), referred to by index (order of appearance), before the `V`
  lines. Toggle instants are whole seconds; each delta is from the previous toggle (the first from `fromMs`). The
  header's ninth count says how many there are and is written only when there are some, so tables without
  conditional clips are byte-for-byte what they were. About 2 toggles a day over 400 days take about 5 KB per
  condition.
- `V` lines list voices; routes refer to them by index. Each clip URI is `uriBase + uriSuffix`; the writer uses the
  longest common folder of the voice's clips as `uriBase`. When any clip has an envelope, a trailing field `h`
  lists one dictionary index (or `-`) per clip, and the writer leaves the inline clip haptic fields as `-`. When any
  clip has a condition, a trailing field `w` lists one condition index (or `-`) per clip.
- `R` lines list routes. Flags: bit 0 group, bit 1 per-sender group. Other bits are reserved and preserved.
- Haptics: `k` keep, `o` off, `a` auto, or `c` followed by `;`-separated events:
  `p<at>,<C|T|L>,<scale>` for a preset (`CLICK`, `TICK`, `LOW_TICK`), or
  `e<at>,<initialSharpness or empty>,<intensity>,<sharpness>,<ms>[,<intensity>,<sharpness>,<ms>…]` for a basic
  envelope. An inline clip haptic is `-` (none) or a `c` timeline. `RoutingCodec.encodeHaptic` and `decodeHaptic`
  read and write one haptic in this syntax; the per-clip auto haptics stored next to installed packs use them
  ([ADR 0012](decisions/0012-haptics-effects-and-auto-haptics.md)). `:core:haptics` turns haptics into
  `VibrationEffect`s.
- A dictionary entry (`H`) is a `c` timeline or, when every event is a basic envelope on the haptic grid
  (`HapticGrid`), the compact form `z<at>,<initialSharpness level or empty>,<points>` (several envelopes separated
  by `;`). Each control point is three base-64 digits (`A–Z a–z 0–9 - _` for 0 to 63): intensity level, sharpness
  level, duration in 20 ms steps. Level `n` is `round(n × 1000 / 63)` thousandths. The app snaps auto envelopes to
  the grid (values to the nearest of 64 levels, durations up to the next 20 ms), so a 16-point envelope takes about
  55 characters instead of about 190.

Example (tabs shown as spaces):

```text
cucuto-routing  1  1761000000123  1  1  2  2  1
P  com.whatsapp
H  z0,,_gCAgG
V  app.mauri.demo.critters  frog  cp0,C,800;e220,,1000,800,120,0,400,180  content://app.mauri.cucuto.clips/app.mauri.demo.critters/frog/characters/frog/clips/  2  ribbit.ogg  2000  -  croak.ogg  1000  -
V  app.mauri.demo.critters  owl  a  content://app.mauri.cucuto.clips/app.mauri.demo.critters/owl/characters/owl/clips/  2  hoot.ogg  1000  -  silence.ogg  1000  -  h0,-
R  0  34600000001@s.whatsapp.net  0  42  0  1
R  0  123456789012345@lid  1  42  0  1
```

`z0,,_gCAgG` is one envelope at 0 ms: intensity 1000 and sharpness 508 after 40 ms, then intensity 0 (sharpness 508)
after 120 more.

## Compatibility

- Readers reject a header version above their own and keep the last table they could read. This matters because
  the module in `system_server` keeps running the old code after an app update until the next reboot.
- Readers skip line types they do not know and extra trailing fields on known lines, so additive changes stay in
  version 1. Anything an old reader would misinterpret needs version 2.
- The clip haptic dictionary is such an additive change: a reader from before it skips the `H` lines, the `h` field
  and the extra header count, and reads the table correctly without clip envelopes (`auto` keeps WhatsApp's
  vibration until the module is updated by a reboot).
- Clip conditions are additive too: a reader from before them skips the `W` lines, the `w` field and the ninth
  header count, and plays every clip of the voice whatever the time until the module is updated by a reboot.
- The header counts must match the records, so a truncated table is rejected rather than half-applied.

## Performance

`RoutingScaleTest` (in `:core:routing`) builds the heavy-user table above and checks the encoded size, decoding time
and lookup time, with and without an auto envelope on every clip. On a laptop JVM, decoding takes about 1 to 3 ms
and a lookup about 0.1 µs, against the hook's 5 ms budget. Four such users in one table (about 790 K characters,
too large for Remote Preferences) decode in about 12 ms; decoding runs on the module's background thread when a
table arrives, never on the notification path, and lookups do not depend on the table's size. `HeavyUserTest` and
`RoutingIntegrationTest` (in `:xposed`, [ADR 0047](decisions/0047-routing-integration-suite.md)) check the size
with every feature on and the file-only path end to end.
