# 0075. A sample chat to try a voice without root

- Status: accepted, amended 2026-10-24 (#346: contact detail's layout and the real pickers; "Hear the characters"
  removed). The defaults the owner may override are in #308.
- Date: 2026-10-24
- Issues: #285 (this work), #346 (the amendment), #274 and ADR 0071 (Google Play review notes), ADR 0025 (Simulate message), ADR 0063
  (independent voice and ringtone)

## Context

Google Play reviewers have no root and no LSPosed (ADR 0071, point 7). Home learns about a chat only when the module
reports one of its WhatsApp notifications (ADR 0010, ADR 0017), so on a review phone there's no chat to give a voice
to, and contact detail with its **Simulate message** is out of reach. A reviewer can preview characters, but can't
see what Cucuto is for: a chat that sounds and vibrates like its own character. Play can refuse an app it can't
evaluate (Minimum Functionality, App access). The same gap exists for anyone who opens the app before setting up
the module.

#285 proposed a local chat with a reserved key (`sample@cucuto.local`) in the contacts table, skipped by the routing
table builder, left out of backups and removed when the first real chat arrives. That works, but every place that
reads contacts (the routing table, backups and restore, phone ringtones and their Android contact link, the key
merge, Home, Assign a voice, the decision log) would need to know about the special key, and a single missed check
would put a made-up chat into `system_server` or a backup. Minimum intrusion (#67) asks for the design where that
can't happen.

## Decision

**1. The sample chat isn't a chat in the database.** It's its own small screen, **Sample chat**, with its two choices
(the message character and the ringtone character) stored as four keys in the app's settings DataStore
(`SampleChatSettings` in `:core:data`). Nothing is written to the contacts, keys, assignments or phone ringtone tables. So by
construction:

- the routing table, which is built only from those tables (`ContactDao.observeRoutingRows`, `RoutingTableSource`),
  never sees it, and the module and Remote Preferences get nothing new;
- backups, which list their fields one by one, don't include it, and restore can't bring it back;
- no Android contact is linked or written and no system ringtone is set, so no contacts permission is involved;
- Home's chat list, Assign a voice, the decision log and Diagnostics stay about real chats only.

`SampleChatIsolationTest` pins this: the compiled `sample` package may not refer to the chat directory, the contact,
assignment or phone ringtone stores, the routing pipeline, Simulate message, notifications or Android contacts.

**2. It plays in the app, with public APIs only, and posts nothing.** **Play a sample message** plays one of the
character's clips and its vibration through `VoicePreview`, the same path as the character picker's preview:
`MediaPlayer` with notification audio attributes (so it follows the notification volume and ringer mode) and
`PublicHapticEffects` with notification vibration attributes. **Play a sample call** rings the ringtone through
`RingtonePreview`, the same path as the ringtone picker: ringtone audio attributes, transient audio focus, the
intro then the loop, until **Stop**. The ringtone variant is the pack's own rule for the current time
(`VariantRules.followPack`). No notification is posted, so nothing can look like a WhatsApp message or call, and the
module isn't involved even when it's active. No new permission.

**3. It says it's a demo.** The screen is titled **Sample chat** ("Chat de ejemplo"), wears a **Demo** label, and a
banner says that it isn't a WhatsApp chat, that the sound plays in the app and nothing reaches WhatsApp or the
module, and that real chats appear on Home after the module sees their first message.

**4. Where it's offered.** Home's **"Your characters are ready"** card (packs installed, no chat yet, ADR 0027's
first-run journey) gets **Try a sample chat** as its main action. Amended (#346): **Hear the characters** (the
character picker in a preview-only mode) is gone, so the sample chat is the one way to try characters before a real
chat arrives, and the character picker always gives a voice to something. That card goes
away as soon as the module reports the first real chat, so the entry does too, and there's nothing to remove: no
contact was ever created. Without packs, the sample chat offers **Get the demo packs**. Default, not in #285's
first draft: no entry in Diagnostics, which is about the module, not about trying a voice.

**5. Choices, with the real pickers (amended, #346).** The first version had its own small picker: a grid of
characters under "Messages" and chips under "Phone calls", without folding, search, "Surprise me", album covers or
"Android default". A reviewer learnt one flow and real users another. Now the sample chat looks like contact detail
(ADR 0063): a header with the chat's avatar (the ringtone's cover tucked against it), the **Demo** label and the
banner, then the **Sounds** card with its **Messages** and **Phone calls** rows, built from the same composables
(`SoundRow`, `RecordCover`, `RingtoneBadge`). Below it, a "Try it" card holds **Play a sample message** and **Play a
sample call** where contact detail has Simulate message.

Each row opens the real picker, pointed at the sample chat: `CucutoDestination.Picker` and
`CucutoDestination.RingtonePicker` with no chat key (`chat = null`) mean the sample chat. Their view models then read
and write only `SampleChatStore` (through `SampleChatPickerTarget` in the `sample` package), never the chat directory
or the phone ringtone store, so the guarantees of point 1 hold, and `SampleChatIsolationTest` keeps checking the
`sample` package. The pickers work as for a real chat: a first tap previews, a second tap (or "Use") gives the voice,
with the burst and the cuckoo call; the ringtone picker offers the covers, search, folding, "Surprise me" and
**Android default**. What the sample chat can't use is left out there: "Leave as is" (no Android contact to leave
alone), the rule chips and the rules screen (ADR 0074; the sample call follows the pack's rule, point 2), removing
the voice, and step 2 after the voice (each row is chosen apart). The ringtone still defaults to **Same as the voice**
when the message character has ringtones (ADR 0063's default). With **Android default**, the sample call can't ring
the phone's own ringtone (that would need its URI, outside the pack previews), so the button is off and says so.

`SampleChatSettings` gains one key, `sample_chat_ringtone_default`, for that choice. The choices stay for the next
visit; that's all the sample chat stores.

## Consequences

- A reviewer without root can go from install to hearing and feeling a character for a chat in under two minutes:
  Skip, Get the demo packs, Try a sample chat, Messages, pick a character (tap, then tap again), Play a sample
  message. The review notes in
  [google-play.md](../google-play.md#review-notes) and the App access text describe that path.
- The sample chat is a preview, not Simulate message: it doesn't run the router, so it ignores day-and-night clip
  rules and per-contact clip toggles and haptic overrides, which it doesn't offer. Simulate message (ADR 0025) stays
  the faithful test for real chats.
- Nothing about the routing pipeline, backups, the module or permissions changes, so the privacy page and the Data
  safety answers stay as they are.
- If a later feature wants the sample chat to behave more like a real one (clip toggles, a haptic override), it keeps
  those settings in `SampleChatSettings` too, never in the contacts tables.
