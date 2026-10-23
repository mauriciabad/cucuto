package app.mauri.cucuto.core.pack.media

import app.mauri.cucuto.packspec.DurationRules
import app.mauri.cucuto.packspec.ErrorCode
import app.mauri.cucuto.packspec.FileFormat
import app.mauri.cucuto.packspec.FileKind
import app.mauri.cucuto.packspec.Issue
import app.mauri.cucuto.packspec.Limits
import app.mauri.cucuto.packspec.MediaReference
import app.mauri.cucuto.packspec.MediaRole
import java.io.File

/**
 * The importer's extra media stage (docs/pack-format.md#validation-stages): decodes one unpacked file with the
 * platform probes and reports the same codes as the header checks (MEDIA_UNDECODABLE, the duration codes,
 * LOOP_POINTS_INVALID, IMAGE_TOO_LARGE).
 */
class MediaVerifier(
    private val audio: AudioProbe,
    private val image: ImageProbe,
) {
    /**
     * Verifies [file], found at [path] in the archive and used by [references] of the manifest [manifestFile].
     * Never throws for bad media.
     */
    fun verify(
        file: File,
        path: String,
        format: FileFormat,
        references: List<MediaReference>,
        manifestFile: String,
    ): List<Issue> =
        when (format.kind) {
            FileKind.AUDIO -> verifyAudio(file, path, format, references, manifestFile)
            FileKind.IMAGE -> verifyImage(file, path, format)
            else -> emptyList()
        }

    private fun verifyAudio(
        file: File,
        path: String,
        format: FileFormat,
        references: List<MediaReference>,
        manifestFile: String,
    ): List<Issue> {
        val full = references.any { it.role == MediaRole.CLIP }
        return when (
            val r =
                safely { audio.probe(file, format, full) } ?: AudioProbeResult.Undecodable("Decoder crashed")
        ) {
            is AudioProbeResult.Undecodable -> listOf(undecodable(path, format, r.reason))
            is AudioProbeResult.Decoded -> DurationRules.check(path, r.durationMs, references, manifestFile)
        }
    }

    private fun verifyImage(
        file: File,
        path: String,
        format: FileFormat,
    ): List<Issue> =
        when (val r = safely { image.probe(file, format) } ?: ImageProbeResult.Undecodable("Decoder crashed")) {
            is ImageProbeResult.Undecodable -> {
                listOf(undecodable(path, format, r.reason))
            }

            is ImageProbeResult.NotChecked -> {
                emptyList()
            }

            is ImageProbeResult.Decoded -> {
                val max = Limits.MAX_BITMAP_SIDE_PX
                if (r.width > max || r.height > max) {
                    listOf(
                        Issue(
                            ErrorCode.IMAGE_TOO_LARGE,
                            "${r.width}x${r.height} px; at most $max px per side",
                            file = path,
                        ),
                    )
                } else {
                    emptyList()
                }
            }
        }

    private fun undecodable(
        path: String,
        format: FileFormat,
        reason: String,
    ) = Issue(ErrorCode.MEDIA_UNDECODABLE, "This device cannot decode the ${format.name} file: $reason", file = path)

    private inline fun <T> safely(block: () -> T): T? =
        try {
            block()
        } catch (e: Exception) {
            null
        }
}
