package app.mauri.cucuto.spike.m9.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Read-only ringtone provider for the M9 spike:
 * `content://app.mauri.cucuto.spike.m9.ring/ring/spike_ring.wav`.
 *
 * It answers one question for the optional Telecom hook: when `Call.getRingtone()` returns an app
 * provider URI, who opens it (static analysis: system_server, uid 1000, through a local MediaPlayer
 * in `Ringtone.createLocalMediaPlayer`), and does it open in time when this app is cold.
 *
 * Every open logs the caller and the time since this process started. The tone is synthesised at
 * runtime (no audio file is committed): a 3 s two-note "ring-ring", which Telecom loops.
 */
class RingProvider : ContentProvider() {

    private companion object {
        const val TAG = "cucuto-m9"
        const val SAMPLE_RATE = 44_100
    }

    private val createdAt = System.nanoTime()

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "audio/wav"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        Log.i(
            TAG,
            "RING_OPEN uri=$uri mode=$mode callingUid=${Binder.getCallingUid()} callingPid=${Binder.getCallingPid()} " +
                "callingPackage=${callingPackageUnchecked ?: "<none>"} sinceProcessStartMs=${(System.nanoTime() - createdAt) / 1_000_000}",
        )
        if (mode != "r") throw SecurityException("read-only provider")
        return ParcelFileDescriptor.open(ensureTone(), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor =
        AssetFileDescriptor(openFile(uri, mode), 0, AssetFileDescriptor.UNKNOWN_LENGTH)

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val file = ensureTone()
        return MatrixCursor(cols).apply {
            addRow(
                Array<Any?>(cols.size) { i ->
                    when (cols[i]) {
                        OpenableColumns.DISPLAY_NAME -> "Cucuto M9 spike ring"
                        OpenableColumns.SIZE -> file.length()
                        else -> null
                    }
                },
            )
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read-only")

    private fun ensureTone(): File {
        val file = File(requireNotNull(context).cacheDir, "spike_ring.wav")
        if (!file.exists() || file.length() == 0L) writeWav(file, synthesise())
        return file
    }

    /** Two 0.4 s bursts of a 520/780 Hz dyad, then silence, over 3 s. */
    private fun synthesise(): ShortArray {
        val n = SAMPLE_RATE * 3
        return ShortArray(n) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            val inBurst = t < 0.4 || (t >= 0.6 && t < 1.0)
            if (!inBurst) {
                0
            } else {
                val local = if (t < 0.4) t else t - 0.6
                val fade = min(1.0, min(local, 0.4 - local) / 0.02)
                val v = (sin(2 * PI * 520 * t) + sin(2 * PI * 780 * t)) * 0.3 * fade
                (v * Short.MAX_VALUE).toInt().toShort()
            }
        }
    }

    private fun writeWav(file: File, samples: ShortArray) {
        val dataSize = samples.size * 2
        DataOutputStream(FileOutputStream(file).buffered()).use { out ->
            fun le32(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun le16(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            out.writeBytes("RIFF")
            le32(36 + dataSize)
            out.writeBytes("WAVEfmt ")
            le32(16)
            le16(1) // PCM
            le16(1) // mono
            le32(SAMPLE_RATE)
            le32(SAMPLE_RATE * 2)
            le16(2)
            le16(16)
            out.writeBytes("data")
            le32(dataSize)
            for (s in samples) le16(s.toInt())
        }
    }
}
