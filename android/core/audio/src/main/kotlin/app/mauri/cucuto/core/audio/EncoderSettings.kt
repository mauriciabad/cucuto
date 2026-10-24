package app.mauri.cucuto.core.audio

import android.media.AudioFormat
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import app.mauri.cucuto.packspec.FileFormat
import app.mauri.cucuto.packspec.authoring.Authoring

/**
 * How the creator encodes a clip with the platform encoder: Ogg Opus at packer's clip settings, or AAC in MP4 when a
 * device has no Opus encoder or its Ogg output does not check out (both are pack formats; brief: "never force a
 * format", Opus recommended).
 */
data class EncoderSettings(
    val mime: String,
    val container: Container,
    val sampleRate: Int,
    val channels: Int,
    val bitrate: Int,
    /** File extension of the result, without the dot. */
    val extension: String,
    /** The pack format the result must parse as. */
    val format: FileFormat,
    /** AAC profile, for AAC only. */
    val aacProfile: Int? = null,
) {
    enum class Container(
        val muxerFormat: Int,
    ) {
        OGG(MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG),
        MPEG_4(MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4),
    }

    /** The keys of the encoder's [MediaFormat]. A map, so tests can check it without the Android runtime. */
    fun formatEntries(): Map<String, Any> =
        buildMap {
            put(MediaFormat.KEY_MIME, mime)
            put(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
            put(MediaFormat.KEY_CHANNEL_COUNT, channels)
            put(MediaFormat.KEY_BIT_RATE, bitrate)
            put(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            put(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
            aacProfile?.let { put(MediaFormat.KEY_AAC_PROFILE, it) }
        }

    /** The [MediaFormat] to configure the encoder with. */
    fun mediaFormat(): MediaFormat =
        MediaFormat().apply {
            for ((key, value) in formatEntries()) {
                when (value) {
                    is Int -> setInteger(key, value)
                    is String -> setString(key, value)
                }
            }
        }

    companion object {
        /** 100 ms of 16-bit mono at 48 kHz per input buffer. */
        const val MAX_INPUT_BYTES = Authoring.Audio.SAMPLE_RATE_HZ / 10 * 2

        /** The default: Ogg Opus, mono, 48 kHz, 96 kb/s (packer's `--opus` for clips). */
        val OPUS =
            EncoderSettings(
                mime = MediaFormat.MIMETYPE_AUDIO_OPUS,
                container = Container.OGG,
                sampleRate = Authoring.Audio.SAMPLE_RATE_HZ,
                channels = Authoring.Audio.CHANNELS,
                bitrate = Authoring.Audio.CLIP_BITRATE_BPS,
                extension = "ogg",
                format = FileFormat.OGG,
            )

        /** The fallback: AAC-LC in MP4 (`.m4a`), mono, 48 kHz, 128 kb/s (AAC needs more bits than Opus for the same quality). */
        val AAC =
            EncoderSettings(
                mime = MediaFormat.MIMETYPE_AUDIO_AAC,
                container = Container.MPEG_4,
                sampleRate = Authoring.Audio.SAMPLE_RATE_HZ,
                channels = Authoring.Audio.CHANNELS,
                bitrate = 128_000,
                extension = "m4a",
                format = FileFormat.M4A,
                aacProfile = MediaCodecInfo.CodecProfileLevel.AACObjectLC,
            )

        /** Tried in this order. */
        val PREFERENCE: List<EncoderSettings> = listOf(OPUS, AAC)
    }
}
