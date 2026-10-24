# 0054. Routing table size headroom: the full table through the file, a reduced copy in Remote Preferences

- Status: accepted
- Date: 2026-10-24
- Issue: #223 (follows #98, [ADR 0020](0020-auto-haptics-at-import.md) and [ADR 0047](0047-routing-integration-suite.md))

## Context

The routing integration suite (ADR 0047) measured a heavy user with every feature on at 250,293 of the 262,144
characters the publisher allowed (`MAX_ENCODED_CHARS`, 256 Ki): 95 %. One more pack of that size and the publisher
would have dropped every clip envelope. That limit comes from Remote Preferences, which travel through Binder (a
1 MiB transaction buffer per process, shared, and two bytes per character in a parcel). The table now travels three
ways (ADR 0009, [ADR 0024](0024-routing-updates-after-app-update.md),
[ADR 0048](0048-routing-table-pull-without-the-service.md)), and only Remote Preferences use a parcel:

| Path | Transport | Limit |
| --- | --- | --- |
| Remote Preferences | Binder parcel (app → daemon → `system_server`) | about 256 Ki characters |
| Remote file `routing.table` | file descriptor (`openRemoteFile`) | the module's read limit |
| App's provider | file descriptor (`openFile`) | the module's read limit |

The module's read limit is `RoutingFile.MAX_BYTES`, 1,048,640 bytes, and it is the same in every released module
that reads the file (0.1.1, 0.1.2, 0.1.3). After an app update, a module from before the update keeps running until
the next reboot and gets new tables only through the file and the nudge, because LSPosed stops delivering
preference changes to it (ADR 0024). So Remote Preferences only ever reach a module that was loaded with the current
app, and that module has the file and the provider.

Where the heavy user's characters go (`HeavyUserTest` prints this breakdown):

| Lines | Characters | What |
| --- | --- | --- |
| `V` (180 voices, 2,130 clips) | 96,305 | URI bases 22,240; clip suffixes 38,696; weights 10,650; the inline `-` haptic 4,260; `h` refs 7,459; `w` refs 4,440; the rest 8,560 |
| `H` (1,440 envelopes) | 82,410 | 57 characters per 16-point envelope (three base-64 digits per point) |
| `R` (1,150 routes) | 54,291 | the phone JID and the LID of a contact repeat voice, seed, flags and contact |
| `W` (6 conditions) | 17,117 | about 800 toggles over 400 days for the sun-based ones |

## Decision

**Raise the limit where it is not real; keep the format.** The publisher writes tables of up to 1 MiB in UTF-8
(`RoutingContract.MAX_TABLE_BYTES`; the frame header fits within `MAX_BYTES`). Remote Preferences keep their own
limit, now named `MAX_PREFERENCES_CHARS` (256 Ki characters):

1. A table that fits Remote Preferences is published exactly as before: the same text in Remote Preferences, the
   remote file and the app's copy, at generation *N*.
2. A larger table goes in full to the remote file and the app's copy at generation *N* + 1. Remote Preferences get
   the same table without clip envelopes at generation *N* (what every module got before this change), or keep
   their table when even that is over their limit. The `generation` key is always *N* + 1, the latest publication.
3. Over 1 MiB, the publisher drops clip envelopes everywhere, and only then refuses the table (unchanged).

The nudge announces *N* + 1. Modules from 0.1.3 on install only newer generations, and 0.1.2 installs any different
one, so whatever arrives first (the reduced copy, the file, or the provider pull when the file is missing or torn),
every module ends on the full table, and each delivery path keeps logging one line per generation. At boot the
module attaches to Remote Preferences (the reduced or older copy) and reads the file right after, in the same
background task (ADR 0048).

The publisher's "same table, no rewrite" check now compares with Remote Preferences only when their table is the
latest publication (its generation equals the `generation` key), and otherwise with the app's copy, which always
holds the full table.

**No module change.** Every released module already reads the file and the provider up to 1 MiB and understands
format version 1, so the headroom works from the first app update, without a reboot.

**Headroom.** The heavy user is 24 % of the new limit, so about four times the heaviest user we model fits with every
envelope. `HeavyUserTest` fails below three times. Two heavy users (507 K characters) go through the file and the
provider pull end to end, and the released 0.1.2 reader decodes them. `RoutingScaleTest` decodes four users (792 K
characters) in about 12 ms on a laptop JVM. That runs once per publication on the module's background thread; the
notification path is unchanged (hash lookups on the table held, no allocation; the ADR 0047 benchmarks still pass).

## Alternatives considered

- **A denser format (version 2).** Estimated on the heavy user: a URI prefix dictionary (about 15 K), one clip list
  per character with per-voice toggles instead of a full list per voice (about 22 K), no inline `-` haptic (4 K),
  route aliases for a contact's second key (9 K) and a code for `@s.whatsapp.net` (7 K), and a `W` line that
  refers to another as its complement, so night is "not day" (5 K). Together about 60 K, or 25 %: 1.3 times the
  headroom, against four times for the transport change. Every one of them changes `V` or `R` lines, which old
  readers parse strictly, so it needs version 2. Released modules reject version 2, and after an app update they
  would keep their last table until a reboot, unless the app also published a version 1 copy. Not worth it now;
  if tables ever approach 1 MiB, a version 2 copy for new modules can go in a second remote file.
- **Drop Remote Preferences for the table.** The file and the provider cover every module that matters, but Remote
  Preferences are the only path that delivers a change without the nudge broadcast, and a module that loses both
  the file and the provider still has them. Keeping them costs nothing for tables that fit, and the reduced copy
  keeps today's behaviour for tables slightly too large.
- **Remove the table from Remote Preferences when it does not fit,** instead of keeping the older one. Modules up
  to 0.1.3 treat a missing table as "nothing published" and switch to WhatsApp's tones until the file arrives; an
  older table is the better fallback, and the file replaces it a few milliseconds later.
- **A shorter condition horizon** (120 days instead of 400; about 12 K). It is compatible, but the app would then
  need to run every four months to keep day and night clips right, for 5 % of the table.

## Consequences

- The heavy user can grow about four times before the publisher drops clip envelopes; the size limit now counts
  UTF-8 bytes (`RoutingFile.fits`). The table is still ASCII in practice, so characters and bytes are the same.
- Large tables are stored three times at up to 1 MiB (Remote Preferences hold at most 256 Ki characters), and the
  module may decode twice per publication: the reduced copy, then the full table.
- A module that gets the reduced copy but neither the file nor the provider plays the right voices with WhatsApp's
  vibration for `auto` haptics, as every module did before this change. That includes the 0.1.0 module, which
  predates the file, until the reboot after updating from it.
- `PublishState.Published.tooLargeForPreferences` says when the table went through the file only; Diagnostics does
  not show it yet.
- ADR 0047's size rule ("stay under `MAX_ENCODED_CHARS`") becomes: at least three times the heavy user fits
  `MAX_TABLE_BYTES`.
