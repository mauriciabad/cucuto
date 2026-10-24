package app.mauri.cucuto.core.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Plays part of a [PcmAudio] while the user trims a clip, reporting the playhead so the waveform can follow it. Media
 * audio attributes: the editor is a media task, unlike the finished clip's preview, which plays as a notification.
 * Cancel the calling coroutine to stop.
 */
class PcmPlayer {
    /** Plays [audio] from [startMs] to [endMs]; [onPosition] gets the playhead in milliseconds of [audio]. */
    suspend fun play(
        audio: PcmAudio,
        startMs: Long,
        endMs: Long,
        onPosition: (Long) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val from = audio.frameAt(startMs)
        val to = audio.frameAt(endMs)
        if (to <= from) return@withContext
        val minBuffer =
            AudioTrack.getMinBufferSize(audio.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track =
            AudioTrack
                .Builder()
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                ).setAudioFormat(
                    AudioFormat
                        .Builder()
                        .setSampleRate(audio.sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                ).setBufferSizeInBytes(maxOf(minBuffer, audio.sampleRate / 5 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        try {
            track.play()
            var offset = from
            val chunk = audio.sampleRate / CHUNKS_PER_SECOND
            while (offset < to && isActive) {
                val written = track.write(audio.samples, offset, minOf(chunk, to - offset), AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                offset += written
                onPosition(audio.durationAt(from + track.playbackHeadPosition))
            }
            val frames = to - from
            // Let the buffered tail play out (with a deadline, in case a device never reports the last frames).
            val deadline = System.nanoTime() + DRAIN_TIMEOUT_NANOS
            while (isActive && track.playbackHeadPosition < frames && System.nanoTime() < deadline) {
                onPosition(audio.durationAt(from + track.playbackHeadPosition))
                delay(POLL_MS)
            }
            onPosition(audio.durationAt(to))
        } finally {
            runCatching {
                track.pause()
                track.flush()
            }
            track.release()
        }
    }

    private fun PcmAudio.durationAt(frame: Int): Long = frame * PcmAudio.MILLIS / sampleRate

    private companion object {
        const val CHUNKS_PER_SECOND = 20
        const val POLL_MS = 16L
        const val DRAIN_TIMEOUT_NANOS = 2_000_000_000L
    }
}
