# Project Brief: Cucuto

Oct 23, 2026 · @Mauri

## Mission

Build **Cucuto** (koo-KOO-toe), an open-source Android 17 app that gives each contact a voice. The name comes from *cucut*, Catalan for cuckoo: like a cuckoo clock, it announces who's there. When a contact messages, the phone plays a random clip from the character assigned to them and fires that character's custom vibration; when they call, their ringtone plays. Write the name as "Cucuto" in prose (owner update 2026-10-23; originally lowercase); identifiers stay lowercase. Identifiers: GitHub repo `cucuto`, application ID `app.mauri.cucuto`, website `cucuto.mauri.app` (the owner points DNS there once the repo exists).

- **Content comes from sound packs, never from the code.** The app ships empty. Users import pack files, or browse and download them from pack repositories. Anyone can build and share packs of anything: animals, game SFX, a friend's voice, a game's songs.
- **Notifications and ringtones.** The same packs can carry message sounds and call ringtones. A contact can get a K.K. Slider song with its album art as ringtone, Pikachu's cries for messages, or both from one character. The message character and the ringtone character are separate choices, and most packs offer only one of the two (owner update 2026-10-24 (#245), [ADR 0063](decisions/0063-independent-voice-and-ringtone.md)). Ringtones can have variants (day, night, seasons, sped-up, radio) of the same song, picked by rules.
- **Anyone can make packs.** A pack creator in the app and in the website (an installable PWA) lets people build packs without tools: pick images, record or import clips and songs, trim them, set haptics, export.
- **Packs travel alone or bundled.** One file can hold a single pack or a bundle of several.
- **Pack repositories.** Users add a repository URL (a static website, a cloud folder, optionally password-protected), browse its packs, preview them, and download the ones they like. The project website is the default repository.
- **Backup and restore.** One backup file brings every assignment, ringtone and setting back on a new phone.
- **The owner's first packs are anime Pokémon voices (Dream World artwork) and K.K. Slider songs.** They are copyrighted, so they live only in the owner's private Google Drive and are imported after install.
- **A public website** explains the app, offers the APK download, screenshots and an explainer video, and hosts free packs to download.
- **It should feel like a game.** Every interaction gets sound, motion and haptics, built on the latest Material 3 Expressive guidelines.
- **Common formats, JPEG XL preferred.** Packs accept the usual image and audio formats. JPEG XL (`.jxl`) is the recommended image format and gets first-class support where it costs little, to help it spread.

