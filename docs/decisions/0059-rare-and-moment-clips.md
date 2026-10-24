# 0059. Rare, first of the day, burst and date clips

- Status: accepted (the owner may override the defaults below, see the `question` issue linked from #120)
- Date: 2026-10-24
- Issues: #120 (battery-free condition ideas), #75 (owner: "more conditions that allow for fun, enjoyable and
  creative experiences, and that dont drain the battery"), extends [ADR 0037](0037-day-and-night-clips.md)

## Context

#120 brainstormed 14 battery-free ideas. Each one costs something: in the pack format (every key is a promise to
pack authors), in the routing table (a heavy user is at 95 % of the Remote Preferences limit, #223 and ADR 0054), and
on the notification path in `system_server` (5 ms budget, never throw, no allocation in steady state, ADR 0047).
The best ideas are the ones a pack author can use to surprise someone, that need nothing new from the phone, and that
the module can decide from what it already holds.

## Decision

**Four ideas, picked for fun per cost:**

| Idea (#120) | Pack format | Where it is decided |
| --- | --- | --- |
| 12, shiny clips | clip `oneIn`: 2 to 10,000 | Module: one `Random.nextInt` per rare clip and message |
| 6, first message of the day | clip `when.firstOfDay: true` | Module: the speaker's last alert day against today |
| 8, bursts | clip `when.burst`: 2 to 50 | Module: the speaker's messages in a row (each within 60 s) |
| 1, dates and holidays | `when.date`: `"MM-DD"`, `{ "from", "to" }` or a list | App: compiled into `W` lines like any `when` (ADR 0037) |

Left out for now: birthdays from Android contacts (needs the contacts permission and splits voices per contact, so the
table grows with every birthday), moon phase and golden hour (cheap, but less fun per key), comebacks (a quiet gap is
covered by `firstOfDay` for most people), mentions and phone in use (read more of the notification or the phone,
opt-in per #67), battery and charging (the module would have to read battery state), evolutions and streaks (need the
decision log in the app and a republish per message). They stay in #120.

**Special clips play instead of the shuffle bag.** A clip with `firstOfDay` or `burst` (a *moment* clip) or `oneIn`
(a *rare* clip) never joins the bag. On each message: a moment clip that matches plays (one by weight if several); else
each rare clip tries its chance in manifest order; else the bag draws from the ordinary clips as before. Joining the
pool would make a "good morning" clip one draw among many, which is not the point. Moment clips repeat by nature only
once per day or per burst, and a rare clip never plays twice in a row, so the brief's no-repeat rule still holds.
A voice whose ordinary clips are all switched off rotates its special clips, so it never goes silent.

**`firstOfDay` and `burst` live in the clip's `when`**, next to the time keys they combine with (a good-morning clip is
`{ "firstOfDay": true, "time": { "from": "05:00", "to": "12:00" } }`). Ringtones use their own `when` without them
(the schema splits `when` from `clipWhen`), since a call has no conversation rhythm. `oneIn` is a clip field, because
it is a property of the clip, not a condition of the moment; it combines with any `when`.

**The app precomputes; the module counts.**

- Dates are ordinary time conditions: `ConditionCompiler` intersects the matching local days, so the module needs no
  change for them, and ringtone variants (`VariantRules`) match them too.
- A day starts at **04:00 local time**, so a late-night chat still belongs to the evening before. The app publishes
  where days start as one `D` line: the shift (UTC offset minus 04:00) and each daylight saving change in the next 400
  days, about 60 characters. The module computes the day as `floor((now + shift) / 1 day)`: no time zone rules in
  `system_server`. Without the line it counts days in UTC.
- The rhythm (last alert time, its day, messages in a row, the last rare clip) lives in the speaker's shuffle bag entry,
  which is bounded (512 speakers) and kept across republishes; the bag is rebuilt in place. It is tracked only for
  voices with special clips, read with one clock call, and allocates nothing (`MomentClipsTest`,
  `DecisionPerformanceTest`). The router's clock became a `fun interface` (`WallClock`), because a `() -> Long` boxed
  every reading into a new `Long`.
- The routing table gets a sparse trailing `m` field on voices with special clips (`m3:f,4:b3,5:r25`) and the `D` line.
  Old readers skip both and draw special clips like ordinary ones until the next reboot; tables without special clips
  are byte-for-byte unchanged (the 0.1.x reader and both goldens check this).

**Format version 1.** Every addition is optional, and a reader that ignored it would still play the pack (special
clips would just rotate). Like clip `when` in ADR 0037, they stay in version 1 while Cucuto is below 1.0; an app older
than this change rejects packs that use them (`SCHEMA_VIOLATION`). Impossible dates (`02-30`) are `WHEN_INVALID`.

**Demo.** Tweet the bird (`chiptune-critters` 1.2.0) greets a chat's first message of the day with a dawn chorus,
squawks at the third message in a row, and calls like a cuckoo on about one message in 25. All three are synthesized
by `packs/generate.main.kts` (CC0). Contact detail labels special clips ("First message of the day", "Message 3 in a
row", "Rare: about 1 in 25 messages").

## Consequences

- No battery cost: no sensors, location, network, alarms or wake-ups; the module reads the clock only for voices with
  conditional or special clips. A decision with special clips takes about 0.3 us and allocates only its result.
- Size: the heavy user of ADR 0047 with a rare clip on every third character, a first-of-the-day clip on every fifth
  and a burst clip on every sixth, plus a date condition, grows by about 760 characters (0.3 % of the Remote
  Preferences limit, 251,121 characters). #234 (ADR 0054) moves the full table to the remote file and leaves ample room.
- The rhythm is forgotten when `system_server` restarts: the first message after a reboot counts as the first of the
  day. Messages that do not alert (muted chats) are not counted. In a group with one voice per sender, the rhythm is
  the sender's, shared with their one-to-one chat, like their shuffle bag.
- "Simulate message" goes through the same path, so a simulation counts as a message (it can use up the day's
  greeting), as it already does for the shuffle bag.
- Defaults the owner may change: the 04:00 day start, the 60 s burst gap, special clips playing instead of the bag,
  counting from boot, and the four ideas picked.
