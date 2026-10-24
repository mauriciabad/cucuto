# Changelog

What changed in each Cucuto release, newest first. Downloads and checksums are on
[GitHub Releases](https://github.com/mauriciabad/cucuto/releases). Versions follow `MAJOR.MINOR.PATCH`; the Android
`versionCode` is `MAJOR * 1000000 + MINOR * 1000 + PATCH`.

Each section is written from the commits since the previous release by `tools/release/release-notes.main.kts`, then
edited by hand where the commit titles are too technical (see [docs/releasing.md](docs/releasing.md)).

## [0.1.2] - 2026-10-24

Pack import works again. If you installed 0.1.1, update and import your packs.

### New

- Haptic editor: make a vibration or tap out its rhythm, and give a contact its own vibration
  ([#172](https://github.com/mauriciabad/cucuto/pull/172))
- Packs can carry ringtones: ring files, plus notification clips cut from the song automatically
  ([#174](https://github.com/mauriciabad/cucuto/pull/174))
- Discover: browse pack repositories, preview characters, install packs and add your own repositories
  ([#176](https://github.com/mauriciabad/cucuto/pull/176))

### Fixes

- Importing a pack failed every time with "UNEXPECTED: IllegalStateException" in 0.1.1. It works again
  ([#181](https://github.com/mauriciabad/cucuto/pull/181))

[Release](https://github.com/mauriciabad/cucuto/releases/tag/v0.1.2) · [All changes](https://github.com/mauriciabad/cucuto/compare/v0.1.1...v0.1.2)

## [0.1.1] - 2026-10-24

The first published build (the `v0.1.0` tag never became a release). An early test version for rooted Android 17
phones with LSPosed.

**Known issue: importing a pack fails** with "UNEXPECTED: IllegalStateException: Flow invariant is violated". This
release can't import any pack, so it can't give anyone a voice yet. Fixed in 0.1.2.

### New

- An LSPosed module in Android's system process (System Framework scope) swaps the sound and vibration of WhatsApp's
  own notification per contact. Unassigned chats, a turned-off voices switch or any failure keep WhatsApp's tone
- Import `.soundpack.zip` packs and bundles, with clear errors for broken files; remove packs you no longer want
- Home lists the people who wrote recently: tap one, pick a character, done
- Group chats get one character, or a voice per sender. WhatsApp Business works too
  ([#159](https://github.com/mauriciabad/cucuto/pull/159))
- Characters vibrate in their own pattern, and packs without one get a vibration that follows the voice
- Status checks and a first-run guide for LSPosed, the scope, WhatsApp and your packs; Diagnostics with a test
  notification and a log of recent decisions
- JPEG XL pictures in packs, notification clips up to 30 seconds

### Fixes

- Clips play at once even when Cucuto isn't running ([#151](https://github.com/mauriciabad/cucuto/pull/151))
- After an app update, the module picks up new assignments without a reboot
  ([#160](https://github.com/mauriciabad/cucuto/pull/160))
- Previews in the app vibrate through public Android APIs only ([#156](https://github.com/mauriciabad/cucuto/pull/156))

[Release](https://github.com/mauriciabad/cucuto/releases/tag/v0.1.1) · [All changes](https://github.com/mauriciabad/cucuto/compare/v0.1.0...v0.1.1)
