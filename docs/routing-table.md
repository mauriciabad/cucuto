# Routing table, format version 1

The routing table is everything the module needs to swap a WhatsApp alert: which contact keys have a voice, which
clips that voice plays, and which vibration goes with them. The app builds it from its database and publishes it to
the module through libxposed Remote Preferences; the module keeps it in memory. This page is the contract between
the two. The code lives in `android/core/routing` (`RoutingTable`, `RoutingCodec`, `RoutingContract`).

## Transport

| | App (`:core:data`) | Module (`:xposed`, in `system_server`) |
| --- | --- | --- |
| API | `XposedServiceHelper.registerListener` → `XposedService.getRemotePreferences("routing")` | `XposedInterface.getRemotePreferences("routing")` |
| Access | read/write (`edit().putString(…).commit()`) | read-only, plus `OnSharedPreferenceChangeListener` |
| Keys | `table` (string, the encoded table), `generation` (long) | same |

- The app republishes whenever contacts, keys, assignments, installed packs or the global switch change
  (`RoutingPublisher`). A table equal to the published one is not written again.
- The service only exists while the module is enabled in LSPosed and the framework supports Remote Preferences
  (`PROP_CAP_REMOTE`). Until then nothing is written; the database stays the source of truth and the table is
  published as soon as the service connects.
- The module attaches lazily on the first lookup (not during boot), decodes on the change callback, and swaps one
  volatile reference. The notification path only does hash map lookups.
- Encoded tables larger than 256 Ki characters are refused (`PublishState.TooLarge`) because Remote Preferences
  travel through Binder. A heavy user (500 contacts, 950 keys, 176 voices of 12 clips) needs about 127 Ki characters.

## Model

- **Route**: `(package, key)` → voice, plus the contact's shuffle seed, flags and database ID. Every key of a
  contact gets a route: phone JIDs (`…@s.whatsapp.net`), `@lid` JIDs, group JIDs (`…@g.us`) and sender keys.
  Phone numbers are not routed.
- **Voice**: pack ID, character ID, the enabled clips (provider URI, weight in thousandths, optional per-clip
  envelope) and the haptic. Contacts with the same character, clip toggles and haptic share one voice.
- **Haptic**: `Keep` (leave WhatsApp's vibration), `Off` (no vibration), `Auto` (each clip's own envelope; clips
  without one behave like `Keep`) or `Custom` (presets and basic envelopes, one-to-one with
  `VibrationEffect.Builder`). Pack mode `none` maps to `Keep`; a per-contact override of `none` maps to `Off`.
- **Clip URIs** are `content://app.mauri.cucuto.clips/<packId>/<characterId>/<file>`, where `file` is the clip's
  path from `pack.json`. Pack IDs, character IDs and pack paths are URI-safe by construction.

Lookup (`RoutingTable.resolve(package, conversationKey, senderKey)`):

1. If the table is disabled (global switch off), change nothing.
2. Look up the conversation key (the notification's shortcut ID). Unknown: change nothing.
3. If that route is a per-sender group and the sender's key has a voice, play the sender's voice.
4. Otherwise play the conversation's voice, or change nothing if it has none.

A group with the default policy (one voice per group) never uses its members' voices.

## Encoding

UTF-8 text, one record per line (`\n`), fields separated by tabs. Strings escape only `%`, tab, CR and LF as `%25`,
`%09`, `%0D` and `%0A`. All numbers are decimal integers; fractions (weights, scales, intensities, sharpness) are
thousandths.

```text
cucuto-routing	1	<generation>	<enabled 0|1>	<package count>	<voice count>	<route count>
P	<package>
V	<packId>	<characterId>	<haptic>	<uriBase>	<clip count>	{<uriSuffix>	<milliWeight>	<clipHaptic or ->}
R	<package index>	<key>	<voice index or ->	<seed>	<flags>	<contactId>
```

- Line 1 is the header. `generation` is the publication time in milliseconds (strictly increasing per device).
- `P` lines list packages; routes refer to them by index (order of appearance).
- `V` lines list voices; routes refer to them by index. Each clip URI is `uriBase + uriSuffix`; the writer uses the
  longest common folder of the voice's clips as `uriBase`.
- `R` lines list routes. Flags: bit 0 group, bit 1 per-sender group. Other bits are reserved and preserved.
- Haptics: `k` keep, `o` off, `a` auto, or `c` followed by `;`-separated events:
  `p<at>,<C|T|L>,<scale>` for a preset (`CLICK`, `TICK`, `LOW_TICK`), or
  `e<at>,<initialSharpness or empty>,<intensity>,<sharpness>,<ms>[,<intensity>,<sharpness>,<ms>…]` for a basic
  envelope. A clip haptic is `-` (none) or a `c` timeline. `RoutingCodec.encodeHaptic` and `decodeHaptic` read
  and write one haptic in this syntax; the per-clip auto haptics stored next to installed packs use them
  ([ADR 0012](decisions/0012-haptics-effects-and-auto-haptics.md)). `:core:haptics` turns haptics into
  `VibrationEffect`s.

Example (tabs shown as spaces):

```text
cucuto-routing  1  1761000000123  1  1  1  2
P  com.whatsapp
V  app.mauri.demo.critters  frog  cp0,C,800;e220,,1000,800,120,0,400,180  content://app.mauri.cucuto.clips/app.mauri.demo.critters/frog/characters/frog/clips/  2  ribbit.ogg  2000  -  croak.ogg  1000  -
R  0  34600000001@s.whatsapp.net  0  42  0  1
R  0  123456789012345@lid  0  42  0  1
```

## Compatibility

- Readers reject a header version above their own and keep the last table they could read. This matters because
  the module in `system_server` keeps running the old code after an app update until the next reboot.
- Readers skip line types they do not know and extra trailing fields on known lines, so additive changes stay in
  version 1. Anything an old reader would misinterpret needs version 2.
- The header counts must match the records, so a truncated table is rejected rather than half-applied.

## Performance

`RoutingScaleTest` (in `:core:routing`) builds the heavy-user table above and checks the encoded size, decoding time
and lookup time. On a laptop JVM, decoding takes about 2 ms and a lookup about 0.1 µs, against the hook's 5 ms
budget.
