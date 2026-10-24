# 0037. Day and night clips from sunrise and sunset

- Status: accepted (the owner may override the defaults below, see the `question` issue linked from #118)
- Date: 2026-10-24
- Issues: #118 (day and night from sunrise and sunset), #75 item 3 (owner: "i want sunrise and sunset from the
  phone's location, or a user setting"), #120 (battery-free condition ideas), #52 (rule-based ringtone variants)

## Context

The pack format already had `when` conditions (`time` day, night or an hour range; `season`; `weekday`), but only on
ringtone variants, and "day" meant a fixed 07:00 to 19:00. The owner wants day and night to follow sunrise and
sunset, from the phone's location or a setting, and asked for battery-free ideas. Notification clips are where a
character speaks most often, so a frog that hops by day and croaks at night is the most visible use.

Three constraints shape the design:

- **Minimum intrusion and battery** (#67, #88, M1 acceptance criterion): no tracking, no background location, no
  wake-ups, the least privileged permission, asked only when the feature is used.
- **The module stays dumb and fast.** It runs in `system_server` with a 5 ms budget and must never throw; it should
  not do calendars, time zones or solar maths on the notification path.
- **The pack format stays version 1** (#75): packs say "day" or "night"; which hours that is, is the phone's
  business.

## Decision

**Clips get the optional `when` of ringtone variants.** Same object, same rules, same `WHEN_INVALID` check
(schema, `shared/pack-spec`, `docs/pack-format.md`, packer shorthands, fixtures `valid/full-pack` and
`invalid/clip-when-empty-range`). When a message arrives, the clips whose condition holds and the clips without one
form the shuffle-bag pool. If no clip qualifies, the whole pool plays, so a character is never silent. The demo
frog now hops by day and croaks at night (`chiptune-critters` 1.1.0).

**The app compiles conditions into instants; the module compares the clock.** `:core:conditions` (plain Kotlin,
new) turns each distinct `when` into a `TimeCondition`: a span (`fromMs` = start of yesterday, `untilMs` = 400 days
later), the state at the start, and the instants where it flips, all computed with the phone's time zone and DST
rules. The routing table carries them in `W` lines, and each clip refers to one by index (trailing `w` field on the
voice; [routing table](../routing-table.md)). `TimeCondition.state(now)` is O(1) and allocation-free: a per-day
bucket index, built when the table is decoded, points at the day's first toggle, and a day has two or three. The
router reads the clock only for voices with conditional clips. Outside the span, a condition is unknown and
ignored (every clip plays), so a stale table degrades to the old behaviour instead of going silent.

**Sunrise and sunset from NOAA's algorithm, in plain Kotlin** (`SolarCalculator`): solar declination and the
equation of time from Meeus' formulas, the hour angle at 90.833° zenith, each event refined once at its own time.
Tests check sunrise and sunset on 18 days on four continents (DST days, time zones far from their meridian such as
Samoa and Kiritimati, a sunset after midnight near the polar circle) against an independent implementation
(`astral` 3.2) within one minute (two at 64° N), plus polar day and night. Polar day counts as day and polar night
as night, as the sky says. No library, no network.

**Where: a place the user sets, no tracking.** The Day and night screen (linked from Settings) offers, in this
order:

1. **A city** from a built-in list of about 160 (Spain and Catalonia well covered, the largest cities elsewhere;
   the phone's country is suggested first). No permission, no network, no geocoder.
2. **Typed coordinates.**
3. **"Use my location once":** asks for `ACCESS_COARSE_LOCATION` on that tap only, makes one low-power
   `LocationManager.getCurrentLocation` request to the fused (or network) provider, and saves the result as the
   place. No fine location, no location updates, nothing in the background.

Only the coordinates are stored (DataStore, rounded to two decimals, about 1 km) and they are not in backups. Until
a place is set, day is 07:00 to 19:00, the previous rule. Seasons are mirrored when the place is south of the
equator.

**No background job.** The published span covers 400 days (about 5 KB per distinct condition), and the table is
recompiled on every publication: whenever assignments or packs change, when the place changes, and once a day while
the app runs (`SystemCalendar` re-emits after local midnight, checked at least hourly, using a coroutine delay that
never wakes the phone). A WorkManager job was considered and left out: it would add a dependency and periodic
wake-ups for something the long span already covers.

**More conditions from #120, for free.** Because the compiler handles any `when`, clips also get hour ranges,
seasons and weekdays (a weekend voice, a spooky autumn night) with no extra module code.

## Consequences

- The routing table grows only for users with conditional clips; tables without them encode exactly as before.
  A module from before this change (until the next reboot) ignores the new lines and plays every clip.
- Travel: sunrise and sunset follow the place, not the phone, so the user changes the place when they move far.
  Time zone changes are picked up on the next publication (the hourly check while the app runs).
- If the app is not opened for over a year, conditions become unknown and every clip plays until it is.
- Ringtone variants (#52) can reuse `ConditionCompiler` and `TimeCondition.nextToggleAfter` for their boundary
  alarms; the brief's `:core:ringtone` work is unchanged otherwise.
- Defaults the owner may change (question issue): cities plus coordinates first and location only on request,
  polar day and night as the sky says, the 07:00 to 19:00 fallback not editable yet, the place not in backups, and
  "no matching clip plays the whole pool".
