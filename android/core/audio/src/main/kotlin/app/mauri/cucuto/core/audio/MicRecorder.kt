package app.mauri.cucuto.core.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import app.mauri.cucuto.packspec.authoring.Authoring
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/**
 * Records the microphone for the pack creator: mono 16-bit PCM at 48 kHz (Opus's rate, so no resampling), up to
 * [maxDurationMs] (the longest clip a pack takes). One recording per instance.
 *
 * The caller asks for `RECORD_AUDIO` when the user taps Record, never before (brief: minimum intrusion); [record]
 * refuses to start without it. Nothing is written to disk: the audio stays in memory until the user keeps a clip.
 */
class MicRecorder(
    private val context: Context,
    private val maxDurationMs: Long = Authoring.Audio.MAX_RECORDING_MS,
) {
    private val stopRequested = AtomicBoolean(false)

    /** Ends the recording; [record] returns what it has. */
    fun stop() = stopRequested.set(true)

    /**
     * Records until [stop], the maximum length, or cancellation (which discards the audio). [onLevel] gets the peak
     * level (0..1) and the elapsed time about every [LEVEL_INTERVAL_MS], on a background thread.
     *
     * @throws SecurityException without the microphone permission; [IOException] when the microphone cannot start.
     */
    @SuppressLint("MissingPermission") // Checked right below, at the moment of recording.
    suspend fun record(onLevel: (level: Float, elapsedMs: Long) -> Unit = { _, _ -> }): PcmAudio =
        withContext(Dispatchers.IO) {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("RECORD_AUDIO is not granted")
            }
            val rate = Authoring.Audio.SAMPLE_RATE_HZ
            val minBuffer =
                AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) throw IOException("Recording at $rate Hz is not supported")
            val chunk = rate * LEVEL_INTERVAL_MS.toInt() / 1000
            val recorder =
                AudioRecord
                    .Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(
                        AudioFormat
                            .Builder()
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build(),
                    ).setBufferSizeInBytes(maxOf(minBuffer * 2, chunk * 4))
                    .build()
            try {
                if (recorder.state != AudioRecord.STATE_INITIALIZED) throw IOException("The microphone did not start")
                val maxFrames = (rate * maxDurationMs / 1000).toInt()
                val out = ShortArray(maxFrames)
                val buffer = ShortArray(chunk)
                var size = 0
                recorder.startRecording()
                while (size < maxFrames && !stopRequested.get()) {
                    coroutineContext.ensureActive()
                    val read = recorder.read(buffer, 0, minOf(chunk, maxFrames - size))
                    if (read < 0) throw IOException("Recording failed ($read)")
                    var peak = 0
                    for (i in 0 until read) {
                        out[size + i] = buffer[i]
                        peak = maxOf(peak, abs(buffer[i].toInt()))
                    }
                    size += read
                    onLevel(peak / PcmAudio.SHORT_SCALE, size * 1000L / rate)
                }
                PcmAudio(out.copyOf(size), rate)
            } finally {
                runCatching { recorder.stop() }
                recorder.release()
            }
        }

    companion object {
        const val LEVEL_INTERVAL_MS = 50L
    }
}
