# 0019. Long notification clips (up to 30 s)

- Status: accepted (the hint threshold and the "no cut" default are open for the owner in #136)
- Date: 2026-10-24
- Issue: #119 (from the owner's answer on #75)

## Context

Pack format v1 limited notification clips to 0.2–4 s. The owner asked for more room: "on the notification duration
limit, I would be more permissive on the maximum length to not limit creativity" (#75, item 7). #119 proposed 30 s.
A longer clip is easy to accept in the validator. The question is what it means at runtime, where Cucuto only swaps
the sound URI and the vibration of WhatsApp's notification and the system plays both.

What the system does with a notification sound (AOSP `NotificationAttentionHelper` and SystemUI's
`NotificationPlayer`; to confirm on the owner's phone, `needs-device`):

- It plays the sound once, to the end, on the notification stream. While it plays it holds transient audio focus
  that ducks other audio, so music gets quieter for the whole clip.
- Effects stop early when the user pulls down the shade (`clearEffects` on panel reveal) and when the notification
  is cancelled (for example, the chat is opened or read on another device).
- There is one notification player: a new alerting notification replaces the sound that is playing. Android 15+
  "notification cooldown" also lowers the volume of quick repeat alerts from the same app.

## Decision

**Clips are 0.2 to 30 s** (`Limits.CLIP_MAX_MS = 30_000`, 50 ms tolerance unchanged). This changes the pack schema
description, `CLIP_DURATION_OUT_OF_RANGE`, the fixtures (`invalid/clip-too-long` is now 31 s and the new
`valid/longest-clip` is exactly 30 s), `docs/pack-format.md`, the website and the app's error text. A larger maximum
accepts more packs and rejects none, so `formatVersion` stays 1.

**Length is the pack author's and the user's choice.** Cucuto does not cut, fade or cap long clips at runtime:

- The defaults are already gentle. The shade or opening the chat stops a clip, a new message replaces it, and the
  cooldown softens bursts.
- Cutting a clip would need our own player, or trimmed copies served by the clip provider. That means more code on
  the notification path and goes against minimum intrusion.
- Users choose the character per contact and can switch clips off one by one (contact detail).

**A hint, not a rule.** `Limits.LONG_CLIP_HINT_MS = 5_000` is shared by the UIs. Above it, the app (pack preview,
clip toggles) and the pack creators say that long clips play to the end and lower other audio. This is never a
validation issue: no new error code, and packer stays silent.

**Haptics do not grow with the clip.** Auto haptics analyse only the first 2 s and end by 2 s (ADR 0012), and custom
haptics end by 5 s (`HAPTIC_MAX_MS`). A 30 s clip vibrates along with its opening only. A 30 s buzz would be
unpleasant and costs battery, and the brief's haptics principles ask for short, crisp effects.

**No effect on the routing table (#98).** A clip in the table is a URI suffix, a weight and an optional envelope.
The envelope is capped at 16 points and 2 s whatever the clip's length, so table size does not depend on duration.
#98 stays about the number of voices and clips.

**Import cost.** The importer test-decodes every clip in full (`PlatformAudioProbe`, 30 s timeout). Decoding runs
much faster than real time, so 30 s of audio stays well under the timeout. Files are bounded by size, not
duration: 100 MiB per audio file, unchanged. Opus at packer's 96 kb/s is about 360 KB for 30 s.

## Consequences

- Packs can carry jingles and song hooks. Ringtones (5 s to 6 min, looping) are still the place for whole songs.
- The hint has to be built into the screens that show clips: the pack import preview, contact detail and both pack
  creators. Each of those issues should mention `LONG_CLIP_HINT_MS`.
- If long clips turn out to annoy in daily use, the fallback is an opt-in setting ("Stop clips after N s"). It
  would serve trimmed copies from the clip provider, with nothing new in the module. The owner can ask for it in
  #136.
