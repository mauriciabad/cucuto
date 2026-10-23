package app.mauri.cucuto.diagnostics

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The Diagnostics test sound: a 700 ms two-tone chirp (a rising fifth), synthesised as 16-bit PCM WAV at first use.
 * Generated rather than committed, so no audio file enters the repository, and unmistakably not a stock tone.
 */
object SelfTestSound {
    private const val SAMPLE_RATE = 44_100
    private const val DURATION_MS = 700
    private const val DIR = "selftest"
    private const val FILE = "chirp.wav"

    /** The chirp in `files/selftest/chirp.wav`, written atomically if missing. Null when it cannot be written. */
    fun ensureFile(filesDir: File): File? =
        try {
            val dir = File(filesDir, DIR).apply { mkdirs() }
            val file = File(dir, FILE)
            if (!file.isFile || file.length() == 0L) {
                val tmp = File(dir, "$FILE.tmp")
                tmp.writeBytes(wav())
                if (!tmp.renameTo(file)) throw IOException("rename failed")
            }
            file
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }

    /** The complete WAV file: [tones] are the first and second note in Hz. */
    fun wav(tones: Pair<Double, Double> = 660.0 to 990.0): ByteArray {
        val samples = SAMPLE_RATE * DURATION_MS / 1000
        val dataSize = samples * 2
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVE".toByteArray())
        buffer
            .put("fmt ".toByteArray())
            .putInt(16)
            .putShort(1) // PCM
            .putShort(1) // mono
            .putInt(SAMPLE_RATE)
            .putInt(SAMPLE_RATE * 2)
            .putShort(2)
            .putShort(16)
        buffer.put("data".toByteArray()).putInt(dataSize)
        for (i in 0 until samples) {
            val t = i.toDouble() / SAMPLE_RATE
            val frequency = if (t < 0.30) tones.first else tones.second
            val envelope = exp(-3.0 * (t % 0.30))
            val value = sin(2.0 * PI * frequency * t) * envelope * 0.6
            buffer.putShort((value * Short.MAX_VALUE).toInt().toShort())
        }
        return buffer.array()
    }
}
