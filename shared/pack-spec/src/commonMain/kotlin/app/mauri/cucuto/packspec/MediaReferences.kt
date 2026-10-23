package app.mauri.cucuto.packspec

/** What a manifest uses a media file for. */
enum class MediaRole(val label: String) {
    COVER("cover"),
    SPRITE("sprite"),
    SPRITE_ANIMATED("spriteAnimated"),
    CLIP("clip"),
    RINGTONE("ringtone"),
    ;

    /** True when [format] may be used for this role (FILE_TYPE_MISMATCH otherwise). */
    fun accepts(format: FileFormat): Boolean = when (this) {
        COVER, SPRITE -> format.kind == FileKind.IMAGE
        SPRITE_ANIMATED -> format.canAnimate
        CLIP, RINGTONE -> format.kind == FileKind.AUDIO
    }
}

/**
 * A media file a manifest points at. [path] is relative to the manifest's folder, [pointer] is the JSON Pointer of the
 * value in the manifest, and [ringtone] is set for ringtones (loop points are checked against the measured duration).
 */
data class MediaReference(val path: String, val role: MediaRole, val pointer: String, val ringtone: Ringtone? = null)

/** Every media file this pack references, in manifest order (a file used twice appears twice). */
fun PackManifest.mediaReferences(): List<MediaReference> = buildList {
    cover?.let { add(MediaReference(it, MediaRole.COVER, "/cover")) }
    characters.forEachIndexed { ci, c ->
        val cp = "/characters/$ci"
        add(MediaReference(c.sprite, MediaRole.SPRITE, "$cp/sprite"))
        c.spriteAnimated?.let { add(MediaReference(it, MediaRole.SPRITE_ANIMATED, "$cp/spriteAnimated")) }
        c.clips.forEachIndexed { i, clip -> add(MediaReference(clip.file, MediaRole.CLIP, "$cp/clips/$i/file")) }
        c.ringtones.forEachIndexed { i, r -> add(MediaReference(r.file, MediaRole.RINGTONE, "$cp/ringtones/$i/file", r)) }
    }
}

/** Every media file this bundle manifest references (only its cover; packs list their own files). */
fun BundleManifest.mediaReferences(): List<MediaReference> = listOfNotNull(cover?.let { MediaReference(it, MediaRole.COVER, "/cover") })

/**
 * Duration rules for audio (docs/pack-format.md#audio), shared by the header checks here and the decoder checks of
 * the Android importer. All limits include the 50 ms tolerance for encoder padding.
 */
object DurationRules {
    fun clipInRange(ms: Long): Boolean =
        ms >= Limits.CLIP_MIN_MS - Limits.DURATION_TOLERANCE_MS && ms <= Limits.CLIP_MAX_MS + Limits.DURATION_TOLERANCE_MS

    fun ringtoneInRange(ms: Long): Boolean =
        ms >= Limits.RINGTONE_MIN_MS - Limits.DURATION_TOLERANCE_MS && ms <= Limits.RINGTONE_MAX_MS + Limits.DURATION_TOLERANCE_MS

    /** True when [loop] lasts at least 1 s and ends within [ms] of audio. A null loop (play once) always fits. */
    fun loopFits(loop: LoopRange?, ms: Long): Boolean {
        if (loop == null) return true
        val end = loop.endMs ?: ms
        val start = loop.startMs ?: 0L
        return end <= ms + Limits.DURATION_TOLERANCE_MS && start + Limits.MIN_LOOP_MS <= end
    }

    /**
     * The duration issues of one audio file measured at [ms] for each of its [references] (CLIP_DURATION_OUT_OF_RANGE,
     * RINGTONE_DURATION_OUT_OF_RANGE, LOOP_POINTS_INVALID). [file] is the audio file and [manifestFile] the manifest
     * holding the references, both relative to the source root.
     */
    fun check(file: String, ms: Long, references: List<MediaReference>, manifestFile: String): List<Issue> = buildList {
        for (ref in references) {
            when (ref.role) {
                MediaRole.CLIP -> if (!clipInRange(ms)) {
                    add(
                        Issue(
                            ErrorCode.CLIP_DURATION_OUT_OF_RANGE,
                            "Clip is $ms ms; clips are ${Limits.CLIP_MIN_MS} to ${Limits.CLIP_MAX_MS} ms",
                            file = file,
                            pointer = ref.pointer,
                        ),
                    )
                }
                MediaRole.RINGTONE -> {
                    if (!ringtoneInRange(ms)) {
                        add(
                            Issue(
                                ErrorCode.RINGTONE_DURATION_OUT_OF_RANGE,
                                "Ringtone is $ms ms; ringtones are ${Limits.RINGTONE_MIN_MS} to ${Limits.RINGTONE_MAX_MS} ms",
                                file = file,
                                pointer = ref.pointer,
                            ),
                        )
                    }
                    val loop = ref.ringtone?.loopRange
                    if (!loopFits(loop, ms)) {
                        add(
                            Issue(
                                ErrorCode.LOOP_POINTS_INVALID,
                                "Loop ${loop?.startMs ?: 0}..${loop?.endMs ?: ms} ms does not fit in $ms ms of audio",
                                file = manifestFile,
                                pointer = ref.pointer.removeSuffix("/file") + "/loop",
                            ),
                        )
                    }
                }
                else -> {}
            }
        }
    }
}
