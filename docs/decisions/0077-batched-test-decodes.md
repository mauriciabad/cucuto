# 0077. Batched test-decodes and one decoder per worker

- Status: accepted
- Date: 2026-10-24
- Issues: #279, #268
- Amended: 2026-10-24 (#315, auto haptics at install use the same decoding loop)

## Context

Importing the owner's private Pokémon pack (28 MB: 268 characters, 1,541 short Opus clips of 1.2 s on average, 268
SVG sprites) stays on "Testing sounds and pictures…" longer than expected (#279). [ADR 0061](0061-fast-import-checks.md)
already test-decodes 8 files at once. This pack has a different shape from #241's: many small files instead of a few
large ones.

Profile on an API 37 emulator (4 cores), with 240 of the owner's clips (used locally, never committed), interleaved per
file. Medians per clip; the host was heavily loaded, so the ratios count more than the absolute values:

| Step | Time |
| --- | --- |
| Opening the file (`MediaExtractor`) | 23–26 ms |
| Creating the decoder (`MediaCodec.createByCodecName`) | 58–70 ms |
| Configuring and starting it | 40 ms |
| Decoding, one packet at a time (about 53 packets of 20 ms) | 311 ms (8.4 ms per packet) |
| Decoding, several packets per input buffer | 46 ms |
| Stopping and releasing it | 12 ms |

Each packet is a round trip to the codec process, and on a busy system each round trip waits to be scheduled. The
decoding itself is cheap. Creating a decoder costs more than decoding a whole short clip in batches.

Checked and rejected:

- **Decoding only the first and last packets of a clip** after proving its length from the Ogg pages. With batches, a
  whole clip costs about as much as configuring the decoder, so decoding less would save little. It would also weaken
  the check the brief asks for ("test-decode every clip").
- **Caching the SVG checks.** pack-spec checks the 268 sprites in well under a second on a laptop JVM (the whole
  validation took 0.4–1.4 s there). Each import checks them only once, so a cache would never be hit.
- **`MediaCodec.reset()` to reuse a decoder.** It cost as much as a new decoder on the emulator. `stop()` keeps the
  codec component, and a stopped codec can be configured again (the documented state machine).

## Decision

**Batches.** When the decoder reports `CodecCapabilities.FEATURE_MultipleFrames` (Android 15; on the API 37 emulator
every `c2.android.*` audio decoder does), `PlatformAudioProbe` runs it in asynchronous mode. Each input buffer
carries as many packets as fit (`MediaCodec.queueInputBuffers`, input buffers of at least 64 KiB, output batches up to
512 KiB through `KEY_BUFFER_BATCH_MAX_OUTPUT_SIZE` and `KEY_BUFFER_BATCH_THRESHOLD_OUTPUT_SIZE`). A short clip then
fits in one or two buffers. The callbacks only queue events; the probing thread does all the work, so a late callback
from an earlier run never touches the codec. Decoders without the feature keep the synchronous loop, one packet at a
time. A long ringtone still decodes its first and last 2 s; with batches, each window is one configure, start and
stop.

**One decoder per worker.** `AudioProbe.session()` and `MediaVerifier.session()` give each of `PackImporter`'s
decoding workers a probe that keeps its decoder between files. After a file it stops the decoder, and it configures
the same one for the next file when the decoder name matches. A decoder that threw, failed or timed out is released,
never reused. The session closes, and releases its decoder and callback thread, when its worker ends, even on failure
or cancellation.

**Nothing is checked less.** Every packet of every clip is still decoded, through the same extractor and decoder
the system plays notification sounds with. Long ringtones are decoded at their start and end, and images whole, as
before. The duration still comes from the container, and the results are the same file for file. On the emulator,
all 1,541 clips of the owner's pack decoded with batches, and the first 160 gave identical results in all four modes
(with or without batches, with or without reuse). [Pack format](../pack-format.md) is unchanged.

## Consequences

- `LargePackDeviceBenchmark` (androidTest, run by hand) has a second shape like the owner's pack:
  `preparesAPackWithManyClips`, 268 characters and 1,541 clips. Both tests now log "before" (a new decoder for every
  file, one packet at a time, as up to v0.1.7) and "after". On the loaded API 37 emulator:

  | Pack | Before | After |
  | --- | --- | --- |
  | Many clips (1,541 clips, 268 SVG): decoding | 68.7 s | 19.2–19.5 s (3.5x) |
  | Many clips: whole `prepare` | 73.9 s | 22.2–22.6 s |
  | Large (#241: 98 clips of 6 s, 196 ringtones, 98 JPEG): decoding | 110.9 s | 5.7–5.8 s (19x) |
  | Large: whole `prepare` | 119.5 s | 6.5–6.6 s |

  Longer files gain more because each of their 300 packets was a round trip before.

  The owner's phone has twice as many, faster cores and is not shared with other emulators. The target from #279
  is a 1,500-clip pack checked in under 20 s there.
- `ManyClipsBenchmarkTest` (JVM, runs in CI) prepares a synthetic pack of the same shape (`SyntheticPack.writeManyClips`)
  with decoders modelled at a fiftieth of the emulator times above. It checks that every worker uses one session and
  closes it, that "after" beats "before", and a generous 30 s bound. On a laptop: 5.2 s before, 1.8 s after.
- Up to 8 decoders, each with its own callback thread, stay allocated (stopped) while the import checks run, then are
  released. That is the same bound as ADR 0061.
- Auto haptics at install used the old loop until #315; see the amendment below.

## Amendment: auto haptics at install (#315)

Installing a pack runs auto haptics ([ADR 0020](0020-auto-haptics-at-import.md)): `ClipPcmDecoder` decodes the first
2 s of every clip, 8 clips at once. It still created a decoder per clip and sent one packet at a time, so for the
owner's pack the "Installing" step took about as long as the old import checks.

**One decoding loop.** The batched and synchronous loops, the decoder kept per session and the callback thread moved
from `PlatformAudioProbe` to `PlatformAudioDecoder` in a new module, `:core:codec`, with no dependencies. `:core:pack`
and `:core:haptics` both use it; neither depends on the other. A `PcmSink` receives each decoded access unit:
`PlatformAudioProbe` counts frames, `ClipPcmDecoder` converts them to mono floats. Batched output buffers hold several
access units, and the sink gets each one at its own offset. A `DecodeWindow` says where to stop (and, for ringtones,
where to seek), so auto haptics stops feeding packets after 2 s as before.

**One decoder per worker.** `PcmDecoder.session()` mirrors `AudioProbe.session()`: each of `AutoHaptics.analyzePack`'s
workers opens one session, decodes all its clips with it and closes it when it ends, even on failure or cancellation.
A clip whose decoding fails releases the decoder, and the next clip gets a new one. `AutoHaptics.analyze` (one clip,
for previews) decodes with a decoder of its own, as before.

**The same haptics.** `BatchedClipDecodeTest` (androidTest in `:core:haptics`) decodes every audio file of the valid
spec fixtures and of the demo packs (Ogg Opus, Opus, MP3, AAC, FLAC and WAV, including files longer than 2 s) both ways:
a new decoder per clip with one packet at a time, and one reused session with batches, in order and then in reverse.
It requires the same samples, bit for bit, and the same envelopes. It passed on the API 37 emulator, where the Opus
decoder takes batches. With its local-only `clipsDir` argument it also passed on all 1,546 audio files of the owner's
Pokémon pack. A dump made with the code before this change and with the code after it (samples checksum and envelope
per clip, same files) was identical.

Measured with `LargePackDeviceBenchmark`, which now also times `install` with `AutoHaptics` as the `PackAnalyzer`
(`Analyzing` is auto haptics). Its `preparesALocalPack` test takes a pack archive pushed by hand (`packArchive`
argument) for local measuring; the owner's pack was used that way and never committed. API 37 emulator (4 cores), host
lightly loaded, "after" over two runs:

| Pack | Auto haptics before | Auto haptics after | Whole `prepare` before → after |
| --- | --- | --- | --- |
| Owner's Pokémon pack (1,541 clips) | 40.3 s | 14.1–14.6 s (2.8x) | 49.3 s → 22.2–22.4 s |
| Many clips (1,541 fixture clips) | 29.3 s | 12.9–13.5 s (2.2x) | 45.3 s → 16.3 s |
| Large (#241: 98 clips of 6 s) | 3.8 s | 0.84–0.91 s (4.4x) | 37.1 s → 4.6–4.7 s |

What is left per clip is most likely opening the file and configuring, starting and stopping the decoder, as for the
import checks. `ManyClipsBenchmarkTest` (JVM, runs in CI) has a second test, `installs a pack with many clips
quickly`, which installs the synthetic pack with `AutoHaptics` and the same modelled decoder waits. It checks that every
worker uses one session and closes it, that "after" beats "before", and the same 30 s bound. On a laptop: 3.3 s
before, 0.56 s after.
