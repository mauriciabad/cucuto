# 0031. Group policy and WhatsApp Business

- Status: accepted (the owner may override the "members show up in recent senders" default, #158; device check
  pending, #157)
- Date: 2026-10-24
- Issues: #20 (group policy: one character per group, or per sender), #21 (WhatsApp Business support)

## Context

The owner settled both product questions: a group speaks with one character by default, with "per sender" as a
per-group option (#56), and WhatsApp Business is in v1 (#57). ADR 0009 already stores the policy on the contact
(`contacts.groupPolicy`) and publishes it in the routing table (route flag bit 1); `RoutingTable.resolve` already
prefers the sender's route in a per-sender group, and ADR 0009's default (#86) keeps WhatsApp's tone for a group with
the default policy and no voice. ADR 0010 hooks both `com.whatsapp` and `com.whatsapp.w4b`, and ADR 0015 lists both in
the manifest's `<queries>`. What was missing:

- **The sender.** The device run (M0 report, "Supervised device run") showed that the record that alerts is
  WhatsApp's group summary, whose shortcut ID is the conversation (`…@lid` for 1:1 chats, `…@g.us` for groups), and
  that channels are `individual_chat_defaults_*` and `group_chat_defaults_*`. Whether that summary carries the
  newest message's sender (a `MessagingStyle` `Person` in `EXTRA_MESSAGES`) is still unknown: the spike's reader was
  buggy and saw nothing. Nothing in the decision log or the module log could tell a missing sender apart from a
  sender without a voice.
- **A way to use it.** Nothing in the app could set the policy, and a group member who never wrote to the user
  directly could not get a voice.
- **Telling the two apps apart.** WhatsApp and WhatsApp Business are separate accounts with separate keys. The same
  person can appear in both, and the two chat rows looked the same.

## Decision

**Read the sender only for groups, and only the sender.** `RecordReader` decides whether the record is a group with
the same rule as `ConversationKeys` (channel prefix, then `@g.us`, then the MessagingStyle flag), now also a function
on raw values. Only then does it open the newest entry of `EXTRA_MESSAGES`, and from it only `sender_person` (then
`Person.getKey()` and `getName()`) or the legacy `sender` name. A 1:1 chat's messages are never opened: its shortcut ID
already is the person. Message text is never read (minimum intrusion). The `Person` key is the routing key, and the
name labels the member in the app.

**Make the outcome observable without exposing anyone.** The decision's `detail` names the per-sender case with
constant strings, so the notification path allocates nothing new:

- `per-sender`: the sender's own voice played.
- `per-sender, no sender on the notification: group voice`, and the same without a group voice.
- `per-sender, sender has no voice: group voice`, and the same without a group voice.

The module's log line for a group alert gains `sender=<shape>`: the JID domain of the sender's key (`@lid`,
`@s.whatsapp.net`), `<other>` for a key that is not a JID, `<name only>` or `<none>`. This answers the device question
("does the summary carry the sender, and is it keyed like a 1:1 chat?") from the LSPosed log alone.

**Routing stays as ADR 0009 defines it.** The table format does not change. In a per-sender group the sender's route
plays when it has a voice; otherwise the group's voice plays; with neither, WhatsApp's tone. A default-policy group
never uses its members' voices. One shuffle bag per speaker (`package + key`), so a person's clips never repeat back to
back, whether they write in a 1:1 chat or in a per-sender group.

**Setting the policy.** `ChatDirectory.setGroupPolicy(chat, policy)` creates the contact if the group was only seen
(a per-sender group needs no voice of its own), and `Chat.groupPolicy` exposes the current value. The UI is a
stateless `GroupPolicySetting` composable (`ui/chat/`) for the contact detail screen (#26): a Material 3 Expressive
connected button group ("The group" / "Each sender", radio semantics), which m3.material.io recommends over segmented
buttons, with a one-line explanation of the selected option.

**Members of per-sender groups show up in recent senders.** When a per-sender group alerts with a sender whose key is
a WhatsApp person JID (`@lid` or `@s.whatsapp.net`), `AlertSightings` also records that sender as a chat of its own,
named after the message's sender. That key is exactly the key of their 1:1 chat, so one tap gives them a voice that
plays in the group and in a direct chat. Groups with the default policy add nobody, so busy groups do not flood the
list with strangers. A key of any other shape is not a chat and is not added (it can still route if the app learns it
another way).

**WhatsApp Business is a separate app with its own keys.** Nothing is shared across packages: routes, contacts
(`(package, key)` is unique), shuffle bags, sightings and group policies are all per package. Chat rows from Business
say so ("Business · …", "Business group · …", `chatSupportingText`).

## Consequences

- If the summary carries no sender, per-sender groups behave like default groups and the decision log says why
  (`no sender on the notification`). The fallback would then be to read the sender from the group's child
  notification, which posts with `onlyAlertOnce` and never reaches `playSound`. That needs a different hook point, so
  it is a follow-up, not a guess.
- If WhatsApp keys senders differently from 1:1 shortcut IDs (for example a phone JID in groups and an `@lid` in 1:1
  chats), the same person becomes two chats until the user merges them. The `sender=` log shape shows which case
  applies.
- The device check is supervised: steps in [`device-testing.md`](../device-testing.md) section 3b, follow-up issue
  #157 (`needs-device`).
