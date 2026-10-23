package app.mauri.cucuto.spike.debugapp

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
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Read-only clip provider for the M0 spike: `content://app.mauri.cucuto.spike.clips/clip/<name>`.
 *
 * Two questions this answers for the production module (issue #11):
 *  1. Can the system's notification sound player read a clip out of our app at all?
 *  2. **Which UID/package opens it**, so the production provider can allowlist exactly that caller
 *     instead of being world-readable.
 *
 * Every `openFile` logs the caller. Static analysis says it will be SystemUI (it hosts
 * `IRingtonePlayer`, which `NotificationAttentionHelper.playSound` calls) — the log confirms it.
 *
 * The clip itself is **synthesised at runtime**, never committed: a short two-tone chirp written
 * as a 16-bit PCM WAV. That keeps the repo free of any audio file and sidesteps the media-licence
 * guard entirely.
 */
class ClipProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "app.mauri.cucuto.spike.clips"
        private const val TAG = "cucuto-m0"

        private const val SAMPLE_RATE = 44_100

        /**
         * Spike default: log the caller but serve anyone, so a mismatch shows up as a wrong-UID
         * log line rather than as silence we would have to debug blind.
         *
         * The production provider must invert this: allowlist the ringtone player's package and
         * reject everything else. See docs/m0-report.md.
         */
        private const val ENFORCE_CALLER = false

        /** Callers the production module is expected to allow. */
        private val EXPECTED_CALLERS = setOf("com.android.systemui", "android")
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "audio/wav"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val callingUid = Binder.getCallingUid()
        val callingPkg = callingPackage ?: callingPackageUnchecked ?: "<unknown>"
        Log.i(
            TAG,
            "CLIP_OPEN uri=$uri mode=$mode callingUid=$callingUid callingPid=${Binder.getCallingPid()} " +
                "callingPackage=$callingPkg expected=${callingPkg in EXPECTED_CALLERS}",
        )

        if (mode != "r") throw SecurityException("read-only provider")
        if (ENFORCE_CALLER && callingPkg !in EXPECTED_CALLERS) {
            throw SecurityException("caller not allowlisted: $callingPkg ($callingUid)")
        }

        val name = uri.lastPathSegment ?: throw IllegalArgumentException("no clip name")
        val file = ensureClip(name)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        val pfd = openFile(uri, mode) ?: return null
        return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    /** Some players stat the URI before opening it; answer the openable columns. */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val name = uri.lastPathSegment ?: "clip.wav"
        val file = ensureClip(name)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply {
            val row: Array<Any?> = Array(cols.size) { i ->
                when (cols[i]) {
                    OpenableColumns.DISPLAY_NAME -> name
                    OpenableColumns.SIZE -> file.length()
                    else -> null
                }
            }
            addRow(row)
        }
    }

    // Read-only: everything else is refused.
    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read-only")

    // ------------------------------------------------------------ clip synthesis

    private fun ensureClip(name: String): File {
        val safe = name.substringAfterLast('/').ifEmpty { "clip.wav" }
        val dir = File(requireNotNull(context).cacheDir, "clips").apply { mkdirs() }
        val file = File(dir, safe)
        if (!file.exists() || file.length() == 0L) {
            writeWav(file, synthesise())
        }
        return file
    }

    /** A 700 ms two-tone chirp with an exponential decay — obviously not a stock Android tone. */
    private fun synthesise(): ShortArray {
        val durationMs = 700
        val n = SAMPLE_RATE * durationMs / 1000
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            // Two notes: a rising fifth, so it is unmistakable in a test.
            val f = if (t < 0.30) 660.0 else 990.0
            val envelope = exp(-3.0 * (t % 0.30))
            val v = sin(2.0 * PI * f * t) * envelope * 0.6
            out[i] = (v * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun writeWav(file: File, samples: ShortArray) {
        val dataSize = samples.size * 2
        FileOutputStream(file).use { fos ->
            fos.write("RIFF".toByteArray())
            fos.write(intLe(36 + dataSize))
            fos.write("WAVE".toByteArray())
            fos.write("fmt ".toByteArray())
            fos.write(intLe(16))
            fos.write(shortLe(1))            // PCM
            fos.write(shortLe(1))            // mono
            fos.write(intLe(SAMPLE_RATE))
            fos.write(intLe(SAMPLE_RATE * 2))
            fos.write(shortLe(2))            // block align
            fos.write(shortLe(16))           // bits per sample
            fos.write("data".toByteArray())
            fos.write(intLe(dataSize))
            val bytes = ByteArray(dataSize)
            for (i in samples.indices) {
                bytes[i * 2] = (samples[i].toInt() and 0xFF).toByte()
                bytes[i * 2 + 1] = ((samples[i].toInt() shr 8) and 0xFF).toByte()
            }
            fos.write(bytes)
        }
    }

    private fun intLe(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte(),
    )

    private fun shortLe(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
}
