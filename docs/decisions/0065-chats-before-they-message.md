# 0065. Chats before they message: Android contacts, not WhatsApp's database

- Status: proposed. The default below waits for the owner on #254, who can override it. Nothing is built yet.
- Date: 2026-10-24
- Issue: #19 (optional root import of WhatsApp chats and groups, M2)

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

## Decision (default; owner can override on #254)

1. **Cucuto reads none of WhatsApp's files.** No root import from `wa.db`, `msgstore.db` or anything else under
   WhatsApp's private storage. libsu is not added for this. Root and LSPosed stay what they are for: the module in
   `system_server` (and, if enabled, the opt-in WhatsApp scope of ADR 0051).
2. **"Add a chat from contacts" replaces the root import** (option A). When it is built:
   - **Entry.** Home's "Assign a voice" screen offers "Add from contacts". Nothing runs until the user taps it, and
     nothing runs in the background.
   - **Explainer screen first** (owner, #166; ADR 0055), in English and Spanish. It covers:
     - what is read: the WhatsApp entries that Android's contacts already hold, each a name and a WhatsApp phone ID;
     - what is never read: messages, chats, groups, WhatsApp's files, and other details of your contacts;
     - that only the people you pick are saved;
     - a link to the code.
     Then the contacts permission flow that phone ringtones already use. If the permission is already granted, there
     is no prompt.
   - **What is read.** One `ContactsContract.Data` query: `MIMETYPE` is WhatsApp's profile type (and WhatsApp
     Business's, `com.whatsapp.w4b`, when present), with the projection `DATA1` (the phone JID), `LOOKUP_KEY` and
     `DISPLAY_NAME`. The list stays in memory while the screen is open.
   - **What is saved.** Only the people the user picks, through the existing tables: a contact named after the Android
     contact, linked to it (`androidLookupKey`), with a `PHONE_JID` key for the messenger and its E.164 number. Then
     the picker opens to choose the voice.
   - **Linking the `@lid`.** When a new `@lid` chat first notifies, the app compares its display name (WhatsApp shows
     the phone's contact name) with the picked contacts that have no `@lid` yet. One exact match (ignoring case and
     surrounding spaces) gets the `@lid` key added, and the routing table republishes. Anything else (no match, or
     several) shows the chat on Home as "Is this {name}?" for a one-tap confirmation. The first message from that
     person plays WhatsApp's tone; the voice plays from the next one.
   - **The module does not change.** It still routes on shortcut IDs only. A name-based route in `system_server` was
     rejected: more code in the most sensitive process, and a namesake would get someone else's voice.
   - **Groups** still appear when they first notify, as today (recent senders).
   - **Fail softly.** No WhatsApp entries (WhatsApp does not sync contacts, the permission is denied, or WhatsApp
     changes its MIME type): the screen says so and points to recent senders. No crash, no retry loop.
3. **If the owner chooses B or C on #254**, a new ADR supersedes this one. It must keep: opt-in and off by default;
   an explainer screen first; a one-shot import the user triggers; read-only access with no copy kept; nothing read
   beyond JID, display name or subject, and last activity; never messages or media; soft failure on any schema
   surprise; tests on a fake schema only; the privacy page updated in the same PR.

## Consequences

- The brief's contact identity paragraph, the libsu row and the M2 checklist now describe this default. The website
  privacy page says Cucuto never opens WhatsApp's databases or files. Its permissions list now matches the manifests
  (contacts, phone state, location and microphone, each asked only when its feature is used).
- #19 stays open until the owner answers #254. The implementation of option A then needs a Home entry point, the
  explainer screen, a `ContactsContract.Data` reader in `:core:ringtone`'s `AndroidContacts` (or a sibling), the
  `@lid` linking in `ChatDirectory`, and Robolectric tests on a fake contacts provider.
- People not saved in the phone's contacts, and groups, can only get a voice after their first notification. The
  recent-senders strip already makes that one tap.
