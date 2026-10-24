# 0081. Signal and Molly: one voice per person across WhatsApp and Signal

- Status: accepted (owner, 2026-10-24, #374: "if i set a voice to a contact it is for both whatsapp and signal at the
  same time"). Amends the brief's "Target apps" row, [ADR 0010](0010-production-hook-and-module-channels.md) (the hook and contact keys) and
  [ADR 0065](0065-chats-before-they-message.md) (Add from contacts).
- Date: 2026-10-24
- Issue: #374

## Context

Cucuto only gave voices to WhatsApp and WhatsApp Business. F-Droid's `NonFreeDep` anti-feature applies because of that
(WhatsApp isn't free software). Signal is free software, and so is Molly, the Signal fork many F-Droid users run. The
owner asked for Signal with one rule: a voice set for a person plays on both WhatsApp and Signal.

What a Signal message notification carries when it reaches `NotificationAttentionHelper` in `system_server` (read in
Signal-Android's `main` on 2026-10-24: `NotificationFactory.notifyForConversation`, `NotificationBuilder`,
`ConversationUtil`, `NotificationChannels`, `NotificationItem.getPersonUri`, `SystemContactsRepository`; Molly's copies
of these files are the same):

| | Signal | WhatsApp, for comparison |
| --- | --- | --- |
| Packages | `org.thoughtcrime.securesms`; Molly `im.molly.app` (Molly-FOSS was merged into Molly) | `com.whatsapp`, `com.whatsapp.w4b` |
| Record that alerts | The conversation's own notification (`GROUP_ALERT_CHILDREN`) | The group summary |
| Shortcut ID | The conversation's Signal recipient ID, a small local number (`RecipientId.serialize()`). Set only while Signal's notification privacy shows the contact and Signal's own screen lock is open | `…@lid`, `…@g.us` |
| `EXTRA_PEOPLE_LIST` | One `Person` for the conversation (`ConversationUtil.buildPerson`): key = recipient ID, `uri` = the Android contact's lookup URI `content://com.android.contacts/contacts/lookup/<lookupKey>/<contactId>` when the person is a saved contact and Signal may read contacts; no `uri` for groups and unsaved numbers. Absent when Signal's privacy hides the contact | Not used |
| Groups | `MessagingStyle.isGroupConversation`; each message's sender `Person` has the same `uri` rule | Channel `group_chat_defaults_*` and `@g.us` |
| Channel | `messages_<n>`, or `contact_<recipientId>_<time>` when the user set a custom sound in Signal. Says nothing about groups | `individual_chat_defaults_*` / `group_chat_defaults_*` |
| Android contacts | Signal's own sync writes raw contacts with the MIME type `vnd.android.cursor.item/vnd.org.thoughtcrime.securesms.contact` and the number in `DATA1`. Molly uses the same MIME type | `vnd.com.whatsapp.profile`, phone JID in `DATA1` |
| Calls | Signal rings itself, with its own ringtone setting. The Android contact's ringtone doesn't apply | WhatsApp rings itself too (ADR 0052) |

The key fact: on Signal, the alerting notification already names the **Android contact** of a 1:1 conversation. On
WhatsApp, Cucuto has to learn a chat's `@lid` first.

## Decision

### The module (still `system_server` only)

1. **Signal and Molly join the messenger list** (`Targets.MESSENGERS`). No new scope and no hook in Signal's process:
   the scope stays `system` (ADR 0002, ADR 0051), and the hooks don't change.
2. **What is read, on Signal and Molly only.** Everything already read on WhatsApp (shortcut ID, channel, the
   group-conversation flag, titles), plus:
   - 1:1 conversations: the `uri` of the first `Person` in `EXTRA_PEOPLE_LIST`. Messages are never opened.
   - Groups: the `uri` of the newest message's sender `Person`, which is already read for its key (#20). Message text
     is never read.

   Only a URI under `content://com.android.contacts/contacts/lookup/` is used. Its lookup key segment is kept
   exactly as written (not decoded), up to the usual 256 characters. Any other URI is ignored.
3. **Keys, strongest first.** A 1:1 conversation from a saved contact uses `contact:<lookupKey>`, then the shortcut
   ID (the recipient ID). Without a contact URI (an unsaved number, Signal without the contacts permission), only the
   recipient ID is used. Groups use the group's recipient ID. In a per-sender group, the sender key is
   `contact:<lookupKey>` when the sender is a saved contact, otherwise the sender's `Person` key. "Is a group" comes
   from the MessagingStyle flag, since Signal's channel says nothing about it.
4. **Lookups stay O(1) and allocation-free.** Routes keep their `(package, key)` hash map. The `contact:` key is one
   string built while the record is read, in the same pass as the existing reads. The routing format doesn't change:
   a route's package is already a free string. A module from before this change ignores Signal routes until the
   reboot after the update (its messenger list is fixed).

### The app: the person is the Android contact

5. **Derived Signal routes.** Every 1:1 contact (not a group) that is linked to an Android contact
   (`androidLookupKey`) and has a voice also routes `contact:<lookupKey>` for Signal and for Molly. This covers chats
   added from contacts, chats linked for phone ringtones, and restored chats. The same voice, haptic and shuffle seed
   apply, so a voice set on a WhatsApp chat plays on Signal with no extra step. The derived keys are not stored: they
   follow the link, so changing or removing the Android link moves them. When two contacts claim the same derived
   key (a WhatsApp chat and a WhatsApp Business chat linked to the same person), a stored key wins, then the oldest
   contact.
6. **Signal recent senders.** A Signal conversation the module saw but couldn't route appears on Home like a WhatsApp
   one. If its key is `contact:<lookupKey>` and a Cucuto contact is linked to that Android contact, the sighting joins
   that contact (one row, one voice). Assigning a voice to a Signal sighting creates a contact linked to that Android
   contact, so the person's WhatsApp chat shares it once linked.
7. **Signal conversations without a contact URI** (unsaved numbers, groups) get their own voice through the recipient
   ID. They can't follow a WhatsApp voice, because nothing ties them to a person. Recipient IDs are local to one
   Signal install, so after reinstalling Signal (or restoring Cucuto on a new phone) those conversations show up
   again as recent senders. Linked people keep working, because the Android contact is the key.
8. **Add from contacts lists Signal entries.** The same one query (ADR 0065) adds Signal's MIME type. A Signal entry
   becomes a contact linked to the Android contact, with the stored key `contact:<lookupKey>` for Signal and its
   number for re-matching after a restore. Its voice plays from its first Signal message, with no linking step. Molly
   writes the same MIME type, so the list shows these entries as "Signal". The derived routes cover Molly too.
9. **Labels.** Home and the contact detail say which app a chat is in: no prefix for WhatsApp (as before),
   "Business", "Signal", or "WhatsApp and Signal" when one person's row has been seen on both. Setup's messenger
   check accepts WhatsApp, WhatsApp Business, Signal or Molly. Spanish strings come in the same PR.

### What does not change

- **Ringtones.** Phone ringtones (ADR 0039) set the Android contact's ringtone, which Signal ignores for its calls.
  Cucuto doesn't cover Signal calls: that would need a hook in Signal's process, which ADR 0051 allows only behind
  its own ADR and `question` issue. Not proposed. The contact detail's Phone calls row keeps meaning phone and
  WhatsApp calls.
- **WaEnhancer X** hooks WhatsApp only, and Cucuto adds nothing to WhatsApp. No interaction.
- **Privacy.** A contact lookup key is what Cucuto already stores for linked contacts. It never leaves the phone, is
  never logged, and appears only in routes and recent senders.

## Limits (fail softly)

| Situation | What happens |
| --- | --- |
| Signal's notification privacy is "No name or message" | No person, no shortcut: Signal's own tone plays |
| Signal can't read contacts | No contact URI: the conversation routes on its recipient ID, with its own voice |
| Signal's screen lock is on | No shortcut, but the person's URI is still there: linked people still play their voice |
| The Android contact is re-joined or split and its lookup key changes | Signal's tone until Signal and Cucuto refresh the link (Signal re-syncs; Cucuto re-links on restore and through phone ringtones) |
| A future Signal stops setting the person's URI | Recipient IDs still work; linked voices fall back to Signal's tone. No crash, every read is fail-soft |

## Alternatives considered

- **Route on the recipient ID only and learn it, like WhatsApp's `@lid` (ADR 0065).** It needs a name-matching step
  and leaves the first message on Signal's tone. The person's URI is already on the notification and names the
  Android contact exactly, so there's nothing to guess.
- **Look up the phone number in `system_server`.** The URI is enough. Querying contacts from the hook would add I/O
  and a permission dependency to the most sensitive process.
- **Store the Signal keys as rows when a contact is linked.** That needs a backfill for existing links and cleanup
  when links change, and it collides with the unique key when two chats share an Android contact. Deriving them
  while building the table is always in step with the link.
- **A Signal scope to change call ringtones.** Out of scope (above).

## Consequences

- Cucuto works with free software alone, so the F-Droid recipe no longer declares `NonFreeDep`.
- The table grows by two routes per linked person with a voice: about 100 characters each. The heavy user of ADR 0054
  stays far below the limit.
- The brief's "Target apps" row lists Signal and Molly. "Other messengers" leaves the later ideas, except Telegram and
  SMS.
- Device check: a Signal message from a linked contact plays the voice (the lead and owner, `needs-device`).
