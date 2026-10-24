package app.mauri.cucuto.packspec

/** Numeric limits of pack format v1. Mirrors the table in docs/pack-format.md. */
object Limits {
    const val FORMAT_VERSION = 1

    const val MiB = 1024L * 1024L
    const val GiB = 1024L * MiB

    const val PACK_UNPACKED_BYTES = 1 * GiB
    const val BUNDLE_UNPACKED_BYTES = 2 * GiB
    const val LARGE_PACK_WARNING_BYTES = 256 * MiB
    const val FILES_PER_PACK = 5_000
    const val ENTRIES_PER_ARCHIVE = 20_000

    /** Uncompressed / compressed, checked per entry and for the whole archive once more than 1 MiB is involved. */
    const val MAX_COMPRESSION_RATIO = 100
    const val RATIO_CHECK_THRESHOLD_BYTES = 1 * MiB

    const val MAX_PATH_LENGTH = 255
    const val MAX_PATH_DEPTH = 8

    const val MANIFEST_BYTES = 1 * MiB
    const val AUDIO_FILE_BYTES = 100 * MiB
    const val BITMAP_FILE_BYTES = 8 * MiB
    const val SVG_FILE_BYTES = 1 * MiB
    const val TEXT_FILE_BYTES = 1 * MiB

    const val MAX_BITMAP_SIDE_PX = 512

    const val CLIP_MIN_MS = 200L
    const val CLIP_MAX_MS = 30_000L

    /** Above this, the app and the pack creator show a gentle hint (long clips play to the end); never an error. */
    const val LONG_CLIP_HINT_MS = 5_000L
    const val RINGTONE_MIN_MS = 5_000L
    const val RINGTONE_MAX_MS = 360_000L
    const val DURATION_TOLERANCE_MS = 50L
    const val MIN_LOOP_MS = 1_000L

    /**
     * A rendered ring file ([RingFiles]) lasts at least this long: the loop section repeats until the file reaches it,
     * and a ringtone that plays once is padded with silence to it. Telecom loops the whole file and calls ring for about
     * 20 to 60 s before voicemail, so the intro is heard once. The longest ring file is under this plus one loop.
     */
    const val RING_FILE_MIN_MS = 90_000L

    /** Default length of a notification clip cut from a ringtone ([ClipCuts]); the brief asks for 2 to 3 s. */
    const val CUT_CLIP_DEFAULT_MS = 2_500L

    /** Fades a renderer applies to a cut clip so it does not click. */
    const val CUT_FADE_IN_MS = 10L
    const val CUT_FADE_OUT_MS = 200L

    const val HAPTIC_MAX_MS = 5_000L
}