Non-goals: ~~Play Store release~~ (owner update 2026-10-24, #211: "i thought we would publish it there too"; Google Play is now an extra channel next to GitHub Releases, from v1 on, with no guarantee of approval, see [distribution.md](distribution.md#google-play) and [ADR 0046](decisions/0046-distribution.md); F-Droid after v1), support for Android 16 or older, dependence on other apps (Tasker, MacroDroid, BuzzKill), any backend, accounts or analytics, and bundling copyrighted media in the public repo.

## Constraints and owner decisions

These are fixed by the owner; design around them rather than reopening them.

| Constraint | Value | What it means for the build |
| --- | --- | --- |
| OS | Android 17 (API 37), latest QPR | `minSdk = targetSdk = 37`, compile against the newest 37.x SDK. No compatibility code, no version branches. |
| Root and hooking | Rooted; LSPosed with libxposed API 102 (WaEnhancer X already runs) | Root-only features are allowed. The module targets libxposed API 102 and uses no legacy Xposed API. |
| Self-contained | No Tasker, MacroDroid, BuzzKill or other helper apps | One APK contains the app and its Xposed module. |
| Target apps | WhatsApp (`com.whatsapp`) and WhatsApp Business (`com.whatsapp.w4b`) | Routing is keyed by package, so other messengers can be added later. Phone calls get ringtones through Android contacts. |
| Coexistence | WaEnhancer X is installed and hooks WhatsApp | Do not hook the WhatsApp process by default. Stay in `system_server` to avoid clashes. Owner update 2026-10-24 (#145, [ADR 0051](decisions/0051-optional-whatsapp-scope.md)): not hooking WhatsApp is "a preference, not a requirement"; a feature that cannot work otherwise (only automatic WhatsApp call ringtones) may use an optional, opt-in WhatsApp scope, explained to the user first, with minimum intrusion (nothing of messages read) and verified compatibility with WaEnhancer X. |
| Minimum intrusion | "I want it to work with the minimum intrusion possible, so we lower the risk of critical vulnerabilities/mistakes" (owner, #67) | Hook as little as possible, in as few processes as possible; fewest permissions, asked only when a feature needs them; read only what a feature uses. No backwards-compatibility code. When two designs work, pick the less intrusive one. |
| License | GPL-3.0-or-later | Derived works must stay open source. Demo packs are CC0. |
| Repository | One public monorepo on GitHub | Android app, website, packer tool, schemas and demo packs live together. |
| Website | SvelteKit, fully static, at `cucuto.mauri.app` | No server. Deployed to GitHub Pages (owner, #55). Pack validation reuses the Kotlin `shared/pack-spec` compiled for the browser (owner, #60). JavaScript tooling runs on Bun (owner, #63). |
| Images | Common formats accepted; JPEG XL (`.jxl`) preferred | Support PNG, JPEG, WebP, GIF, AVIF and JPEG XL. JXL is recommended and used for the project's own assets where practical. If JXL decoding becomes a burden on Android, drop it in the app and convert JXL to another format at import time. |
| Open source | Public code, private content | No copyrighted media in the public repo, ever. Content arrives as imported packs. |
| Quality bar | Game feel plus latest Material 3 Expressive | Sound, motion and haptics on every interaction, built to current Material and accessibility guidance. |

## Decision: root-first, no non-root version

**Build it as an LSPosed module that runs in `system_server`; skip the non-root build.** Android 17's background audio hardening makes the non-root route unreliable, and root gives a cleaner design with fewer moving parts.

Why non-root fails on Android 17:

- **Background playback is silenced.** On Android 17, any app playing audio in the background needs a visible activity or a running foreground service (not `SHORT_SERVICE`). Apps targeting API 37 also need that service to have while-in-use (WIU) capability, and failures are silent ([Background audio hardening](https://developer.android.com/about/versions/17/changes/bg-audio)).
- **WIU is hard to keep.** WIU is only granted to a service started while the app is visible or from a user action. A service started at boot gets its audio suppressed, so after every reboot the owner would have to open the app again.
- **The workarounds are worse than root.** A permanent media-playback notification, or posting our own duplicate notification on one channel per clip, both add visible clutter and fragile edge cases.

| Approach | Plays reliably in background on Android 17 | Extra UI noise | Honors muted chats, DND and alert-once | Verdict |
| --- | --- | --- | --- | --- |
| Notification listener plays the clip itself | Only with a WIU foreground service; lost after reboot | Permanent foreground notification | Must be reimplemented by hand | Rejected |
| Listener posts its own notification on a per-clip channel | Yes (the system plays it) | Duplicate notification, hundreds of channels in Settings | Partly; duplicates WhatsApp's own logic | Rejected |
| **LSPosed hook in `system_server` swaps the sound and vibration on WhatsApp's own notification** | **Yes (the system plays it, as for any notification)** | **None** | **Yes, for free: the system decides whether to alert** | **Chosen** |

What the chosen design gives us:

- **One sound, no duplicates.** We replace WhatsApp's tone on the exact notification that was going to alert. Muted chats, re-posted updates, Do Not Disturb, ringer mode and notification cooldown keep working exactly as Android implements them.
- **No WhatsApp settings to change.** Leave WhatsApp's tone and vibration on. If the module is off or a hook fails, the default WhatsApp tone plays: safe degradation.
- **No foreground service, no persistent notification, no battery exemptions.** The app only needs to run when the owner opens it.
- **No conflict with WaEnhancer X.** The required scope never touches the WhatsApp process. Only the optional WhatsApp call-ringtone scope does, if the user turns it on, and it must coexist with WaEnhancer X ([ADR 0051](decisions/0051-optional-whatsapp-scope.md)).

The first milestone (M0 below) is a short device spike that confirms the hook points on the owner's exact build before any UI is written.

## Architecture

One APK holds two parts: a tiny hook inside `system_server` that swaps WhatsApp's alert, and a normal Compose app that manages packs and assignments.

&#91;embedded content: architecture · notification path and app components\]

Read it top to bottom: WhatsApp posts, the framework decides whether to alert, our hook swaps the sound and vibration, and the system plays them, reading the clip from our app.

The same flow in steps:

1. WhatsApp posts a MessagingStyle notification.
2. `NotificationManagerService` applies channel settings, DND and ranking, and decides whether this post should alert at all.
3. In the alerting step (`NotificationAttentionHelper` in recent AOSP; confirm the exact class and methods in M0), our hook runs only for WhatsApp packages. It reads the conversation key and sender, looks up the in-memory routing table, picks a clip and haptic, and replaces the record's sound URI and vibration effect.
4. The system plays the sound through its notification sound player, which opens the clip from our clip provider, and plays the vibration through the vibrator service.
5. The hook sends a "seen sender" event to the app so new contacts appear for one-tap assignment.
6. In the app, the importer installs packs to private storage, the UI writes assignments to Room, and the app republishes the routing table whenever assignments change.

## Sound pack and bundle format (v1)

A sound pack file is a zip named `<name>.soundpack.zip` (owner update 2026-10-23: zip files always end in `.zip`, never a custom extension like `.soundpack`; [ADR 0007](decisions/0007-zip-file-names.md)). It holds either one pack or a bundle of several packs, each with a JSON manifest, characters, clips, sprites and haptics. The format is public and versioned so anyone can build packs. The app detects what a zip is from its contents (`pack.json`, `bundle.json` or `backup.json` at the root), so any `.zip` name is accepted.

**Vocabulary.** A *pack* contains *characters*: Pikachu, a cat, a friend, or a song with its album art. A character has a picture, a pool of short notification *clips*, an optional set of *ringtones* with *variants*, and a default *haptic*. A *bundle* groups several packs in one file. Users assign characters to *contacts* (a 1:1 chat, a group, or a phone contact).

Single pack layout:

```
pokemon-voices.soundpack.zip
├── pack.json
├── cover.jxl
└── characters/
    └── pikachu/
        ├── sprite.jxl           # static, required (any supported format)
        ├── sprite-anim.jxl      # animated (JXL, WebP or GIF), optional
        └── clips/
            ├── pika-pika.ogg
            ├── chu.ogg
            └── pikachu-happy.ogg
```

Bundle layout: `bundle.json` at the root instead of `pack.json`, and each pack in its own folder.

```
pokemon-all-gens.soundpack.zip
├── bundle.json
├── cover.jxl
└── packs/
    ├── kanto/      # a complete pack: pack.json, cover.jxl, characters/
    └── johto/
```

Pack manifest example, a song with ringtone variants (`pack.json`):

```json
{
  "formatVersion": 1,
  "id": "com.example.kk-slider",
  "name": "K.K. Slider",
  "version": "1.0.0",
  "author": "Owner",
  "license": "Personal use only",
  "cover": "cover.jxl",
  "characters": [
    {
      "id": "bubblegum-kk",
      "name": "Bubblegum K.K.",
      "sprite": "characters/bubblegum-kk/album.jxl",
      "seedColor": "#F4A7C0",
      "tags": ["kk", "pop"],
      "clips": [
        { "file": "characters/bubblegum-kk/clips/hook.ogg", "label": "Chorus hook" }
      ],
      "ringtones": [
        { "file": "characters/bubblegum-kk/ringtones/live.ogg", "label": "Live", "tags": ["live"], "when": { "time": "day" } },
        { "file": "characters/bubblegum-kk/ringtones/radio.ogg", "label": "Radio", "tags": ["radio"], "when": { "time": "night" }, "loop": { "startMs": 1200, "endMs": 98000 } }
      ],
      "haptic": { "mode": "auto" }
    }
  ]
}
```

Bundle manifest example (`bundle.json`):

```json
{
  "formatVersion": 1,
  "type": "bundle",
  "id": "com.example.pokemon-all-gens",
  "name": "Pokémon, all generations",
  "version": "1.0.0",
  "cover": "cover.jxl",
  "packs": ["packs/kanto", "packs/johto"]
}
```

Rules the importer enforces:

- **Audio:** any common format Android plays: Ogg Opus, Ogg Vorbis, MP3, AAC/M4A, FLAC or WAV. Notification clips are 0.2 to 30 s (owner update 2026-10-23, #75: more permissive so the limit does not hold creativity back; was 4 s; runtime effects in [ADR 0019](decisions/0019-long-notification-clips.md)); ringtones are 5 s to 6 min and loop by default, with optional loop points. Opus is recommended for size. The packer and the pack creator can trim silence and normalize loudness so clips sound consistent, but they never force a format.
- **Images:** PNG, JPEG, WebP, GIF, AVIF, JPEG XL or SVG, at most 512 px per side for bitmaps. Animated sprites can be GIF, animated WebP or animated JPEG XL. JPEG XL is recommended (it compresses pixel art losslessly and well); the tools can convert to it but never require it.
- **Haptic:** `{"mode": "auto"}` derives a pattern from each clip at import time, or `{"mode": "custom", "events": [...]}` gives an explicit timeline (schema in the Haptics section).
- **Limits:** up to 1 GB unpacked per pack and 2 GB per bundle (song packs are large), 5,000 files per pack, and a decompression ratio cap to block zip bombs. The importer warns before installing anything over 256 MB.
- **Safety:** reject absolute paths and `..` segments (zip slip), unknown file types, and anything executable; SVGs are sanitized (no scripts, no external references). Packs contain data only. Pack IDs inside a bundle must be unique.

Import flow (local files and repository downloads go through the same pipeline):

1. Entry points: in-app picker (Storage Access Framework, which also reaches Google Drive and other cloud apps), "Open with" and the share sheet for zip files (`application/zip` and `.zip` paths; packs are `*.soundpack.zip`, owner update 2026-10-23), and downloads from pack repositories.
2. Validate against the schemas, then test-decode every clip and image.
3. For a bundle, show its packs with checkboxes; all are selected by default.
4. Show a preview screen that reveals the characters one by one, each playing a clip.
5. Install each pack to app-private storage at `files/packs/<id>/<version>/`. Re-importing the same `id` with a higher `version` updates in place and keeps all assignments.
6. Removing a pack asks what to do with contacts that use its characters.

The repo ships JSON Schemas in `spec/` (`pack.schema.json`, `bundle.schema.json`, `repository.schema.json`), a `docs/pack-format.md` spec, and the `packer` command-line tool that builds packs, bundles and repository indexes (see Tech stack).

## Ringtones

Packs can carry call ringtones too, so a contact gets one look and sound across messages and calls. For phone calls, the app uses Android's own per-contact ringtone, so it works without hooks and shows up in the Contacts app; the app is simply a much faster picker with album art.

**Model.**

- A character can carry a `ringtones` array next to its `clips`. A character can be a song (Bubblegum K.K. with its album cover), not only a creature.
- **Variants** are ringtone entries of the same song: day and night versions, seasons, sped-up, underwater, "action", live versus radio. Each entry has free `tags` and an optional `when` condition (`time`: day, night or an hour range; `season`; `weekday`; `date`: days of the year such as holidays the pack defines, #120, [ADR 0059](decisions/0059-rare-and-moment-clips.md)). Day and night follow local sunrise and sunset, computed on the phone for a place the user sets (a city, coordinates, or one approximate location fix they ask for; 07:00 to 19:00 until they set one), never tracked (owner update 2026-10-23, #75; #118, [ADR 0037](decisions/0037-day-and-night-clips.md)).
- **Clips can carry the same `when`** (#118): a night-only or day-only clip joins the random pool only while its condition holds, so a character can hop by day and croak at night. The app compiles each condition into instants for the routing table; the module only compares the clock against them.
- **Special clips** (#120, [ADR 0059](decisions/0059-rare-and-moment-clips.md)): `oneIn` makes a rare easter-egg clip (about one message in N), and a clip's `when` can also name a moment of the conversation, `firstOfDay` (the chat's first message of the day) or `burst` (the nth message in a row). Special clips play instead of the random pool when their moment comes. Everything is decided from the time and counters the module already keeps in memory: no sensors, location, network or wake-ups.
- Per contact, the user picks the variant rule: one fixed variant, random per call, by time of day, by season, or follow the pack's `when` conditions. Owner update 2026-10-24 (#288): the user can see the pack's rules in plain words ("Live by day, Radio at night") and edit them for one contact: "your own rules" start as a copy of the pack's, where each version can be turned off or given another condition, with a one-tap "Reset to the pack's rules" ([ADR 0074](decisions/0074-ringtone-rules-screen.md)).
- **Linking:** the message character and the ringtone character are independent choices (owner update 2026-10-24 (#245); originally "assigning a character assigns both its notification clips and its ringtones"). Users mix freely: messages from Tweet the bird, ringtone from K.K. Slider. Until the user picks a ringtone, it follows the message character when that character has ringtones; a message character without ringtones leaves the ringtone empty or keeps the one that rang before; changing the message character never changes a ringtone the user picked ([ADR 0063](decisions/0063-independent-voice-and-ringtone.md)). Owner update 2026-10-24 (#298): the usual pack is either notifications or ringtones, so the main flow is two picks, the message sound and then the ringtone. A ringtone is a Cucuto character (with or without a message voice), **Android default** (the phone's default ringtone: Cucuto clears a ringtone of the contact's own, keeping it for undo) or **Leave as is** (Cucuto never touches the contact's ringtone and gives back its own if Cucuto changed it) ([ADR 0073](decisions/0073-sound-flow-message-then-ringtone.md)).

Where ringtones apply:

| Call type | How | Notes |
| --- | --- | --- |
| Phone calls, fixed or rule-based variant | Android's per-contact ringtone (`ContactsContract.Contacts.CUSTOM_RINGTONE`) | Native; Telecom plays it and the Contacts app shows it. Needs `READ_CONTACTS` and `WRITE_CONTACTS`. Ringtone files are copied into the shared `Ringtones/cucuto/` folder through MediaStore so the system can play them. For rule-based variants the app rewrites the contact's ringtone at each boundary (time-of-day change, new season) with an inexact alarm, which needs no exact-alarm permission ([ADR 0039](decisions/0039-phone-ringtones-on-the-device.md)). |
| Phone calls, random per call | The app re-rolls the contact's ringtone after each call (no hook) | M9 spike (#50, [ADR 0023](decisions/0023-call-ringtones-native-first.md)): Telecom runs inside `system_server` (scope `system` covers it), but no hook is needed: the app rewrites `CUSTOM_RINGTONE` when a call ends, so the next call rings with a new variant. A per-contact call vibration rides on the ringtone URI's `vibration_uri` parameter (Android 17, enabled on Pixel). Telecom hooks (`Call.getRingtone()`, `Ringer.vibrateIfNeeded`) are prepared in `spikes/m9/` as a fallback only. |
| WhatsApp calls | Guided per-chat ringtone by default; automatic with an optional WhatsApp scope | M9 spike (#50): WhatsApp's calls are self-managed (Telecom does not ring) and its call channel has no sound, so WhatsApp rings inside its own process. By default the app exports the ringtone to `Ringtones/cucuto/` and guides the user to WhatsApp's per-chat call ringtone (one file, no variants). Owner update 2026-10-24 (#145): automatic WhatsApp call ringtones matter, so this feature alone may hook WhatsApp through an optional, opt-in scope the user enables in LSPosed after a plain explanation ([ADR 0051](decisions/0051-optional-whatsapp-scope.md)); the least intrusive hook and the scope UX are spike #215. Owner update 2026-10-24 (#229): WhatsApp Business gets the same automatic call ringtones, as a second optional scope entry (`com.whatsapp.w4b`), unless the device run (#228) finds a clear difficulty ([ADR 0052](decisions/0052-whatsapp-call-ringtones.md), #273). |

Contacts: each app contact links to an Android contact for phone ringtones. Match WhatsApp senders to Android contacts by phone number, and let the user confirm or pick manually.

Audio for ringtones: any supported format, 5 s to 6 min, looping by default with optional seamless loop points (`loop.startMs`, `loop.endMs`), an optional fade-in, and loudness normalized for ringtone volume. The creator and the packer can cut a 2 to 3 s notification clip from a song (for example its hook) automatically.

In the UI: the ringtone picker is a grid or carousel of album covers; tapping one plays it with a spinning-record animation, variant chips switch versions, and the contact detail shows the message sound and the ringtone as two rows, "Messages" and "Phone calls", each one tap from its picker. The character picker lists only characters with notification clips and the ringtone picker only characters with ringtones, with pack filters that offer only packs that have such characters (owner update 2026-10-24 (#245), [ADR 0063](decisions/0063-independent-voice-and-ringtone.md)). Assigning from Home picks the message sound first, then opens the ringtone picker as an optional second step (owner update 2026-10-24 (#298), [ADR 0073](decisions/0073-sound-flow-message-then-ringtone.md)); the ringtone picker offers "Android default" and "Leave as is" next to the covers, and the ringtone shows in the contact detail header and on Home's rows. Both pickers fold their pack sections, search inside folded packs and offer "Surprise me" (owner update 2026-10-24 (#289, #290), [ADR 0072](decisions/0072-picker-folding-and-surprise-me.md)). Owner update 2026-10-24 (#242): a ringtone preview plays exactly what a call would (the same ring file, from the beginning, looping like the call), with no time limit, and can be paused ([ADR 0057](decisions/0057-ringtone-picker-and-ringtone-backups.md), built in #246: also on contact detail and the Packs screens, never from a loop point or a cut clip). Notification previews stay short. Owner update 2026-10-24 (#288): a "Ringtone rules" screen, opened from the ringtone picker and from each character on Packs, shows which version rings when, in plain words, with every version playable, and lets the user edit the rules for one contact ([ADR 0074](decisions/0074-ringtone-rules-screen.md)). Message clips keep the pack's conditions; the clip toggles are their per-contact control.

**The owner's K.K. Slider pack (private).** The source is the [K.K. Slider Complete Songs Collection](https://downloads.khinsider.com/game-soundtracks/album/animal-crossing-k.k.-slider-complete-songs-collection) on KHInsider: 193 files, 98 songs plus 95 "(Radio)" versions, 502 MB as MP3 or 981 MB as FLAC. One character per song, with "live" and "radio" as variants. Owner update 2026-10-24 (#246): the songs are ringtones only, with no auto-cut clip for messages ("the sounds should be ringtones, not notifications"); contacts take their message voice from another pack. Album covers come from a fan wiki. Transcoding to Opus roughly halves the size. Like the Pokémon pack, it stays in the owner's private Google Drive.

## Backup and restore

One backup file restores every assignment, ringtone and setting on a new phone. Packs re-download from their repositories; only local-only packs, like the private Pokémon and K.K. packs, need their files picked again.

What a backup holds (a zip named `cucuto-backup-YYYY-MM-DD.zip`, owner update 2026-10-23, with versioned JSON; schema in `spec/backup.schema.json`):

- **Assignments:** every contact key the app knows (WhatsApp conversation keys, phone numbers, display names), the assigned character and ringtone by pack ID and character ID, variant rules, clip toggles and haptic overrides.
- **Settings** and the list of pack repositories.
- **Pack references:** ID, version, source repository URL and SHA-256 for each installed pack. An optional "full backup" includes the pack files themselves, which can be large.
- **Credentials** for repositories only if the user opts in. Then the whole file is encrypted with a passphrase (AES-256-GCM, key derived with a memory-hard function such as Argon2id).

Where backups go:

- **Manual export** through the Storage Access Framework, for example straight into Google Drive.
- **Automatic backup** to a folder the user picks once (persistent folder permission). It is written shortly after any change, keeping the last 10 copies. Or to one file the user picks, for example in Google Drive, replaced on each change ([ADR 0040](decisions/0040-google-drive-backups-and-pack-folders.md)). Automatic backups are never encrypted and never hold credentials ([ADR 0028](decisions/0028-settings-and-backup-ui.md)).
- Cucuto itself uploads nothing: a backup goes only where the user chooses.
- Android's built-in app backup and device-to-device transfer are off: `data_extraction_rules.xml` excludes every domain, so nothing reaches Google through them ([ADR 0001](decisions/0001-android-build-and-versions.md)). Correction 2026-10-24 (#256): this line used to say Android backup stayed on for the settings database; the app has shipped with it off since #69 (owner can override on #262).
- Never in a backup: device-specific state, such as the automatic backup target, permissions and the location for day and night clips ([ADR 0037](decisions/0037-day-and-night-clips.md)). Settings travel only from an explicit list ([ADR 0013](decisions/0013-backup-and-restore.md)).

Restore on a new phone:

1. Install the APK, open the app, choose "Restore from backup" and pick the file.
2. The app lists the needed packs: it downloads those with a repository (verifying SHA-256) and asks for the files of local-only packs (pick them from Google Drive).
3. Contacts are re-matched by phone number, WhatsApp key and name; anything unmatched is shown for a one-tap fix.
4. Phone ringtones are written back to the Android contacts, and the routing table is republished for the module.
5. A final checklist covers what a backup can't do: enable the module in LSPosed (System Framework scope) and reboot.

The restore must be idempotent: running it twice gives the same result, and it never overwrites assignments the user made on the new phone without asking.

## Monorepo, license and private content

One public monorepo under GPL-3.0-or-later holds everything except copyrighted media. The owner's Pokémon pack lives in their private Google Drive.

```
cucuto/
├── android/            # Gradle project: app, xposed and core modules
├── web/                # SvelteKit site and PWA pack creator (static adapter)
├── tools/packer/       # CLI: build packs, bundles and repository indexes
├── spec/               # JSON Schemas: pack, bundle, repository index
├── packs/              # CC0 demo pack sources, built by CI and published on the site
├── docs/               # pack format, install guide, contributing
├── LICENSE             # GPL-3.0-or-later
└── .github/workflows/  # android, web, packs, release
```

|  | Public monorepo (GitHub) | Owner's private content (Google Drive) |
| --- | --- | --- |
| Contents | All code, schemas, docs, packer, website, CC0 demo packs | Pokémon pack sources (raw clips, sprites, `pack.yaml`) and the built `.soundpack.zip` files |
| Media | Only CC0 demo content: generate it with ffmpeg tones and simple drawn shapes, or use clearly CC0 recordings | Anime voice clips and Pokémon sprites, personal use only |
| License | Code GPL-3.0-or-later; demo packs CC0 | None; never published |

Keeping the Pokémon pack in a private Google Drive works well:

- **Day one:** tap Import in the app. Android's file picker shows Google Drive; pick the `.soundpack.zip` file. No public link, no sharing settings, nothing leaves the owner's account.
- **Later, optional:** a Google Drive folder can be added as a private pack repository, so updates appear in the app's Discover screen (see Pack repositories).

Guards in the public repo:

- `.gitignore` blocks `*.soundpack.zip` (and bare `*.soundpack`) outside build output and `spec/fixtures/`, and backups (`cucuto-backup-*.zip`), plus a `private/` folder for local experiments.
- A CI job fails if a commit adds audio or image files outside an allowlist (`packs/`, app resources, website assets made from demo packs).
- README and website state that no third-party content ships with the app and users are responsible for the packs they import.
- Screenshots and the explainer video use demo packs only, never Pokémon assets.

Building the owner's Pokémon pack (outside the repo):

- **Voices:** The Sounds Resource rips of spin-off games that use the anime voice cast: Hey You, Pikachu! (37 Pikachu clips), PokePark 2 (Pikachu 25, Tepig 32, Oshawott 21, Snivy), PokePark Wii (563 unlabeled voice files), Pokémon Channel (7 clips each for many Johto and Kanto species), Pokémon Puzzle League. See the earlier research report for links.
- **Sprites:** the owner prefers Dream World artwork, from the `sprites/pokemon/other/dream-world` folder of the [PokeAPI sprites repo](https://github.com/PokeAPI/sprites). These are SVG vector images and only cover generations I to V, so later Pokémon need another style (official artwork, 475 px PNG). Dream World art is static, so the app animates it (squash, stretch, bounce). Packs keep the SVGs; the packer can also rasterize them to JPEG XL or PNG. These images are copyrighted, so they stay private.
- **Workflow:** one folder per character with raw clips, a `pack.yaml` listing names and colors, then `packer build` produces the `.soundpack.zip`. Several packs (for example one per generation) can be combined with `packer bundle`.

## Website

A fully static SvelteKit site at `cucuto.mauri.app` explains the app, gets people to the APK, doubles as the default pack repository, and hosts the pack creator as an installable PWA. It needs no server.

Pages:

1. **Home.** Hero animation built from a demo pack, a one-line pitch, a "Download APK" button (latest GitHub Release asset, with version and size), the explainer video, a screenshot carousel, "how it works" in three steps, requirements (Android 17, root, LSPosed with libxposed API 102) and an FAQ.
2. **Packs.** A gallery of free packs: cover, character sprites, a play button per sample clip, size, license, a "Download .soundpack.zip" button and an "Add to app" deep link.
3. **Install guide.** Install the APK, enable the module in LSPosed with System Framework scope, reboot, run the in-app status checks.
4. **For pack authors.** The format spec rendered from `docs/pack-format.md`, packer usage, how to host your own pack repository, and a link to the Create page.
5. **Privacy.** No analytics, no cookies, no tracking.

Tech and deployment:

- SvelteKit with the static adapter; every route prerendered at build time.
- The release version, APK size and download URL are fetched from the GitHub Releases API during the build. A release triggers a site rebuild.
- Deploy from GitHub Actions to GitHub Pages (owner, #55; DNS: `cucuto.mauri.app CNAME mauriciabad.github.io`).
- Large files (APK, `.soundpack.zip` files) live as GitHub Release assets, not in the site build; GitHub allows up to 2 GiB per release file. The site hosts only small preview files: sample clips, sprites, covers.
- The build runs `packer index` over the demo packs and publishes `/repo/index.json`, so the site is also a pack repository that the app adds by default.

Images and media:

- JPEG XL preferred, never required. Serve images with a `<picture>` element: a `.jxl` source first, then AVIF or PNG fallbacks generated at build time. Chrome 155 and later decode JPEG XL by default (since October 2026), Safari since version 17; Firefox still needs a preference ([JPEG XL in Chrome](https://developer.chrome.com/blog/jpeg-xl-in-chrome)).
- The explainer video is produced by a separate subagent from the brief in [`explainer-video.md`](explainer-video.md). Owner update 2026-10-24 (#194): no humans, an AI voice, English only, and no real app UI or phone recordings: a simplified, illustrated version of the UI, so the video stays evergreen when the app changes; its project files are kept so new features can be added later (built in #271: `android/video`, ADR 0070). Owner update 2026-10-24 (#302): "the video is ready to be published it is a moment"; the cut is live on Home. The site plays `web/static/video/explainer.webm` and `.mp4`, a poster (served as JPEG XL with fallbacks, ADR 0042) and WebVTT captions. Lazy-loaded, never autoplays with sound.
- Screenshots come from the app running the demo packs, captured by a script so they can be regenerated on every release.

Design: same visual language as the app (Material 3 Expressive color, shape and spring motion), light and dark themes, responsive, accessible, Lighthouse scores of 95 or more.

## Pack repositories

A repository is any HTTPS location that serves a `repository.json` index and the files it lists. Users add its URL, browse its packs, preview them, and download the ones they like, with optional credentials.

Index example (`repository.json`, generated by `packer index`):

```json
{
  "formatVersion": 1,
  "name": "Official packs",
  "homepage": "https://cucuto.mauri.app",
  "packs": [
    {
      "id": "app.mauri.demo.cats",
      "type": "pack",
      "name": "Cats",
      "version": "1.2.0",
      "author": "Cucuto contributors",
      "license": "CC0-1.0",
      "tags": ["animals"],
      "cover": "previews/cats/cover.jxl",
      "characters": [
        { "id": "tabby", "name": "Tabby", "sprite": "previews/cats/tabby.jxl", "sample": "previews/cats/tabby.ogg" }
      ],
      "download": {
        "url": "https://github.com/OWNER/cucuto/releases/download/packs-v1/cats-1.2.0.soundpack.zip",
        "size": 4821033,
        "sha256": "…"
      }
    }
  ]
}
```

Relative paths resolve against the index URL. Entries with `"type": "bundle"` list their packs, so the app can show the contents before downloading. Previews are small separate files, so browsing stays fast.

Supported sources:

| Source | How the user adds it | When | Notes |
| --- | --- | --- | --- |
| Static HTTPS site (GitHub Pages, Cloudflare Pages, S3 or R2, any web server) | Paste the folder or `repository.json` URL, scan a QR code, or tap "Add to app" on a website | v1 | The main path. The project website is the default repository. |
| Direct link to one `.soundpack.zip` | Paste the link | v1 | One-off download through the same importer. |
| Google Drive folder | Sign in with Google and pick a folder | v1.1 | Read-only Drive API access to that folder. The app reads `repository.json` there, or lists `.zip` files and reads their manifests. Fits the owner's private Drive. Public Drive links are a poor host: files above roughly 100 MB return a virus-scan warning page instead of the file, popular files get throttled, and folders can't be listed without the API. |
| MEGA link | Paste the link | Later | MEGA encrypts files on the client, so the app would need the MEGA SDK and the key in the link to decrypt. Check the SDK's license against GPL-3.0 before committing. |

Authentication:

- Per repository, optional HTTP Basic (username and password) or a bearer token header. HTTPS only.
- Credentials are encrypted with an Android Keystore key, never logged, and never forwarded when a redirect changes host.
- Static hosts can't check passwords by themselves: on Cloudflare Pages a tiny middleware function or Cloudflare Access can add Basic auth; GitHub Pages cannot protect files. Document this for repository owners.

In the app (Discover screen):

1. **Repositories:** the default one plus user-added ones. Add by URL, QR code, or the deep link `cucuto://repo/add?url=…` from a website.
2. **Browse:** pack grid with covers, search, tag filters, and "installed" or "update available" badges. A bundle is one card with its packs inside, each with its own badge, and a pack the repository also lists on its own shows only there, so nothing appears twice (owner report 2026-10-24, #247, [ADR 0064](decisions/0064-bundles-in-discover.md)).
3. **Preview:** play sample clips and haptics before downloading; only the small preview files are fetched. The index carries each character's haptic (`x-haptic`) and the phone computes auto envelopes from the sample (owner update 2026-10-24 (#272), [ADR 0069](decisions/0069-haptic-previews-in-discover.md)). Each preview also draws the vibration, in the app and on the website's Packs page, which can't vibrate (owner update 2026-10-24 (#301)).
4. **Download:** animated progress, then size and SHA-256 checks, then the standard importer (bundles show their pack checklist).
5. **Updates:** repositories are checked when the app opens, with ETag caching. No background jobs.

Security: everything remote is untrusted. Apply the same validation as local imports, check the advertised size before downloading, reject SHA-256 mismatches, cap redirects, and show each repository's host clearly. Adding a repository from an unknown source shows a short warning.

## Pack creator (app and website)

People can build packs without the command line, either in the app or in the website installed as a PWA. Both produce standard `.soundpack.zip` files, and everything runs on the device: nothing is uploaded anywhere.

The creation flow, the same on both:

1. **Pack details:** name, author, license (with CC0 and CC-BY presets), description, cover.
2. **Characters:** add a character, pick or crop a sprite (any supported image format, animated allowed), name it, and pick a seed color (suggested from the sprite).
3. **Clips:** import audio files or record with the microphone. A waveform editor trims start and end, with optional silence trimming and loudness normalization. Each clip gets a label and a weight.
4. **Haptics:** auto mode by default; the haptic editor for custom patterns.
5. **Validate and export:** run the same checks as the importer, then export a `.soundpack.zip` (single pack or a bundle of several). The app can also install the result directly.

In the app:

- A **Create** entry on the Packs screen. Packs installed from a repository can be duplicated and edited; the original stays untouched.
- Recording uses `RECORD_AUDIO`, asked only when the user taps Record.
- Live preview with real haptics, plus "Simulate message" to hear the clip exactly as a notification would play it.
- Export through the share sheet or the Storage Access Framework (for example, straight into Google Drive).

In the website (PWA):

- A **Create** page, installable as a PWA and usable offline (service worker, web app manifest). Where the browser supports file handling, the installed PWA opens pack files directly (`file_handlers` for `application/zip` / `.zip`; owner update 2026-10-23).
- Audio is decoded and trimmed with the Web Audio API and recorded with MediaRecorder. Encode with WebCodecs where the browser supports the codec, otherwise fall back to WAV (a WebAssembly encoder would need `'wasm-unsafe-eval'` in the site's CSP; [ADR 0038](decisions/0038-web-pack-creator.md)). Layout, file names and clip processing are the shared Kotlin of both creators (`authoring` in `shared/pack-spec`).
- Images are cropped on a canvas and written as JPEG XL where the browser can encode it, otherwise WebP, PNG or JPEG (ADR 0038).
- Packs are zipped in the browser and downloaded. A "host your own repository" helper takes several packs and produces a ready-to-upload folder with `repository.json` and previews, the same as `packer index` (shared `RepositoryIndexer`), with steps for GitHub Pages and other hosts (#191, [ADR 0058](decisions/0058-create-page-tools.md)). A bundle tool combines finished packs like `packer bundle`.
- Drafts are saved in the browser (IndexedDB) after every change, and an existing `.soundpack.zip` opens as a draft with the same pass-through rules as the app's duplicate-and-edit (ADR 0058).
- Haptic patterns are edited visually, with the app's "Copy for AI" and "Paste pattern" (shared Kotlin, ADR 0058). Browsers can't play rich haptics (at best simple on/off vibration on Android Chrome), so the page says to preview haptics in the app.

Shared rules: the JSON Schemas in `spec/` and a set of golden test packs (valid and invalid) are the contract. One Kotlin implementation (`shared/pack-spec`) validates everywhere: in `packer`, in the app, and on the website compiled to JavaScript (owner, #60: option 1; Kotlin/JS rather than Wasm per ADR 0022, #116). It runs against the same fixtures in CI, so a pack made anywhere imports everywhere. Spike #117 settled how much more of the PWA creator is shared Kotlin: its logic (IDs, layout, `pack.json`, clip DSP), compiled to JS, while the UI stays Svelte ([ADR 0038](decisions/0038-web-pack-creator.md)).

## Haptics

Every character gets a vibration that is built with `VibrationEffect.Builder` and played by the system alongside its clip. In-app touches get their own lighter haptics.

**API choice.** Use `VibrationEffect.Builder` as the primary API. It sequences presets (`PRESET_CLICK`, `PRESET_TICK`, `PRESET_LOW_TICK` and others) and basic envelopes on a timeline, and the framework falls back automatically when the hardware lacks a preset or envelope ([Create custom haptic effects](https://developer.android.com/develop/ui/views/haptics/custom-haptic-effects)). Google's docs label it "Android 16 (26Q4)"; confirm it exists on the owner's build in M0. If it is missing, fall back to `BasicEnvelopeBuilder` (API 36) plus `Composition` with explicit support checks. Do not use `WaveformEnvelopeBuilder` in packs: it has no fallback and depends on the device's frequency profile.

Haptic schema in `pack.json` (custom mode), mapped one-to-one onto the Builder:

```json
"haptic": {
  "mode": "custom",
  "events": [
    { "at": 0,   "preset": "CLICK", "scale": 0.8 },
    { "at": 120, "preset": "TICK",  "scale": 0.5 },
    { "at": 220, "envelope": [
        { "intensity": 1.0, "sharpness": 0.8, "ms": 120 },
        { "intensity": 0.0, "sharpness": 0.4, "ms": 180 }
    ] }
  ]
}
```

Where haptics come from, in order of precedence:

1. **Per-contact override** set in the haptic editor.
2. **Pack custom pattern** for the character.
3. **Auto mode, "haptics follow the voice":** at import, decode each clip, compute its loudness envelope (about 20 ms windows), simplify it to the device's maximum control points and store it as a basic envelope per clip. The vibration then rises and falls with the voice. Clamp the result to at most 2 s and always end at zero intensity, which the envelope API requires.

Playback in notifications: the module sets the chosen `VibrationEffect` on WhatsApp's notification record, so the system plays it with notification usage. The owner's notification vibration intensity setting, DND and ringer mode then apply automatically.

In-app haptics: use Compose `LocalHapticFeedback` types for standard touches (toggles, confirms, list ticks). Reserve richer Builder effects for moments of delight: assigning a character, importing a pack, a successful test. Follow Android's haptics design principles: short, crisp, never a long buzz for a UI action.

**Haptic editor (M4).** A timeline screen where the owner taps to drop presets, drags envelope points, sees a live waveform, and previews with the clip. It also has a "tap the rhythm" mode that records taps into presets. Owner suggestion (#170): recommend that users ask an AI to write the vibration from the sound wave for more precise results than auto mode; the editor helps with "copy for AI" and "paste pattern" (#217).

## UX, game feel and Material 3 Expressive

The app should feel like a small game: every action answers with synchronized motion, sound and haptics, on a Material 3 Expressive foundation. Juice never gets in the way of getting a contact set up in under 10 seconds.

Screens over dialogs (owner, #166): a flow or a body of content gets a real screen with predictive back; dialogs and bottom sheets are only for very minimal, temporary content (a confirmation, a short choice, a blocking progress card). Conversions: #218 (ADR 0055).

Words (update 2026-10-24 (#343)): the app uses one word for each thing, in every screen, in English and Spanish
(`values-es/strings.xml` lists the Spanish shared terms):

| Thing | English | Spanish |
| --- | --- | --- |
| A figure from a pack, with its sprite or album cover | character | personaje |
| The character whose clips play when a chat messages you; giving one is "give a voice" | voice | voz |
| One sound a character plays for a message | clip | clip |
| What rings when a contact calls; the song a character can have | ringtone | tono |
| The two sounds of a chat, as rows and section titles (contact detail, sample chat, Settings) | Messages · Phone calls | Mensajes · Llamadas |
| The ringtone choices that are not a character | Android default · Leave as is | Predeterminado de Android · Dejar como está |
| What plays without a voice | WhatsApp's tone | el tono de WhatsApp |
| A file of characters · several packs in one file | pack · bundle | pack · colección |

"Message sound" and "notification sound" are not used in the app: say "voice" or "clip". A call is "calls you", not
"phones you". Buttons that open a file say "Choose …".

Screens:

1. **Status and onboarding.** One card that turns green when everything works: module active in `system_server`, correct LSPosed scope, WhatsApp detected, at least one pack installed. Each failing check has a one-tap fix or a clear instruction.
2. **Home (contacts).** A list of chats with their character's sprite as avatar, and the ringtone's small album cover (or a call icon for Android default) tucked against it (owner update 2026-10-24 (#298)). Assigning a voice here is two steps: the message sound, then the ringtone (skippable). A "recent senders" strip shows people who messaged recently and have no character yet. Search, filter by pack, and a FAB to assign. Before the first chat arrives, Home offers a **sample chat**: a demo, kept out of the database and the routing table, that plays a chosen voice and ringtone in the app, so reviewers and people without the module can try the core feature (update 2026-10-24 (#285), ADR 0075). "Add a chat from contacts" adds people who have not written yet; they wait in their own section until their first message links them (owner update 2026-10-24 (#254), [ADR 0065](decisions/0065-chats-before-they-message.md)).
3. **Character picker.** A grid of sprites or album covers grouped by pack, with only the characters that have notification clips (owner update 2026-10-24 (#245)). Tap previews (sprite bounces, clip plays, haptic fires). A second tap assigns. Owner update 2026-10-24 (#289, #290): pack sections fold so long packs stay navigable (the contact's current pack unfolded, the fold state remembered, pack chips to jump, search that finds characters in folded packs), and a "Surprise me" button per pack and one for every pack select and preview a random eligible character, then re-roll (never the same one twice in a row) or confirm ([ADR 0072](decisions/0072-picker-folding-and-surprise-me.md)). The ringtone picker works the same way.
4. **Contact detail.** A header with the message character's avatar and the ringtone's album cover, "Rings with …" under the name (owner update 2026-10-24 (#298)). Two rows, "Messages" (the assigned character) and "Phone calls" (the ringtone), each one tap from its picker (owner update 2026-10-24 (#245)); the clip pool with per-clip toggles, the ringtone's variant rule and linked Android contact, haptic override, group policy (one character for the group, or per sender), and a "Simulate message" button that runs the real pipeline.
5. **Packs.** Installed packs as a carousel, import from a file, create a new pack (see Pack creator), pack detail with all characters, update and remove.
6. **Discover.** Browse pack repositories, preview and download packs, manage repositories and their credentials (see Pack repositories).
7. **Haptic editor.** See the Haptics section.
8. **Settings and diagnostics.** Global on/off, Quick Settings tile, backup and restore, and a decision log: for each recent notification, the package, matched contact, chosen clip and why (or why it was skipped). This log is essential for debugging.

Juice checklist:

- One `Feedback` engine fires sound, haptic and visual together from a single timestamp so they never drift apart.
- Spring motion everywhere through `MaterialTheme.motionScheme` (expressive scheme).
- Sprites squash and stretch when they "speak". Avatars morph shape on press using Material shapes.
- Assigning a character shows a short particle burst in the character's color, with the clip and a rising haptic.
- Shared-element transitions from grid to detail. Predictive back animations on every screen.
- Each character screen takes its colors from the sprite (palette extraction to a seed color, then a dynamic color scheme).
- A small built-in set of CC0 UI sounds (tap, toggle, success, error), switchable off and quiet by default.
- Loading states use expressive progress indicators, never a bare spinner. Brand motif: a cuckoo clock whose little door pops open to reveal the character who's "calling". Use it in the app icon, onboarding, the assign animation and the website hero.

Material and accessibility:

- Use Compose Material 3 1.5.0 for Expressive components. As of 21 Oct 2026, 1.4.0 is stable and 1.5.0 is in beta (beta02); most Expressive APIs (motion scheme, button groups, split button, floating toolbar, FAB menu, wavy progress, expressive lists) are no longer experimental. `MaterialShapes` and `LoadingIndicator` still need opt-in ([release notes](https://developer.android.com/jetpack/androidx/releases/compose-material3)).
- Edge-to-edge, dynamic color with a brand fallback, light and dark themes.
- Respect the system "remove animations" setting: keep state changes, drop decorative motion and particles.
- Every sprite and button has a TalkBack label. Never encode meaning only in color, sound or haptics.
- Smooth at 120 Hz: baseline profiles, no work on the main thread when decoding sprites or audio.

## Tech stack and project setup

The Android side is Kotlin, Jetpack Compose and the libxposed API 102 in one Gradle project producing a single APK; the website is SvelteKit. Pick the newest stable versions on day one and record them in version catalogs; use the 1.5.0 Material 3 beta only for Expressive components.

| Area | Choice | Notes |
| --- | --- | --- |
| SDK | `minSdk 37`, `targetSdk 37`, `compileSdk` = newest 37.x | Recent Compose releases require a 37.x `compileSdk`; check the release notes when setting it |
| Language and build | Kotlin, Android Gradle Plugin 9.x, Gradle version catalog | Kotlin only, no Java sources |
| UI | Jetpack Compose, Material 3 1.5.0 (Expressive), Navigation 3 | `MaterialExpressiveTheme`, expressive motion scheme |
| Images | Coil 3 with the platform decoders (PNG, JPEG, WebP, GIF, AVIF, animated WebP and GIF), plus [jxl-rs](https://github.com/libjxl/jxl-rs) behind a small JNI layer and a Coil decoder for JPEG XL ([ADR 0006](decisions/0006-jpeg-xl-decoding.md); replaces jxl-coder) | JXL support is a nice-to-have: ship only the arm64 native library to keep the APK small and check the license is GPL-compatible. If it causes crashes or bloat, drop it and convert JXL to WebP or PNG at import. |
| Data | Room (contacts, assignments, repositories, decision log), DataStore (settings), kotlinx.serialization (manifests, indexes) |  |
| Networking | OkHttp with ETag caching, redirect limits and per-host credentials | For pack repositories only; the module never touches the network |
| Secrets | Android Keystore key encrypting repository credentials | Never logged, never exported in backups |
| DI | Hilt | Keep the module code free of DI frameworks |
| Audio in app | `SoundPool` for previews (the app is in the foreground, so Android 17's background limits do not apply) | Decoding with `MediaExtractor` and `MediaCodec` for the auto-haptic analysis |
| Hooking | [libxposed API](https://github.com/libxposed/api) 102, `compileOnly` | `minApiVersion` and `targetApiVersion` 102; modules targeting 102 cannot call the legacy Xposed API, so don't |
| Module scope | System Framework only: `system` in `scope.list`, which is `system_server` for modern modules ([ADR 0002](decisions/0002-xposed-scope-system.md); `android` would be wrong) | Never scope WhatsApp |
| Root utilities | None (libsu was planned) | Cucuto reads none of WhatsApp's files, so the root import that needed libsu is not built (owner update 2026-10-24 (#254), [ADR 0065](decisions/0065-chats-before-they-message.md)). |
| Website | SvelteKit (Svelte 5) with the static adapter, TypeScript, service worker and web app manifest for the PWA; Bun for installs and scripts (owner, #63; #115) | Deployed by GitHub Actions to GitHub Pages (#55) |
| Tests | JUnit and Turbine (unit), Compose UI tests, Playwright for the site, a device test checklist |  |
| CI | GitHub Actions: Android build, lint and tests; web build; demo packs and repository index; media allowlist guard; release APK on tag |  |

Gradle modules (under `android/`):

- `:app` — Compose UI, view models, onboarding, Discover, pack creator, diagnostics.
- `:xposed` — module entry point and hooks. Small, defensive, no Compose and no heavy dependencies, since it loads inside `system_server`.
- `:core:pack` — pack and bundle models, JSON Schema validation, safe unzip, importer.
- `:core:repo` — repository sources (HTTPS index first, Google Drive later), credentials, download and verification.
- `:core:data` — Room database, repositories, the routing table that the module reads.
- `:core:conditions` — `when` conditions (day and night from sunrise and sunset, hour ranges, seasons, weekdays, dates) compiled into instants, and where local days start for first-of-the-day clips; shared by clips and ringtone variants.
- `:core:ringtone` — MediaStore export, per-contact ringtones, variant rules and boundary alarms.
- `:core:backup` — backup export, encryption, scheduled backups and restore.
- `:core:haptics` — pack haptic schema to `VibrationEffect`, auto-haptic generator, capability checks.
- `:core:audio` — decoding, loudness envelope analysis, preview playback, and recording, trimming, clip cutting and normalization for the pack creator.
- `:core:codec` — the platform audio decoding loop shared by the import checks and auto haptics: batched input, one decoder per worker (ADR 0077).

Outside `android/`:

- `tools/packer` — JVM command-line tool. `packer build` turns a folder plus `pack.yaml` into a validated `<id>-<version>.soundpack.zip`; `packer bundle` combines packs into one file; `packer index` writes `repository.json` with previews, sizes and SHA-256 hashes. With ffmpeg it can trim silence, normalize loudness and transcode to Opus; with libjxl's `cjxl` it can convert images to JPEG XL. All conversions are opt-in flags.
- `web/` — the SvelteKit site, which reuses the schemas in `spec/` to render the packs gallery.

How the app and the module talk:

- **Routing table (app to module):** the app writes a compact table (contact key, character id, clip list, haptic) to libxposed Remote Preferences, which the framework stores and serves to the module. The module caches it in memory and reloads on change. Never do disk or database work on the notification path.
- **Clip files (system to app):** an exported read-only `ContentProvider` at `content://<appId>.clips/<pack>/<character>/<clip>`. It checks the calling UID and allows only the system and the process that actually opens notification sounds (historically SystemUI's ringtone player; confirm in M0). It refuses everyone else.
- **Seen senders (module to app):** the module posts a lightweight event (package, conversation key, display name, group flag) that the app records. The event goes through the provider or a protected broadcast, so the home screen can offer "recent senders" for one-tap assignment.

Contact identity: the stable key is `package + conversation key`. Prefer the notification's conversation shortcut ID if M0 shows it is the WhatsApp JID; otherwise fall back to the MessagingStyle conversation title. Contacts who have not written yet can be added from Android's contacts: WhatsApp's own sync adapter keeps an entry with a phone JID for each WhatsApp contact saved on the phone. Cucuto reads those entries (`READ_CONTACTS`, already used for phone ringtones), never WhatsApp's database. "Add a chat from contacts" lists them behind an explainer screen (shown only while the contacts permission is missing; owner update 2026-10-24 (#337): "add from contact shoudl not show the warning every time, just the first time") and saves only the people the user picks. They can get a voice and a phone ringtone right away; the ringtone works at once. The chat's `@lid` is linked by name on its first notification, so that first message still plays WhatsApp's tone. Only an exact, unique match links by itself, with no namesake in the phone's contacts; anything else is asked on Home. Groups appear when they first notify. Owner update 2026-10-24 (#254): "contacts" ([ADR 0065](decisions/0065-chats-before-they-message.md), built in #292); the kickoff plan was a root read of WhatsApp's local database.

## Milestones and acceptance criteria

Ten milestones, each ending in something the owner can install and try. M0 is a go/no-go gate: if a check fails, report back with findings before building further. Ringtones are part of v1 (owner, #59): M9 lands before the M7 release; only the pack creator (M8) follows in v1.1.

### Status (2026-10-24)

Development is near the end (owner, 2026-10-24). From now on, occasional single agents do small features ([maintaining.md](maintaining.md)). Signed test releases (v0.1.x) ship from `main`, and the website is live. `owner-action` marks what only the owner can do.

| Milestone | Done | Left |
| --- | --- | --- |
| M0 Device spike | Gate passed (GO, [M0 report](m0-report.md)): hook points, swaps, DND, silent, bursts, WaEnhancer X | Muted-chat parity check (#12, owner) |
| M1 Core engine | Everything: importer, routing table, production hook, clip mirror, timing and battery | — |
| M2 Contacts and senders | Recent senders, group policy, WhatsApp Business | Add chats from Android contacts (#292); group sender device check (#157, owner) |
| M3 UI and game feel | All screens and the four-tab shell, feedback engine, Spanish, accessibility pass, picker folding and "Surprise me" | TalkBack and Scanner walkthrough on the phone (#226, owner) |
| M4 Haptics | Everything: pack haptics, auto haptics, editor, per-contact override, AI-written patterns | — |
| M5 Pack repositories | `packer index` and bundles, Discover with previews and haptic previews, drawn haptic previews on the website and in the app (#301), credentials, verification | Import speed (#279, #268); Drive folder source, v1.1 (#37, blocked on #188) |
| M6 Website | Every page, release-driven builds, the default repository, JPEG XL, screenshots, GitHub Pages and DNS | Explainer video rework (#271); TypeScript 7 (#162, blocked upstream) |
| M7 Open-source release | Public repo and docs, CI with the media guard, signed releases, backup and restore UI, Google Play bundle and listing | Contact re-linking (PR #266) and a wiped-phone restore (#43, owner); review sample chat (#285); Play account, video and defaults (#276, #286, #287, owner); clean-install end to end (#46, owner); F-Droid after v1 (#275) |
| M8 Pack creator (v1.1) | In-app creator, website Create page and its tools, golden packs through every validator | Creator vibrations, ringtones and animated sprites (#185, PR #225) |
| M9 Ringtones | Ring files and auto-cut clips, phone ringtones with variants, ringtone picker, independent voice and ringtone, day and night, the ringtone rules screen and per-contact rules (#288) | Sound flow rethink (#298); supervised device runs (#143, #228, owner); automatic WhatsApp call ringtones (#273, blocked on #228); the owner's K.K. check |

No milestone: special clips (#120, PR #258), finding Drive backups on a fresh install (#163, needs #188).

### M0 — Device spike (gate)

A throwaway module plus a debug screen, run on the owner's phone.

- [x] Record device model, ROM build, root manager, LSPosed/Vector version and supported libxposed API level.
- [x] Module loads in `system_server` and logs every WhatsApp notification that reaches the alerting stage.
- [x] Locate the real hook point on this Android 17 build: in recent AOSP the alerting logic lives in `NotificationAttentionHelper` (sound, vibration, LED); confirm method names and signatures from the device's framework.
- [x] Dump for one 1:1 message and one group message: channel ID, sound URI, vibration, shortcut ID, MessagingStyle conversation title, sender `Person`, group key, group alert behavior, and whether the child or the summary is the record that alerts.
- [x] Swap the sound to a test clip served by our provider and hear it. Identify which process opens the URI.
- [x] Swap the vibration to a `VibrationEffect.Builder` effect and feel it. Confirm the Builder API exists on this build.
- [ ] Confirm muted chats, Do Not Disturb, silent mode and quick bursts behave exactly as with WhatsApp's own tone.
- [x] Confirm WaEnhancer X keeps working with our module enabled.

### M1 — Core engine

- [x] Pack and bundle importer with validation, safe unzip, decoding of every supported image and audio format, and install, tested with the demo packs and a malformed pack set (zip slip, oversize, bad schema).
- [x] Room database for contacts and assignments; routing table published to the module.
- [x] Module hook swaps sound and vibration using the routing table. Random clip per message with a shuffle bag, so the same clip never plays twice in a row.
- [x] Unassigned contacts keep WhatsApp's default tone. A global switch turns everything off.
- [x] Hook adds no noticeable delay: under 5 ms of work on the notification path, all lookups from memory.
- [x] The clip starts at the same instant WhatsApp's own tone would, also when Cucuto's process is not running, and battery life is preserved: no wake-ups, wake locks or services of our own (owner, #88; measured in #121).

### M2 — Contacts and senders

- [ ] "Recent senders" feed from the module, with one-tap assignment.
- [ ] Add chats before they message, from Android's contacts (WhatsApp's own entries there), not from WhatsApp's database. Groups come from notifications. Owner update 2026-10-24 (#254) ([ADR 0065](decisions/0065-chats-before-they-message.md), #292; was "optional root import of all WhatsApp chats and groups").
- [ ] Group policy: one character per group, or per sender inside a group.
- [ ] WhatsApp Business supported with the same flows.

### M3 — UI and game feel

- [x] All screens from the UX section, built with Material 3 Expressive.
- [x] Feedback engine with synchronized sound, haptic and motion; full juice checklist done.
- [ ] Accessibility pass: TalkBack walkthrough, large fonts, "remove animations" setting.
- [x] Pickers stay navigable with large packs: folding pack sections, search, quick jump, and "Surprise me" per pack and across every pack (owner, #289 and #290; [ADR 0072](decisions/0072-picker-folding-and-surprise-me.md)).

### M4 — Haptics

- [x] Pack haptic schema implemented, including auto mode ("haptics follow the voice").
- [x] Haptic editor with live preview and tap-the-rhythm recording.
- [x] Per-contact haptic override.

### M5 — Pack repositories and bundles

- [x] `repository.json` schema and `packer index`; `packer bundle` for multi-pack files.
- [x] Discover screen: add repositories by URL, QR code or deep link; browse, preview samples and haptics, download, update badges. Haptic previews: the owner wanted them done eventually (#175, item 7), built in #272 ([ADR 0069](decisions/0069-haptic-previews-in-discover.md)). Owner update 2026-10-24 (#301): previews also draw the vibration, in Discover and on the website.
- [x] Basic auth and bearer tokens stored with a Keystore key; credentials never sent across hosts.
- [x] SHA-256 and size verification; malicious-index tests (huge sizes, redirect loops, bad hashes).
- [ ] v1.1 follow-up: Google Drive folder source with read-only access, tested against the owner's private Drive.

### M6 — Website

- [x] SvelteKit static site with Home, Packs, Install guide, For pack authors and Privacy pages.
- [x] APK download wired to the latest GitHub Release; rebuild on every release.
- [x] Demo packs built in CI, published as release assets, indexed at `/repo/index.json` and set as the app's default repository.
- [x] JPEG XL images with generated fallbacks; scripted screenshots from the demo packs; slot ready for the explainer video from the video subagent.
- [x] Deployed to GitHub Pages (#55), with the exact DNS record for `cucuto.mauri.app` handed to the owner.

### M7 — Open-source release

- [x] Public monorepo with README, LICENSE (GPL-3.0-or-later), pack format docs, schemas, `packer`, CONTRIBUTING.
- [ ] Backup and restore: manual export, automatic backups to a chosen folder, optional encryption, and a tested restore on a wiped phone.
- [x] CI green, including the media allowlist guard.
- [x] Signed release APK on GitHub Releases, linked from the website.
- [ ] Google Play as an extra channel (owner update 2026-10-24, #211): app bundle, listing and review notes (#274, ready: [ADR 0071](decisions/0071-google-play-bundle-and-listing.md), [google-play.md](google-play.md)); the owner's account, signing key and closed test (#276). Approval is not guaranteed ([distribution.md](distribution.md#google-play)).
- [ ] After v1: F-Droid, reproducible and signed with Cucuto's key (#275).
- [ ] The owner imports the private packs from Google Drive on a clean install and everything works end to end.

### M8 — Pack creator (v1.1; can move earlier)

- [ ] In-app creator: pack details, characters, sprite crop, import or record clips, waveform trim, normalize, haptics, validate, export or install.
- [x] Duplicate-and-edit for installed packs.
- [x] Website Create page as an installable, offline-capable PWA with the same flow, all processing in the browser (#48, [ADR 0038](decisions/0038-web-pack-creator.md); covers, bundles, custom haptics, drafts and editing existing packs in #191, [ADR 0058](decisions/0058-create-page-tools.md)).
- [x] "Host your own repository" helper that outputs a folder with `repository.json` and previews (#191, ADR 0058).
- [x] Golden test packs run through `shared/pack-spec` on the JVM (packer), on Android and in the browser build used by the website, all green in CI (#116).

### M9 — Ringtones (v1, before the M7 release)

- [x] Spike first: confirm Telecom's ringer hook point and scope for random-per-call, and find out how WhatsApp plays its call ringtone (static phase: [M9 report](m9-report.md), ADR 0023; device run pending).
- [ ] `ringtones` in the pack schema, loop points, variants with `when` conditions; packer and creator support including auto-cut notification clips.
- [ ] Phone ringtones through `CUSTOM_RINGTONE` with MediaStore export; rule-based variants rewritten at boundaries.
- [x] Album-cover ringtone picker with preview and variant chips; contact linking by phone number ([ADR 0057](decisions/0057-ringtone-picker-and-ringtone-backups.md)).
- [x] Message character and ringtone chosen independently, pickers filtered by what each character offers, ringtone previews that play like the call from the start (owner, #245, #246 and #242; [ADR 0063](decisions/0063-independent-voice-and-ringtone.md)).
- [x] Two picks as the main flow (the message sound, then the ringtone), "Android default" and "Leave as is" ringtones, and the ringtone on Home and the contact header (owner, #298; [ADR 0073](decisions/0073-sound-flow-message-then-ringtone.md)).
- [ ] Automatic WhatsApp call ringtones through an optional, opt-in WhatsApp scope (owner, #145; ADR 0051; spike #215: static phase in the [WhatsApp ringtone spike](whatsapp-ringtone-spike.md), ADR 0052; device run #228; build #273), compatible with WaEnhancer X, for WhatsApp and WhatsApp Business (owner update 2026-10-24, #229). Without the scope, the guided per-chat ringtone.
- [ ] The owner's K.K. Slider pack assigned to several contacts, with day and night variants switching correctly.

Later ideas, out of scope for v1: other messengers (Telegram, Signal, SMS), a home-screen widget, MEGA links as a pack source, community pack submissions to the official repository.

## Risks and open questions

The biggest risk is framework internals shifting under the hook; the mitigations below keep failures silent and safe (WhatsApp's own tone plays).

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Hook point differs on the owner's build or changes in a later QPR | Custom sounds stop; default tone plays | Resolve methods by name with several candidate signatures, log clearly, no-op on failure. Status screen shows "hook not active". |
| LSPosed/Vector instability on Android 17 (users report boot loops on some builds) | Module can't load | The owner's framework already runs WaEnhancer X with API 102. Keep the module tiny and catch every exception inside hooks; never throw in `system_server`. |
| Group messages alert through the summary notification, not the child | Wrong or missing sender | M0 confirms which record alerts. If it is the summary, resolve the sender from the newest child in the same group. |
| The process that plays notification sounds can't read our clip URI | Silence instead of a clip | Provider caller allowlist confirmed in M0. Fallback: have the module copy clips to a location that process can read. |
| WhatsApp identifiers change (phone JIDs versus newer `@lid` IDs) | Assignments stop matching | Store every key seen for a contact; match on any. The "recent senders" feed makes reassignment one tap. |
| WhatsApp plays its call ringtone inside its own process (confirmed statically in M9) | No custom WhatsApp call ringtones without hooking WhatsApp | Guided per-chat ringtone by default; automatic ringtones only through the optional WhatsApp scope (ADR 0051, #215), which can break when WhatsApp changes its ringer, so it falls back to WhatsApp's own ringtone on any error. Phone-call ringtones work natively regardless. |
| Phone ringtone files deleted or moved by the user | Contacts fall back to the default ringtone | Keep ringtones in a dedicated `Ringtones/cucuto/` folder and re-check them on app start; repair automatically. |
| JPEG XL decoding gaps (no built-in Android decoder assumed; Firefox needs a preference) | Missing sprites or site images | JXL is optional: bundle jxl-rs (ADR 0006), otherwise convert at import. On the site, serve `<picture>` with fallbacks. |
| Malicious or broken pack repositories | Crashes, storage abuse, misleading content | Same validation as local imports, size and SHA-256 checks, redirect caps, warning when adding an unknown repository. |
| Google Drive and MEGA links are unreliable hosts | Failed downloads | Recommend static hosts for public repositories; use the Drive API (not public links) for Drive folders; treat MEGA as a later, SDK-based source. |
| Losing the phone | All assignments gone | Automatic backups to a user-chosen folder (for example Google Drive), tested restore. |
| Copyrighted media leaks into the public repo | Takedown risk | `.gitignore`, CI media allowlist, demo-pack-only screenshots and video. |
| Google Play rejects or removes Cucuto (root and LSPosed, the optional WhatsApp hook read as interfering with other apps, the WhatsApp trademark) | Play listing lost; account strikes | Play is an extra channel only; GitHub Releases and Obtainium stay the main one. No "WhatsApp" in the name, icon or developer name; review notes explain the hook; rejections are answered, never resubmitted unchanged ([distribution.md](distribution.md#google-play), #276). |
| Very rapid bursts of messages | Clips cut each other off | The system already plays one notification sound at a time; cooldown softens bursts. Optional per-contact minimum gap in settings. |

Open questions for the owner (answer before M1 unless noted):

1. Which GitHub account or organization owns the `cucuto` repo (it replaces `OWNER` in URLs). **Settled:** `mauriciabad`.
2. Hosting for the site: GitHub Pages or Cloudflare Pages (needed by M6). **Settled: GitHub Pages** (owner, #55).
3. Default for group chats: one character per group, or per sender? **Settled: one character per group**, with "per sender" as a per-group option (owner, #56).
4. Include WhatsApp Business from v1? The design supports it at no extra cost. **Settled: yes** (default accepted, #57).
5. Who hands the explainer video brief to the video subagent, and when: after M3, once the UI is final, is suggested. **Settled: after M3**, with demo packs only (default accepted, #58).
6. Should ringtones (M9) ship in v1, or follow in v1.1 like the pack creator? **Settled: v1** (owner, #59); M9 lands before the M7 release.

Later owner answers are recorded in [`owner-log.md`](owner-log.md) and the linked `question` issues.

Settled: name Cucuto (application ID app.mauri.cucuto, site cucuto.mauri.app), license GPL-3.0-or-later, libxposed API 102, monorepo, common image and audio formats with JPEG XL as the preferred image format, `.soundpack` as the file extension, replaced by `<name>.soundpack.zip` for packs and bundles and `cucuto-backup-YYYY-MM-DD.zip` for backups (owner update 2026-10-23: zip files always end in `.zip`; the type is detected from the contents).

## Sources

- [Background audio hardening (Android 17)](https://developer.android.com/about/versions/17/changes/bg-audio)
- [Behavior changes: apps targeting Android 17](https://developer.android.com/about/versions/17/behavior-changes-17)
- [Create custom haptic effects](https://developer.android.com/develop/ui/views/haptics/custom-haptic-effects)
- [Compose Material 3 release notes](https://developer.android.com/jetpack/androidx/releases/compose-material3)
- [NotificationAttentionHelper in AOSP](https://android.googlesource.com/platform//frameworks/base/+/330dc32a01316b5e31c4ea5afdf834cb0497f057/services/core/java/com/android/server/notification/NotificationAttentionHelper.java)
- [libxposed API](https://github.com/libxposed/api) and [Develop modules with the modern Xposed API](https://www.github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API)
- [Vector (LSPosed fork)](https://github.com/JingMatrix/Vector) and [Android 17 crash-loop discussion](https://github.com/JingMatrix/Vector/discussions/759)
- [WaEnhancer X releases](https://newreleases.io/project/github/mubashardev/WaEnhancer/release/1.7.0)
- [PokeAPI sprites (including Dream World SVGs)](https://github.com/PokeAPI/sprites)
- [K.K. Slider Complete Songs Collection on KHInsider](https://downloads.khinsider.com/game-soundtracks/album/animal-crossing-k.k.-slider-complete-songs-collection)
- [JPEG XL in Chrome (Chrome 155)](https://developer.chrome.com/blog/jpeg-xl-in-chrome) and [JPEG XL browser status, July 2026](https://freetoolonline.com/news/jpeg-xl-returns-chrome-firefox.html)
- [jxl-coder for Android](https://github.com/awxkee/jxl-coder) and [its Coil plugin](https://github.com/awxkee/jxl-coder-coil)
- [Google Drive direct links and the virus-scan threshold](https://u2l.ai/tools/google-drive-direct-link-generator)
