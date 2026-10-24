# Google Play

Everything Play Console asks for, drafted so the owner can copy it in. Why and how: [ADR 0071](decisions/0071-google-play-bundle-and-listing.md).
Policy assessment: [distribution.md → Google Play](distribution.md#google-play). The owner's checklist (account,
keys, closed test, submission): #276. The defaults the owner answered (signing, category, audience, upload
tool): #287.

Keep this page true. A new permission, a new network call or a new data flow updates the
[privacy page](https://cucuto.mauri.app/privacy/) (`web/src/lib/i18n/{en,es}.ts`), the Data safety answers below and
`ALLOWED_PERMISSIONS` in `.github/workflows/ci.yml` in the same PR. CI fails until the allowlist matches.

## What the repository provides

| Play needs | Where it comes from |
| --- | --- |
| App bundle (`.aab`) | Each `v*` release attaches `cucuto-<version>.aab`, signed with the release key, which is Play's upload key ([releasing.md](releasing.md#google-play-bundle)) |
| Target API 36 or higher | `targetSdk` 37. CI checks it (`Check Play requirements` in `ci.yml`) |
| 16 KB page support | Checked on every PR: native libraries zip-aligned to 16 KB, ELF `LOAD` segments aligned to 16 KB. The Rust `libcucuto_jxl.so` passes as is (NDK r30 linker) |
| Store listing, English and Spanish | `fastlane/metadata/android/{en-US,es-ES}/`: `title.txt`, `short_description.txt`, `full_description.txt`, `changelogs/<versionCode>.txt` |
| Icon 512 × 512, 32-bit PNG | `fastlane/metadata/android/en-US/images/icon.png` (es-ES falls back to it) |
| Feature graphic 1024 × 500, no alpha | `fastlane/metadata/android/<locale>/images/featureGraphic.png`, from `brand/tools/build-store.mjs` |
| Phone screenshots (2 to 8) | Six per language, 1080 × 1920, no alpha: `images/phoneScreenshots/`, from `./gradlew :app:exportStoreScreenshots` |
| Upload tool (optional) | `fastlane/Supplyfile`, run by the owner ([releasing.md](releasing.md#upload-to-google-play)) |

`kotlin tools/release/release-notes.main.kts --check` (CI's `store-metadata` job) checks the listing against Play's
limits: title 30, short description 80, full description 4000, release notes 500 characters, only `<b>` and `<i>`
tags, no "WhatsApp" or "Meta" in the title or in the first words of the short description, the disclaimer at the
end, and the image sizes, ratios and alpha.

## Signing

Owner update 2026-10-24 (#287, second answer; ADR 0071): Play signs the APKs it serves with **Cucuto's existing
release key**, which the owner gives Play once with Google's PEPK tool. The **upload key is the same release key**:
the release workflow already signs the bundle with it, from the secrets that sign the APK. No new keystore, no new
secret.

Google's documentation ([Sign your app](https://developer.android.com/studio/publish/app-signing)): "To provide your
own signing key for Google to use when signing your app, select **Change app signing key** and select one of the
**Export and upload** options". And "If you do not generate a new upload key, you continue to use your app signing
key as your upload key to sign each release." A separate upload key is optional; Google recommends one so it can be
reset if lost, but nothing requires it.

1. **Before the first upload**, in Play Console → Cucuto → **Protected with Play → Play Store distribution → Go to
   Play app signing** (older menus: **Test and release → Setup → App signing**): **Change app signing key** ("Use a
   different key") → **Export and upload a key from Java keystore**. Download the PEPK tool and the encryption key
   shown there, run the command Play Console shows on `~/cucuto-release-key/cucuto-release.jks` (alias `cucuto`),
   and upload the encrypted output. The owner does this on their own machine (#276); the key never leaves it
   unencrypted. Skip the optional upload certificate: the release key stays the upload key.
2. Upload the first bundle (`cucuto-<version>.aab` from a GitHub release) to **Internal testing**.
3. Check on the same Play app signing page: the **app signing key certificate** and the **upload key certificate**
   SHA-256 both equal `RELEASE_CERT_SHA256` (the repository variable, also on every GitHub release and on the
   website's Install page). If either differs, stop and ask on #276.

The order matters: Play enrolls a new app in a Google-generated key on its first upload. The key can still be
changed until a release reaches open testing or production, but installs made from Play with Google's key would then
stop updating.

The release key has every job now, so it stays as precious as before (releasing.md). With one key in both roles,
Google can't reset the upload key on its own if it leaks: the GitHub APKs and Play would both need a new key.

What users see: **Play installs and the APKs from GitHub, Obtainium or F-Droid carry the same certificate, so they
update each other.** Obtainium's certificate pin accepts an app installed from Play, and Play updates an app
installed from GitHub once it has a higher versionCode. Play installs arrive as split APKs (base, arm64, density),
not byte-identical to the reproducible GitHub APK; LSPosed loads the module from the base APK. The module's own check
(ADR 0010) only compares the module with the installed app, and both come from the same install.

Android developer verification: Play Console registers the Play-signed app itself, with the release certificate.
Check that the APKs outside Play are covered too (#276, step A4).

## Store listing

| Field | Value |
| --- | --- |
| App name | `Cucuto` (from `title.txt`) |
| Default language | English (United States) – en-US. Add Spanish (Spain) – es-ES |
| Short description | `short_description.txt` |
| Full description | `full_description.txt` |
| App icon | `images/icon.png` |
| Feature graphic | `images/featureGraphic.png` (per language) |
| Phone screenshots | `images/phoneScreenshots/1-…6-…` in order (per language) |
| Video | None yet |
| App or game | App |
| Category | Personalization (#287) |
| Tags | The closest ones Play offers, for example ringtones or notifications |
| Email | The owner's public contact address (shown on the listing) |
| Website | `https://cucuto.mauri.app` |
| Privacy policy | `https://cucuto.mauri.app/privacy/` |

Trademark rules (ADR 0046): no "WhatsApp" in the name, the icon, the developer name or the feature graphic, and not
in the first words of the short description. The full description says it works with WhatsApp and WhatsApp
Business notifications and ends with "Not affiliated with WhatsApp or Meta." Use a developer name without
"WhatsApp" (for example the owner's name).

## App content

### Privacy policy

`https://cucuto.mauri.app/privacy/` (English) and `https://cucuto.mauri.app/es/privacy/` (Spanish). Both already
cover the app.

### Ads

**No, my app does not contain ads.**

### App access

**All or some functionality in my app is restricted.** Add one set of instructions, without a username or password.

Name: `Root and LSPosed requirement`

Any other information (paste this; under 500 characters):

```text
Cucuto sets a notification sound and vibration per WhatsApp contact. That part is an LSPosed module: it needs Android 17 with root, which a review device lacks. No login. Without root: tap Skip, Get the demo packs on Home, then Try a sample chat: pick a character, tap Play a sample message and hear and feel what a message from that chat would do. Settings > Diagnostics shows what is missing. Video and notes: https://github.com/mauriciabad/cucuto/blob/main/docs/google-play.md#review-notes
```

### Content rating

IARC questionnaire. Category: **All other app types → Utility, Productivity, Communication, or Other**.

| Question | Answer | Why |
| --- | --- | --- |
| Violence, blood, fear | No | Cartoon characters (the demo packs are a chiptune frog, a kettle…) |
| Sexuality, nudity | No | |
| Profanity, crude humor | No | |
| Drugs, alcohol, tobacco | No | |
| Gambling, simulated gambling | No | |
| Users interact or exchange content (chat, sharing) | No | Packs are files the user picks or downloads. Nothing is shared with other users. |
| Shares the user's location with others | No | The approximate location stays on the phone (day and night clips) |
| Digital purchases | No | |
| Unrestricted internet or web browser | No | It only talks to the pack repositories in the user's list |

Expected rating: Everyone / PEGI 3 / USK 0.

### Target audience and content

- Target age: **18 and over** only (#287). Not designed for children. The app needs a rooted phone.
- Appeals to children: **No.**

### Other declarations

| Declaration | Answer |
| --- | --- |
| News app | No |
| Government app | No |
| Financial features | None |
| Health | None |
| Advertising ID | **No.** No library declares `AD_ID` (CI's permission allowlist would catch it) |
| Foreground service, full-screen intent, exact alarms | Not used, so Play asks nothing |

### Sensitive permissions

None of Cucuto's permissions is on Play's restricted list (SMS, Call Log, All files access, `QUERY_ALL_PACKAGES`,
Accessibility, background location, exact alarms and so on). So **no permissions declaration form is needed**. If
Play or a reviewer asks anyway, each one is explained below. The app explains each runtime permission before asking
for it, and everything else keeps working if the user says no. Source of truth: the privacy page.

| Permission | Kind | Used for | Asked when |
| --- | --- | --- | --- |
| `READ_CONTACTS`, `WRITE_CONTACTS` | Runtime (contacts) | Phone ringtones: link a chat to one of the user's contacts and set that contact's ringtone, the same setting the Contacts app changes | Only when the user turns on phone ringtones |
| `READ_PHONE_STATE` | Runtime (phone) | "Random per call" ringtones: notice that a call ended to pick the next one. The number is never read | Only when the user picks that mode |
| `RECORD_AUDIO` | Runtime (microphone) | Recording a clip in the pack creator | Only while recording |
| `ACCESS_COARSE_LOCATION` | Runtime (approximate location, foreground only) | One reading for sunrise and sunset, for day and night clips. Never precise, never in the background | Only after "Use my location once" |
| `POST_NOTIFICATIONS` | Runtime | The test notification in Diagnostics (the self-test) | When the user posts it |
| `INTERNET` | Normal | Pack repositories in the user's list: list, update and download packs | When the user browses or downloads |
| `VIBRATE` | Normal | Previewing a character's vibration and the haptic editor | |
| `RECEIVE_BOOT_COMPLETED` | Normal | Re-schedules the next day-and-night or season switch of phone ringtones after a reboot | |
| `app.mauri.cucuto.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Signature (AndroidX) | Added by AndroidX to protect the app's own receivers | |

## Data safety

Play's definitions: "collected" means sent off the device by the app or its libraries, and "shared" means given to a
third party. Processing that happens only on the device isn't collection. Draft answers:

**Does your app collect or share any of the required user data types?** → **No.**

That's the whole form. Play then shows "No data collected" and "No data shared with third parties". Why it's true,
for review, and the checklist to re-run when something changes:

- **No accounts, no ads, no analytics, no crash reporting, no tracking libraries.** Nothing in the dependency tree
  sends usage data (`android/gradle/libs.versions.toml`).
- **Everything the app knows stays in its private storage.** That covers packs, assignments, settings, the chats
  the module noted (chat name, sender name, WhatsApp's chat ID) and the decision log. Android's cloud backup and
  device-to-device transfer are off for Cucuto.
- **The module never goes online.** It reads only notifications from WhatsApp and WhatsApp Business inside
  `system_server`, never message text, and hands what it notes to the app over a channel that refuses every other
  app.
- **Network:** only the pack repositories in the user's list (this site's by default), to list, update and download
  packs the user chooses. Requests carry no identifiers or cookies. A private repository's token is typed in by the
  user and sent only to that repository. That's the user's own service, not a third party Cucuto shares with.
- **Backups** go only where the user saves them (Storage Access Framework). If the user picks Google Drive, the
  Drive app uploads the file. Cucuto itself uploads nothing.
- **Location** (approximate, one reading) stays on the phone and is never in a backup.
- **Microphone** recordings stay on the phone, inside the pack being made.
- **Contacts** are read and written on the phone only (the ringtone link). Nothing leaves the device.

**Security practices** (shown only when something is collected): not applicable. For the record, all network
traffic is HTTPS only (no cleartext), and there's no account, so there's no deletion request.

**Re-check this form** when any of these ship: the Drive API backup (ADR 0040, step 4: Cucuto would upload backups to
the user's own Drive itself, so it likely becomes "collected", with personal info and contacts, encrypted in transit,
optional, deletable), crash reporting of any kind, a new permission, or a new host the app contacts on its own.

## Review notes

The long form behind the App access text, also useful for an appeal.

**What Cucuto is.** An open-source (GPL-3.0-or-later) app plus an LSPosed module. On rooted Android 17 phones it
gives each WhatsApp contact a different notification sound and vibration, chosen from sound packs the user imports.
It isn't affiliated with WhatsApp or Meta.

**Why it needs root and LSPosed.** Android doesn't let one app change another app's per-conversation notification
sound. The module runs inside Android's system process (`system_server`), where notification sounds are played. When
a WhatsApp notification is about to alert, it swaps the sound and vibration for the one the user assigned. It does
nothing else:

- It never roots the phone and gains no privileges itself. The user installs root and LSPosed, then enables the
  module and picks its scope in LSPosed.
- Scope: System Framework only. It doesn't hook or modify WhatsApp. It doesn't read message content, WhatsApp's files
  or its network traffic.
- Muted chats, Do Not Disturb and silent mode keep working, because Android still decides whether a notification
  alerts. If anything fails, WhatsApp's own tone plays.
- No network access from the module, no data collection, no ads. The source is public:
  `https://github.com/mauriciabad/cucuto` (module code in `android/xposed/`).

**What a reviewer can test without root (about two minutes):**

1. Open Cucuto. The setup explains the requirements; tap **Skip**.
2. Home → **Get the demo packs** (or Packs → **Get free demo packs**). Discover downloads two small packs of CC0
   cartoon characters from `cucuto.mauri.app`.
3. Back on Home, the card "Your characters are ready" → **Try a sample chat**. This is the core feature, as a demo
   (ADR 0075): a made-up chat, clearly labelled **Demo**, that works without the module.
   - Tap a character under **Messages** to give the sample chat that voice (it previews right away).
   - Tap **Play a sample message**: the phone plays one of the character's clips with its vibration, as a WhatsApp
     message from a chat with that voice would.
   - Under **Phone calls**, keep **Same as the messages** (Bleep the frog has a ringtone) or pick another, then
     **Play a sample call** and **Stop**.
   The sample chat posts no notification, isn't sent to the module, isn't in backups and needs no permission. Real
   chats replace it on Home once the module sees their first message.
4. **Hear the characters** on the same card, and Discover: browse the pack repository and preview characters.
5. Settings → **Diagnostics** (and the setup card on Home) shows which check fails: the module in
   `system_server`, its LSPosed scope, WhatsApp, a pack. That's the honest state of a review device.
6. Optional: the haptic editor (make a vibration or tap out a rhythm) and the pack creator (record or import a clip).

With root and the module, the same voice plays for the real chat's WhatsApp notifications, and contact detail's
**Simulate message** sends a test through the module itself (shown in the video).

**Video of the root feature:** `<link from #286>` (Diagnostics self-test, then a chat's voice played through the
module on a rooted Android 17 phone).

**If Play objects:**

- *Device and Network Abuse ("interferes with other apps").* The module changes only the sound and vibration of
  notifications the system is already playing, at the user's explicit request, on a phone the user rooted. It
  doesn't modify WhatsApp, read its data, bypass its features or affect its servers. It works like a per-contact
  ringtone setting that Android doesn't offer. The user enables it in LSPosed and can remove it there.
- *Minimum functionality.* The app is a complete product for its audience (rooted phones), and the requirements are
  in the first lines of the description and in the app's setup. Without root, the app still works as a pack manager
  and previewer, the sample chat shows the core feature end to end in the app, and it says plainly what's missing.
- *Intellectual property.* The name, icon and graphics are original (`brand/`). "WhatsApp" appears only to say which
  notifications the app works with, and the listing says Cucuto isn't affiliated with WhatsApp or Meta.

Answer every rejection with changes or an appeal, never by resubmitting the same build (#276, step E).

### When the optional WhatsApp hook ships (#273)

ADR 0051 and ADR 0052 add an opt-in hook inside WhatsApp's process, for call ringtones only. It's off by default and
needs the user to add WhatsApp to the module's scope. In that PR, change the description's line "It never hooks the
WhatsApp app" and the review notes to say what it does, for example:

> Optional, off by default: for call ringtones, you can let the module change one thing inside WhatsApp, the
> ringtone file its call ringer opens. Nothing else in WhatsApp is touched, and the app explains it before you
> enable it.

The Data safety answer doesn't change: the hook reads only the caller name from WhatsApp's own call notification,
on the device. ADR 0046 names this hook as the most likely reason for a rejection. If Play objects, the options are
an appeal or a Play build without that one feature. That build isn't planned unless it happens.
