package app.mauri.cucuto.core.creator

import app.mauri.cucuto.packspec.FileFormat
import app.mauri.cucuto.packspec.FileInput
import app.mauri.cucuto.packspec.FileKind
import app.mauri.cucuto.packspec.Limits
import app.mauri.cucuto.packspec.Ringtone
import app.mauri.cucuto.packspec.media.AudioProbe
import java.io.File

/**
 * "Add a ringtone" in the creator (#322, ADR 0049): a song goes into the draft's media as it is, in its own format (the
 * format never forces one, and re-encoding a song would lose quality), after checking it is audio a pack can hold and
 * lasts 5 s to 6 min (the importer's rules, with the same 50 ms tolerance).
 */
object RingtoneImport {
    sealed interface Result {
        data class Added(
            val ringtone: DraftRingtone,
        ) : Result

        /** Not one of the audio formats packs hold (by extension). */
        data object Unsupported : Result

        /** Its headers cannot be read: not the format its name says, or damaged. */
        data object Undecodable : Result

        data class OutOfRange(
            val durationMs: Long,
        ) : Result
    }

    /**
     * Copies [source] (a file named like [fileName], which gives the format and the first label) into [drafts]'s media
     * and returns the new version. Nothing is kept unless it is [Result.Added]. Blocking IO.
     */
    fun import(
        drafts: DraftStore,
        source: File,
        fileName: String,
    ): Result {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val format = FileFormat.of("f.$extension")
        if (format?.kind != FileKind.AUDIO) return Result.Unsupported
        val target = drafts.newMediaFile("ringtone", extension)
        source.copyTo(target, overwrite = true)
        val durationMs =
            runCatching { FileInput(target.toPath()).use { AudioProbe.durationMs(format, it) } }.getOrNull()
        val tolerance = Limits.DURATION_TOLERANCE_MS
        val result =
            when {
                durationMs == null -> {
                    Result.Undecodable
                }

                durationMs < Limits.RINGTONE_MIN_MS - tolerance || durationMs > Limits.RINGTONE_MAX_MS + tolerance -> {
                    Result.OutOfRange(durationMs)
                }

                else -> {
                    val label = label(fileName.substringBeforeLast('.'))
                    Result.Added(
                        DraftRingtone(target.name, Ringtone(target.name, label = label), durationMs = durationMs),
                    )
                }
            }
        if (result !is Result.Added) target.delete()
        return result
    }

    /** "pond-song_2" becomes "Pond song 2", as packer labels files. */
    fun label(fileStem: String): String? =
        fileStem
            .replace(Regex("[-_]+"), " ")
            .trim()
            .replaceFirstChar { it.uppercase() }
            .take(MAX_LABEL)
            .ifEmpty { null }

    private const val MAX_LABEL = 80
}
