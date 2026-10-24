# 0017. Home, recent senders and the character picker

- Status: accepted (the owner may override the defaults below, #129); the picker's character list is amended by
  [ADR 0063](0063-independent-voice-and-ringtone.md)
- Date: 2026-10-24
- Issues: #25 (Home and character picker), #18 (recent senders feed with one-tap assignment)

## Context

These two issues close the app's basic loop: someone messages, they appear in "recent senders", one tap opens the
character picker, a second tap assigns, and the next message plays that character's voice. The brief (screens 2 and
3, "Juice checklist") asks for a chat list with sprite avatars, a recent-senders strip, search, a pack filter and a
FAB; a picker grid grouped by pack where a tap previews (bounce, clip, haptic) and a second tap assigns with a
particle burst; and shared-element transitions. ADR 0009 stores contacts, keys, assignments and the `seen_senders`
feed; ADR 0010 fills the feed from the module's decision events (package, conversation key, display name from the
notification, group flag). ADR 0014 provides `CharacterTheme`, `LocalFeedback`, `CookieAvatar`, `ParticleBurst` and
`CuckooDoor`.

## Decision

**A chat is a conversation key, not a row ID.** `ChatKey(packageName, conversationKey)` (`:core:data`, serializable)
is what Navigation 3 keys carry (`Picker(chat)`, `ContactDetail(chat)`) and what the UI assigns to. A conversation the
module has seen but that has no contact yet needs no database row until it gets a voice. Its `toString()` keeps only
the JID domain, so a key that ends up in a log or a test name does not identify anyone.

**`ChatDirectory` joins the three sources.** `observeChats()` merges contacts (with every key), assignments and the
recent-senders feed into `Chat`s, newest sighting first. A contact WhatsApp moved from a phone JID to an `@lid` is
still one chat. `assign(chat, packId, characterId)` does `findOrCreate` on the key (named after the notification) and
then `AssignmentRepository.assign`. The routing publisher already observes those tables, so nothing else is needed for
the module to pick up the change. A group key assigns the whole group, as the owner agreed in #56. `dismissRecent`
deletes the sighting. `CharacterDirectory` reads every installed pack's characters (name, sprite, seed color, clip
files, haptic) through the same cached `PackManifestCatalog` the routing table uses. Pack paths that would leave the
pack folder are dropped.

**Home.** A search field (Material 3 `SearchBarDefaults.InputField`, filtering in place; there is no separate results
page), pack filter chips (shown when there are two or more packs), the "Recent senders" strip, the chat list and an
extended FAB "Assign a voice" that opens a sheet of the chats without a voice. Search ignores case and accents and
matches the chat name, the character name and the pack name. Tapping a chat opens the picker; the contact detail
(#26) takes over that tap later. A long press on a strip item hides it until that chat messages again. Status stays
the start destination (#24 owns onboarding) and links to Home.

**Picker.** A `LazyVerticalGrid` with one section per pack. Amended by [ADR 0063](0063-independent-voice-and-ringtone.md)
(owner update 2026-10-24, #245): it lists only characters with notification clips, with pack filter chips (and counts)
when two or more packs have some, and its empty state links to Packs and Discover. The first tap selects a character and previews it: a
random clip, never the same one twice in a row, plus the character's haptic, and the sprite "speaks" (`CookieAvatar`
morph) while the clip plays. A second tap on the selected character assigns it:
`LocalFeedback.current.fire(Feedback.Assign(id))` returns the beat, the avatar morph and `ParticleBurst` start from
that beat, and the character's own clip is scheduled for `beat.characterClipAtNanos`, the "-cu!" note. The picker
then closes after 900 ms. The selected character re-themes the screen (`CharacterTheme`, from the pack's `seedColor`
or else extracted from the sprite). The chat's avatar is a shared element between the Home row (or strip item) and
the picker header (`SharedTransitionLayout` around `NavDisplay`, `LocalNavAnimatedContentScope` per entry). Without a
chat (`Picker(null)`) the grid only previews.

**Preview audio and haptics.** Clips play through `MediaPlayer` with `USAGE_NOTIFICATION` audio attributes, like
Android's own notification sound picker, so the preview follows the notification volume and the ringer mode. The
haptic is built with `:core:haptics` (`PublicHapticEffects.create`, the module's planner with public APIs only,
[ADR 0026](0026-app-haptics-public-apis-only.md)) and played with
`VibrationAttributes.USAGE_NOTIFICATION`. `Keep`, `Off` and `Auto` play no preview haptic: `Auto` needs the per-clip
envelopes of #97.

**Sprites.** Coil, with `coil-svg` 3.6.3 added next to the JPEG XL decoder, because the demo packs use SVG sprites.
AndroidSVG has no scripting and no network access, and the importer has already rejected unsafe SVG (pack format
"Images").

**Tests.** The view models are tested on the JVM with fakes of `ChatDirectory`, `CharacterDirectory` and
`VoicePreview`. `ChatDirectory` is tested on Room (Robolectric). `HomeContent` and `PickerContent` get Compose UI tests
on Robolectric in `src/testDebug`, because the test activity comes from the debug-only `ui-test-manifest`. They need
Espresso 3.7.0: the 3.5 that Compose's test library pulls in looks for `InputManager.getInstance()`, which Android 17
no longer has.

### Defaults (owner may override, #129)

- The strip shows chats **without a voice** whose last notification is at most **7 days** old, newest first, up to
  **12**. Older ones stay in the list and in the FAB sheet.
- Groups appear in the strip like 1:1 chats; assigning a group gives the whole group that voice.
- The preview follows the notification volume and ringer mode (it is silent in silent mode).
- The picker closes by itself 900 ms after assigning.

## Consequences

- The basic loop works end to end once a pack is imported on the Packs screen (#27, ADR 0016), which Home and the
  picker link to when no pack is installed.
- `Chat` is a read model. The contact detail (#26) and per-sender group policy (#20) can reuse `ChatDirectory`.
- Shared elements need a unique key per entry. A chat shown both in the strip and in the list shares only its strip
  avatar.
