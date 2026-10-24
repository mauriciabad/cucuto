package app.mauri.cucuto.core.audio

import app.mauri.cucuto.packspec.FileFormat
import app.mauri.cucuto.packspec.authoring.Authoring
import org.junit.Assert.assertEquals
import org.junit.Test

class EncoderSettingsTest {
    @Test
    fun `clips are Ogg Opus, mono, 48 kHz at packer's 96 kbps`() {
        val opus = EncoderSettings.OPUS
        assertEquals(
            mapOf(
                "mime" to "audio/opus",
                "sample-rate" to 48_000,
                "channel-count" to 1,
                "bitrate" to 96_000,
                "pcm-encoding" to 2, // AudioFormat.ENCODING_PCM_16BIT
                "max-input-size" to 9_600,
            ),
            opus.formatEntries(),
        )
        assertEquals(EncoderSettings.Container.OGG, opus.container)
        assertEquals(4, opus.container.muxerFormat) // MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
        assertEquals("ogg", opus.extension)
        assertEquals(FileFormat.OGG, FileFormat.of("clip.${opus.extension}"))
        assertEquals(Authoring.Audio.CLIP_BITRATE_BPS, opus.bitrate)
    }

    @Test
    fun `the fallback is AAC-LC in MP4`() {
        val aac = EncoderSettings.AAC
        assertEquals("audio/mp4a-latm", aac.formatEntries()["mime"])
        assertEquals(2, aac.formatEntries()["aac-profile"]) // MediaCodecInfo.CodecProfileLevel.AACObjectLC
        assertEquals(0, aac.container.muxerFormat) // MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        assertEquals(FileFormat.M4A, FileFormat.of("clip.${aac.extension}"))
        assertEquals(listOf(EncoderSettings.OPUS, EncoderSettings.AAC), EncoderSettings.PREFERENCE)
    }

    @Test
    fun `16-bit conversion clips instead of wrapping`() {
        val shorts = PcmAudio.toShorts(floatArrayOf(0f, 0.5f, 1f, 1.5f, -1f, -2f))
        assertEquals(listOf<Short>(0, 16_383, 32_767, 32_767, -32_767, -32_768), shorts.toList())
    }

    @Test
    fun `pcm slices convert milliseconds to frames`() {
        val audio = PcmAudio(ShortArray(48_000) { 16_384 }, 48_000)
        assertEquals(1_000, audio.durationMs)
        assertEquals(24_000, audio.slice(250, 750).size)
        assertEquals(0.5f, audio.slice(0, 10)[0])
        assertEquals(0, audio.slice(900, 100).size)
        assertEquals(48_000, audio.frameAt(5_000))
    }
}
