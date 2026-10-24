# Architecture decisions

Short ADRs (context, decision, consequences), one per file, numbered in order. The [project brief](../project-brief.md)
is the spec; these record how and why Cucuto was built the way it is. Owner answers that changed a decision are in
[`owner-log.md`](../owner-log.md) and the linked `question` issues.

**Adding one:** take the next number after the highest one here *and* in open PRs (`gh pr list`, then
`gh pr diff <n> --name-only`), and add a row to this table in the same PR. Amend older ADRs in place (a dated
"Amended" note) rather than contradicting them silently. Missing numbers (0035, 0049, 0059, 0066–0068, 0070) were
handed out to work that never merged or is still in a PR (0049 is in #225, 0059 in #258). Don't reuse them.

**Read first** when you touch the module or the routing path: [0002](0002-xposed-scope-system.md),
[0009](0009-data-layer-and-routing-table.md), [0010](0010-production-hook-and-module-channels.md),
[0024](0024-routing-updates-after-app-update.md), [0043](0043-framework-service-lost-after-app-update.md),
[0048](0048-routing-table-pull-without-the-service.md), [0054](0054-routing-table-size-headroom.md) and
[0051](0051-optional-whatsapp-scope.md).

| ADR | Decision | Status |
| --- | --- | --- |
| [0001](0001-android-build-and-versions.md) | Android build setup and versions | accepted |
| [0002](0002-xposed-scope-system.md) | The module's scope is `system` (system_server), not `android` | accepted |
| [0003](0003-brand-assets-by-reference.md) | The app compiles brand tokens and the icon straight from `brand/` | accepted |
| [0004](0004-dependabot-for-dependency-updates.md) | Dependabot for dependency updates | accepted |
| [0005](0005-shared-pack-spec-module.md) | One Kotlin Multiplatform pack-spec module for packer and the app | accepted |
| [0006](0006-jpeg-xl-decoding.md) | JPEG XL decoding on Android: jxl-rs behind a small JNI layer | accepted |
| [0007](0007-zip-file-names.md) | File names: packs end in `.soundpack.zip`, backups in `.zip` | accepted |
| [0008](0008-pack-importer.md) | Pack importer: copy, validate, stage, decode, rename | accepted |
| [0009](0009-data-layer-and-routing-table.md) | Data layer and the routing table published to the module | accepted |
| [0010](0010-production-hook-and-module-channels.md) | Production hook, clip provider and the module → app event channel | accepted |
| [0011](0011-repository-client-and-credentials.md) | Repository client: manual redirects, origin-scoped credentials, Keystore AES-GCM | accepted |
| [0012](0012-haptics-effects-and-auto-haptics.md) | Haptic effects, fallbacks and auto haptics | accepted |
| [0013](0013-backup-and-restore.md) | Backup and restore: Bouncy Castle crypto, re-zipped full backups, ask-first restore | accepted |
| [0014](0014-theme-and-feedback-engine.md) | Theme, character colors and the Feedback engine | accepted |
| [0015](0015-status-checks-and-onboarding.md) | Status checks and first-run onboarding | accepted |
| [0016](0016-packs-screen.md) | Packs screen: import flow, errors in plain words and removal | accepted |
| [0017](0017-home-recent-senders-and-picker.md) | Home, recent senders and the character picker | accepted |
| [0018](0018-clip-mirror-for-instant-sounds.md) | Clip mirror: SystemUI plays clips without starting Cucuto | accepted; re-checked and kept by 0062 |
| [0019](0019-long-notification-clips.md) | Long notification clips (up to 30 s) | accepted |
| [0020](0020-auto-haptics-at-import.md) | Auto haptics at import and clip envelopes in the routing table | accepted |
| [0021](0021-signed-release-apk.md) | Signed release APK on tag | accepted |
| [0022](0022-pack-spec-in-the-browser.md) | pack-spec in the browser: Kotlin/JS for the website's pack checks | accepted |
| [0023](0023-call-ringtones-native-first.md) | Call ringtones: native first, Telecom hooks only as a fallback, WhatsApp calls guided | accepted for the static phase; device run #143 pending |
| [0024](0024-routing-updates-after-app-update.md) | Routing table updates after an app update: remote file and nudge | accepted |
| [0025](0025-contact-detail-and-simulate-message.md) | Contact detail and "Simulate message" | accepted |
| [0026](0026-app-haptics-public-apis-only.md) | App-process haptics use public APIs only | accepted |
| [0027](0027-app-shell-and-first-run-journey.md) | App shell: four tabs, per-tab back stacks and the first-run journey | accepted |
| [0028](0028-settings-and-backup-ui.md) | Settings, backup and restore UI | accepted |
| [0029](0029-discover-screen.md) | Discover screen: browsing repositories, previews, installs and adding repositories | accepted |
| [0030](0030-haptic-editor-and-per-contact-override.md) | Haptic editor, tap the rhythm and per-contact haptic override | accepted |
| [0031](0031-group-policy-and-whatsapp-business.md) | Group policy and WhatsApp Business | accepted; device check #157 pending |
| [0032](0032-screenshot-tests.md) | Screenshot tests and website screenshots with Roborazzi | accepted |
| [0033](0033-ring-files-and-auto-cut-clips.md) | Ring files and auto-cut clips: rendered by tools, pack format 1 unchanged | accepted |
| [0034](0034-in-app-pack-creator.md) | In-app pack creator | accepted |
| [0036](0036-app-languages.md) | App languages: English and Spanish, kept complete by the build | accepted |
| [0037](0037-day-and-night-clips.md) | Day and night clips from sunrise and sunset | accepted |
| [0038](0038-web-pack-creator.md) | The website's pack creator: shared Kotlin for layout and clips, browser APIs for media | accepted |
| [0039](0039-phone-ringtones-on-the-device.md) | Phone ringtones: ring files rendered on the phone, MediaStore, contact undo record, re-roll receiver | accepted |
| [0040](0040-google-drive-backups-and-pack-folders.md) | Google Drive: one SAF file for automatic backups now, the Drive API behind an interface | accepted; Drive sign-in waits for the owner (#188) |
| [0041](0041-accessibility-pass.md) | Accessibility pass | accepted |
| [0042](0042-explainer-video-and-slot.md) | Explainer video: brief, files and the Home player | accepted; amended by the owner on #194 (rework #271) |
| [0043](0043-framework-service-lost-after-app-update.md) | Framework service lost after an app update: tell the user to reboot | accepted |
| [0044](0044-vibrator-capabilities-at-boot.md) | Vibrator capabilities read at boot, off the notification path | accepted |
| [0045](0045-demo-packs-in-one-tap.md) | The demo packs in one tap, from Home through Discover | accepted |
| [0046](0046-distribution.md) | Distribution: store metadata, release notes, Obtainium and reproducible builds | accepted |
| [0047](0047-routing-integration-suite.md) | Routing integration suite | accepted |
| [0048](0048-routing-table-pull-without-the-service.md) | Routing table pull from the app's provider when the framework service is missing | accepted |
| [0050](0050-shell-follow-ups.md) | Shell follow-ups: the door on the status card, the Settings badge, restore on first run | accepted |
| [0051](0051-optional-whatsapp-scope.md) | An optional, opt-in WhatsApp scope, only for features that need it | accepted |
| [0052](0052-whatsapp-call-ringtones.md) | WhatsApp call ringtones: swap the URI at `RingtoneManager.getRingtone`, caller by notification name | accepted; device run #228 pending, build #273 |
| [0053](0053-ai-written-haptic-patterns.md) | AI-written haptic patterns: Copy for AI and Paste pattern | accepted |
| [0054](0054-routing-table-size-headroom.md) | Routing table size headroom: the full table through the file, a reduced copy in Remote Preferences | accepted |
| [0055](0055-screens-over-dialogs.md) | Screens over dialogs: flows and long content are destinations | accepted |
| [0056](0056-baseline-profile.md) | Baseline Profile | accepted |
| [0057](0057-ringtone-picker-and-ringtone-backups.md) | The ringtone picker, and phone ringtones in backups | accepted |
| [0058](0058-create-page-tools.md) | Create page tools: drafts, editing packs, covers, custom haptics, bundles and the repository helper | accepted |
| [0060](0060-explainer-video-draft-pipeline.md) | Explainer video: an automated draft from the app's real screens | to be reworked (owner, #194; #271) |
| [0061](0061-fast-import-checks.md) | Fast import checks for large packs | accepted |
| [0062](0062-hook-vs-clip-mirror.md) | Instant sounds: no hook beats the clip mirror, so it stays | accepted |
| [0063](0063-independent-voice-and-ringtone.md) | Message voice and ringtone are independent choices; ringtone previews ring like the call | accepted |
| [0064](0064-bundles-in-discover.md) | Bundles in Discover: one card with its packs inside | accepted |
| [0065](0065-chats-before-they-message.md) | Chats before they message: Android contacts, not WhatsApp's database | accepted by the owner on #254 (2026-10-24); built in #292 |
| [0069](0069-haptic-previews-in-discover.md) | Haptic previews in Discover: the pack's haptic in the index, auto envelopes on the phone | accepted; amended for drawn previews (#301) |
| [0071](0071-google-play-bundle-and-listing.md) | Google Play: app bundle, signing, listing and review notes | accepted; owner updates #287 (Play signs with the release key via PEPK, release key uploads) |
| [0072](0072-picker-folding-and-surprise-me.md) | Folded pack sections and "Surprise me" in the character pickers | accepted; defaults open in #297 |
| [0073](0073-sound-flow-message-then-ringtone.md) | The sound flow: the message sound, then the ringtone, with "Android default" and "Leave as is" | accepted; owner update #298, defaults open in #282 |
| [0074](0074-ringtone-rules-screen.md) | Ringtone rules: the pack's rules in plain words, and your own rules per contact | accepted; defaults open in #303 |
