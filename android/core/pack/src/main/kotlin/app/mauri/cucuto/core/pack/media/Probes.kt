package app.mauri.cucuto.core.pack.media

import app.mauri.cucuto.packspec.FileFormat
import java.io.File

/**
 * Test-decodes audio with the device's decoders, on top of the header checks of pack-spec. Implementations block and
 * must not throw for bad input: they return [AudioProbeResult.Undecodable].
 */
fun interface AudioProbe {
    /**
     * Decodes [file]. With [full] (clips) every frame is decoded; otherwise (ringtones, up to 6 min) only the first and
     * last seconds are, which proves the decoder accepts the stream without spending seconds per file.
     */
    fun probe(
        file: File,
        format: FileFormat,
        full: Boolean,
    ): AudioProbeResult

    /**
     * A probe for one worker that decodes files one after another and may keep decoders between them (#279, ADR
     * 0077). Close it when done. The default has nothing to keep and probes with [probe].
     */
    fun session(): Session = Session { file, format, full -> probe(file, format, full) }

    /** One worker's probe: not thread-safe, used for one file at a time, closed at the end. */
    fun interface Session : AutoCloseable {
        /** The same as [AudioProbe.probe]. */
        fun probe(
            file: File,
            format: FileFormat,
            full: Boolean,
        ): AudioProbeResult

        override fun close() {}
    }
}

sealed interface AudioProbeResult {
    /** Decoded; [durationMs] is checked against the clip and ringtone ranges again. */
    data class Decoded(
        val durationMs: Long,
    ) : AudioProbeResult

    data class Undecodable(
        val reason: String,
    ) : AudioProbeResult
}

/**
 * Test-decodes images with the device's decoders. Implementations block and must not throw for bad input: they
 * return [ImageProbeResult.Undecodable].
 */
fun interface ImageProbe {
    fun probe(
        file: File,
        format: FileFormat,
    ): ImageProbeResult
}

sealed interface ImageProbeResult {
    data class Decoded(
        val width: Int,
        val height: Int,
    ) : ImageProbeResult

    data class Undecodable(
        val reason: String,
    ) : ImageProbeResult

    /** The platform has no decoder for this format; the header checks of pack-spec are all that ran. */
    data class NotChecked(
        val reason: String,
    ) : ImageProbeResult
}
