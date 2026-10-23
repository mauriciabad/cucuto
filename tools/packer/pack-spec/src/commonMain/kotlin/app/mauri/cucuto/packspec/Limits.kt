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
    const val CLIP_MAX_MS = 4_000L
    const val RINGTONE_MIN_MS = 5_000L
    const val RINGTONE_MAX_MS = 360_000L
    const val DURATION_TOLERANCE_MS = 50L
    const val MIN_LOOP_MS = 1_000L

    const val HAPTIC_MAX_MS = 5_000L
}
