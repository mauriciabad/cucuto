# 0061. Fast import checks for large packs

- Status: accepted
- Date: 2026-10-24
- Issues: #241

## Context

On the owner's phone, importing a private 306 MB pack (98 characters, 98 clips, 193 Ogg Opus ringtones, 98
covers) stayed on "Testing sounds and pictures…" for 3 to 4 minutes before the review appeared (#241).

Profile, on a synthetic pack of the same shape (`SyntheticPack` in `:core:pack` tests: 392 media files, about 300 MB):

- **Platform test-decodes: almost all the time.** On an API 37 emulator, `PackImporter.prepare` with the platform
  decoders took 160 to 180 s for 98 clips, 196 ringtones and 98 images (`LargePackDeviceBenchmark`). One 6 s Opus file
  took about 300 ms to decode, plus about 40 ms to create and start the codec. That is about 1 ms per 20 ms packet, and
  almost none of it is CPU: Codec2 runs in the media codec process and each packet is a round trip. Feeding all free
  input buffers before draining the output did not help (the codec still takes one packet at a time), but several
  files at once did: 24 files took 499 ms per file one at a time, 294 ms with 2 at once, 146 ms with 4 and 77 ms with 8,
  on a 4-core emulator.
- **Reading the archive: seconds on a phone.** pack-spec checked every media entry in full three times: in the layout
  stage (`readHead` reads and CRC-checks the whole entry, so a corrupt file fails there), in the media stage (the header
  probes) and once more while unpacking. The CRC-32 ran a byte at a time on `Long`s. On a laptop JVM that was 2.3 s to
  validate and 1.3 s to unpack 300 MB; a phone is several times slower.

## Decision

**Test-decode several files at once.** `PackImporter` runs the platform probes on a bounded pool:
`DEFAULT_DECODE_PARALLELISM` is the number of CPU cores, at least 2 and at most 8 (a constructor parameter, for
tests). The work is waiting for the codec process, so 8 helps even on 4 cores; 8 software decoders are far below the
32 instances each codec allows, and each holds only small buffers. Results keep the order of the files, so the report
is the same as before, issue for issue. Every probe stays as it was: clips are decoded whole, long ringtones at their
first and last 2 s, images whole (at most 512 px a side), JPEG XL frame by frame. Nothing is checked less.

**Check each zip entry once.** `ZipArchive` remembers entries that passed every check (sizes, the end of the DEFLATE
stream, the CRC-32). Later reads still read and inflate the bytes and enforce the sizes, but skip the CRC-32, and
`useFile` reads a checked stored entry straight from the archive without another pass. The first read of every entry
is unchanged, so every error code and the stage that reports it stay the same; the golden fixtures pass on the JVM and
under Bun. It relies on the input not changing while the archive is open: the importer validates its private copy,
and the website validates a byte array in memory.

**A faster CRC-32.** Slicing-by-8 on `Int` (Kotlin/JS emulates `Long`) in common code: about 4x faster on the JVM, and it
helps the website too.

**Determinate progress.** The import screen shows "Testing 120 of 392 files" under the progress bar while decoding
(English and Spanish, `import_testing_count`). The step title stays the live region, so TalkBack does not read every
file.

Not done:

- **Multiple frames per input buffer** (`FEATURE_MultipleFrames`, Android 15) would cut the round trips by up to 50x,
  and the emulator's software audio decoders support it. It needs MediaCodec's asynchronous mode and new output
  callbacks, though, and could not be tested on a real device here. Left for a follow-up (#268).
- **Decoding less** (only the container length, or shorter windows) would weaken the check the brief asks for.
- **Auto haptics** (ADR 0020) runs at install and decodes the first 2 s of every clip, one at a time. Running it in
  parallel would speed up installing in the same way; it is a separate change in `:core:haptics` (#267).

## Consequences

- Synthetic pack, laptop JVM, decoders replaced by header probes plus a wait of a tenth of the measured packet time
  (`LargePackBenchmarkTest`, median of 3, on a heavily loaded machine): 15.2 s before, 3.0 s after (5x). Without the
  wait (archive work only): about 4 s before, 0.8 s after.
- On a phone, decoding should go about 4 to 6x faster with 8 at once, going by the emulator numbers above.
  `LargePackDeviceBenchmark` measures it (run by hand: it logs `LargePackBenchmark` lines).
- `LargePackBenchmarkTest` runs in CI with a generous 20 s bound, and checks that progress counts every file once.
- Up to 8 codec instances and 8 IO threads are busy for the length of the decoding step.
