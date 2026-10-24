# 0065. Chats before they message: Android contacts, not WhatsApp's database

- Status: accepted (owner update 2026-10-24, #254: "contacts"). Built in #292; the design details below were filled
  in there.
- Date: 2026-10-24
- Issues: #19 (optional root import of WhatsApp chats and groups, M2), #254 (the owner's answer), #292 (the build)

## Context

Home learns about a chat only when its notification passes through the module (ADR 0010, ADR 0017). A contact who
has not written since Cucuto was installed cannot get a voice yet. The kickoff brief planned to fix this with root:
"With root, the app can optionally read WhatsApp's local contact database to list all chats and groups with their
JIDs" (contact identity, M2, the libsu row in the stack table).

Since then, the owner set two principles that this plan collides with:

- #67, minimum intrusion: "I want it to work with the minimum intrusion possible, so we lower the risk of critical
  vulnerabilities/mistakes." When two designs work, pick the less intrusive one (`CLAUDE.md`).
- #145: "My main concern is that users may think the app could spy them or something." ADR 0051 allows a WhatsApp
  scope only where nothing else works, behind an explainer, reading nothing of messages. The ringtone spike already
  rejected editing WhatsApp's database as too intrusive ([report](../whatsapp-ringtone-spike.md), "Alternatives we
  rejected").

The facts that decide the design:

1. **Routing keys.** The module routes on the alerting notification's shortcut ID: `…@lid` for 1:1 chats, `…@g.us`
   for groups. Phone JIDs (`…@s.whatsapp.net`) never appeared (M0 device run, ADR 0009). An imported chat only plays
   its voice if Cucuto knows its `@lid` or `@g.us`.
2. **Android contacts** (checked on the owner's phone on 2026-10-24 with aggregate counts only; no name or number was
   read or printed): WhatsApp's own sync adapter keeps 1,144 raw contacts of account type `com.whatsapp`. Each has a
   `vnd.android.cursor.item/vnd.com.whatsapp.profile` data row whose `DATA1` is a phone JID. None is an `@lid`, and
   groups are not there. Cucuto already declares `READ_CONTACTS` for phone ringtones (ADR 0039), so reading these
   rows needs no new permission.
3. **WhatsApp's database.** A person's `@lid` and the group list exist only in WhatsApp's private storage
   (`/data/data/com.whatsapp/databases`). Public forensics write-ups put contacts and group subjects in `wa.db`, and
   the chat list, the JID table and the `@lid`-to-phone map in `msgstore.db`, next to every message. The schema is
   undocumented and changes with WhatsApp updates. Agents may not open those files on the owner's phone, so the
   schema cannot be verified. Opening another app's live SQLite database (WAL mode) as root can leave root-owned
   `-shm` or `-wal` files, or hold locks, that break WhatsApp unless it is opened strictly read-only and immutable,
   or copied first, which means copying the message database.

## Options compared

| | A. Android contacts | B. A, plus root read of `wa.db` for groups | C. Root import from `wa.db` and `msgstore.db` |
| --- | --- | --- | --- |
| Privileges | `READ_CONTACTS` (already declared) | Root, WhatsApp's private files | Root, WhatsApp's message database |
| 1:1 chats | WhatsApp contacts saved on the phone | Same as A | All, unsaved numbers too |
| Groups | Only after their first notification | Yes | Yes |
| First message plays the voice | No: 1:1 chats link their `@lid` on the first notification, and play their voice from the next one | Groups yes, 1:1 as A | Yes |
| Breaks with a WhatsApp update | No, public Android API | Possibly; fails softly | Possibly; fails softly |
| What users may fear | Same as phone ringtones today | "It reads WhatsApp's files" | "It opens my messages file" |

## Decision (owner update 2026-10-24, #254: "contacts")

1. **Cucuto reads none of WhatsApp's files.** No root import from `wa.db`, `msgstore.db` or anything else under
   WhatsApp's private storage. libsu is not added for this. Root and LSPosed stay what they are for: the module in
   `system_server` (and, if enabled, the opt-in WhatsApp scope of ADR 0051).
2. **"Add a chat from contacts" replaces the root import** (option A). When it is built:
   - **Entry.** "Add a chat from contacts" on Home and in the "Who gets a voice?" chat picker (details below).
     Nothing runs until the user taps it, and nothing runs in the background.
   - **Explainer screen first** (owner, #166; ADR 0055), in English and Spanish. It covers:
     - what is read: the WhatsApp entries that Android's contacts already hold, each a name and a WhatsApp phone ID;
     - what is never read: messages, chats, groups, WhatsApp's files, and other details of your contacts;
     - that only the people you pick are saved;
     - a link to the code.
     Then Android's prompt for `READ_CONTACTS` alone (reading is all this needs). If the permission is already
     granted, there is no prompt.
   - **What is read.** One `ContactsContract.Data` query: `MIMETYPE` is WhatsApp's profile type (and WhatsApp
     Business's, `com.whatsapp.w4b`, when present), with the projection `DATA1` (the phone JID), `LOOKUP_KEY` and
     `DISPLAY_NAME`. The list stays in memory while the screen is open.
   - **What is saved.** Only the people the user picks, through the existing tables: a contact named after the Android
     contact, linked to it (`androidLookupKey`), with a `PHONE_JID` key for the messenger and its E.164 number. Then
     its contact detail opens, with the Messages and Phone calls rows (ADR 0063).
   - **Linking the `@lid`.** When a new `@lid` chat first notifies, the app compares its display name (WhatsApp shows
     the phone's contact name) with the picked contacts that have no `@lid` yet. Only an exact, unique match gets the
     `@lid` key added, and the routing table republishes. Anything else asks the user on Home ("Which {name} is
     this?"). The first message from that person plays WhatsApp's tone; the voice plays from the next one. The exact
     rules, and what counts as unique, are in "Design details" below.
   - **The module does not change.** It still routes on shortcut IDs only. A name-based route in `system_server` was
     rejected: more code in the most sensitive process, and a namesake would get someone else's voice.
   - **Groups** still appear when they first notify, as today (recent senders).
   - **Fail softly.** No WhatsApp entries (WhatsApp does not sync contacts, the permission is denied, or WhatsApp
     changes its MIME type): the screen says so and points to recent senders. No crash, no retry loop.
3. **If the owner ever asks for B or C**, a new ADR supersedes this one. It must keep: opt-in and off by default;
   an explainer screen first; a one-shot import the user triggers; read-only access with no copy kept; nothing read
   beyond JID, display name or subject, and last activity; never messages or media; soft failure on any schema
   surprise; tests on a fake schema only; the privacy page updated in the same PR.

## Design details (#292)

Filled in when it was built. These are defaults the owner may override on #309.

### Entry points

"Add a chat from contacts" appears where someone looks for a person who is not there yet:

- **Home, with packs and no chats:** the journey card "Your characters are ready" offers it below "Try a sample
  chat" (ADR 0075; "Hear the characters" was removed in #346), and the plain empty state (shown once the journey is over) has it too.
- **"Who gets a voice?"**, the chat picker behind Home's "Assign a voice" button: a row at the top of the list, and
  a button in its empty state.

The character and ringtone pickers stay as they are: they open for a chat that already exists.

### The flow: one screen, three steps

`AddFromContacts` is one pushed destination with three steps:

1. **The explainer, only while `READ_CONTACTS` is missing** (owner update 2026-10-24, #337: "add from contact shoudl
   not show the warning every time, just the first time"). It has "Continue" and a link to the code. The rule is
   the permission itself, not a "seen it" flag:
   - **Granted** (here, through phone ringtones, or in Android's settings): no explainer. The screen opens on the
     list and reads the entries at once.
   - **Missing**, because it was never granted or was revoked: the explainer, every time, so nothing is read without
     the user first seeing what is read. Revoking is the user saying no again, so they see why it is asked again.
   - **Blocked**: Android no longer shows its prompt ("don't ask again", or a second refusal). An app can't tell
     this before asking, so after a refusal Cucuto checks `shouldShowRequestPermissionRationale`: false right after
     a refusal means blocked. It remembers that in a settings key that is never backed up (a permission belongs to
     the phone), and clears it once the permission is granted. While blocked, the explainer adds a card saying
     Android won't ask again and where the permission is in Android's settings, and its button becomes "Open
     settings".
   - **Coming back** to the screen (from Android's settings, say) with the permission granted meanwhile: the
     explainer, or the refusal step, gives way to the list.
2. **Android's prompt** for `READ_CONTACTS` only, and only when it is missing. Phone ringtones still ask for both
   contacts permissions when they are turned on (ADR 0039). A refusal shows a step that says nothing was read and
   offers Android's app settings, and "Try again" unless Android has blocked the prompt.

   The other contacts explainers already follow the same rule: phone ringtones (`READ_CONTACTS` and
   `WRITE_CONTACTS`, ADR 0039) and the restore summary's "Find contacts" (ADR 0028) show their explainer dialog only
   when a permission is missing, and go straight on when it is granted. They stay as they are; when Android has
   blocked the prompt, their refusal dialog already offers Android's settings.
3. **The list**: every Android contact with a WhatsApp entry, sorted by name, with a search field (case and accents
   ignored) and a check box per person. One row per Android contact and app: a contact with WhatsApp and WhatsApp
   Business entries has two rows, the second marked "WhatsApp Business". Each row shows the WhatsApp number, so two
   people with the same name can be told apart. People already in Cucuto (same WhatsApp number, or the same Android
   contact already linked for that app) are shown checked and disabled, as "Already in your chats". The button at
   the bottom says how many will be added.

**What is read.** One `ContactsContract.Data` query. The selection is `MIMETYPE IN (?, ?)` with WhatsApp's profile type
(`vnd.android.cursor.item/vnd.com.whatsapp.profile`) and WhatsApp Business's
(`vnd.android.cursor.item/vnd.com.whatsapp.w4b.profile`). The projection is `MIMETYPE`, `DATA1` (the phone JID),
`LOOKUP_KEY` and `DISPLAY_NAME`. The MIME type says which app the entry belongs to. `Data` has no `ACCOUNT_TYPE` column
on the owner's phone (checked with a column error, no rows printed), and `DATA1` held a phone JID in all 1,144 rows
(a count). The Business type follows WhatsApp's naming; it is not on the owner's phone, so it is untested there (#228
covers Business on the device). Rows whose `DATA1` is not a phone JID are skipped.

**After saving.** One person: their contact detail opens (Messages and Phone calls rows, ADR 0063). Several: back to
where the flow started, where they show up.

### Chats waiting for their first message

**No schema change.** A chat added from contacts is a contact whose only messenger keys are phone JIDs
(`…@s.whatsapp.net`). Phone JIDs never reach the module (ADR 0009), so any such 1:1 contact is waiting for its `@lid`.
`Chat.awaitingLink` says so. The same rule covers chats restored from a backup. Their keys, numbers and Android link are
already in the backup format, so a restore brings them back still waiting, and the `@lid` is linked on the new phone.

**On Home** they get their own section, "Waiting for a first message", above "Chats", with one line of explanation:
the first message plays WhatsApp's tone, the voice plays from the next one, and phone ringtones work right away. A
newly added chat would otherwise sort last, after every chat that has messaged. A tap opens the contact detail, which
repeats the explanation in a card. They are also in "Who gets a voice?" while they have no voice, marked "Not linked
yet".

**The phone ringtone works at once.** The contact is linked to its Android contact when it is saved, so choosing a
ringtone on the Phone calls row writes `CUSTOM_RINGTONE` as for any linked contact (ADR 0039, #213), once phone
ringtones are on.

### Linking rules and name collisions

A **name** is compared after Unicode NFC normalization, trimming and lowercasing (`Locale.ROOT`). Nothing else is
folded: accents and inner spaces count. A **sighting** is a recent sender of WhatsApp or WhatsApp Business that is not
a group and whose key belongs to no contact. A **waiting chat** is as above. Both sides are compared within one
messenger package only.

A sighting is linked to a waiting chat automatically only when all of these hold:

1. their names are equal;
2. no other waiting chat of that app has that name;
3. no other sighting of that app has that name;
4. **no namesake in Android's contacts:** every WhatsApp entry of that app with that name belongs to the waiting
   chat's own Android contact, or to someone whose chat is already linked (a sender with no chat cannot be them). This
   catches a second "Ana" in the phone's contacts that the user did not add: she would show up with the same name, and
   rule 2 alone would give her the first Ana's voice.

Rule 4 reads the same WhatsApp entries as the list (lookup key and name, in memory only). It runs only when rules 1 to
3 already hold, so only after a sighting matches a waiting chat. Without the contacts permission, or for a waiting chat
with no Android link, it cannot hold, so Cucuto asks.

Anything else with at least one waiting chat of the same name becomes a **question on Home**: a section "Who is this?"
above the recent senders. Each row opens a confirm screen (ADR 0055):

- The screen says that more than one person has that name, so Cucuto will not guess. It suggests checking the number
  in WhatsApp.
- It lists the candidates with their numbers, and any voice they already have.
- A tap links the sighting to that person.
- "None of them" hides the sighting, as "Hide" does in the strip, until it messages again.

A sighting with no waiting chat of the same name is a plain recent sender, as before. The pending chat stays waiting.

**When linking runs:**

- after each alert from a 1:1 chat that belongs to no contact (in the app process, as the sighting is stored);
- right after saving from contacts, so someone who already messaged is linked at once (from the list in memory);
- after each confirmation, because one answer can make another match unique.

**Saving someone who already has a chat.** Someone may already have a chat in Cucuto, made from a recent sender, with no
Android link. When an added person's name meets rules 1 to 4 against such a chat, the Android link and the phone JIDs
go to that chat instead of a new one, so Home never shows the same person twice.

**Not covered:** a chat linked by name is not marked afterwards, and there is no "unlink" yet. A wrong link would need
an unsaved number whose WhatsApp label is exactly a saved contact's name. If that turns out to happen, an "unlink"
action on the contact detail is the follow-up.

## Consequences

- The brief's contact identity paragraph, the libsu row and the M2 checklist describe this decision (owner update
  2026-10-24, #254). The website privacy page says Cucuto never opens WhatsApp's databases or files. Its permissions
  list matches the manifests (contacts, phone state, location and microphone, each asked only when its feature is
  used).
- Built in #292:
  - the entry points and the `AddFromContacts` screen;
  - a `ContactsContract.Data` reader next to `AndroidContacts` (`ResolverWhatsAppContacts` in `:core:ringtone`,
    behind `WhatsAppContactsSource` in `:core:data`);
  - the rules (`ChatLinkRules`) and `ChatLinker` in `:core:data`, called from `AlertSightings`;
  - the questions on Home with their confirm screen.

  The tests cover the query against a made-up contacts provider (Robolectric), the linking rules with every collision
  case, the view models, and a backup round trip of a waiting chat. The root import of #19 is not built; the PR only refers to that issue.
- The website privacy page lists the second use of the contacts permission and what it reads.
- People not saved in the phone's contacts, and groups, can only get a voice after their first notification. The
  recent-senders strip already makes that one tap.
