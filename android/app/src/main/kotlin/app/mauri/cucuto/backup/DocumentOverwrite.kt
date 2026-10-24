package app.mauri.cucuto.backup

import android.content.ContentResolver
import android.net.Uri
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/**
 * Replaces the whole content of an SAF document, whatever write modes its provider accepts (ADR 0040).
 *
 * `openFileDescriptor` modes are provider-specific (ContentResolver docs: "w" may or may not truncate). `"wt"` is
 * the documented truncating mode; Google Drive's provider has answered it with "Unsupported mode: wt" and accepts
 * `"rwt"`; `"w"` is the last resort. After writing, a regular file is cut to the written length, so a shorter backup
 * never keeps the old file's tail (which would break the zip). Cloud providers often hand out a pipe instead and
 * upload when it closes; a pipe is never truncated, and a failed copy closes it with an error so the provider can
 * drop the half-written upload.
 */
internal object DocumentOverwrite {
    val MODES = listOf("wt", "rwt", "w")

    /** Opens with the first of [MODES] that [open] accepts; returns that mode and what it opened. */
    fun <T : Any> openFirst(open: (String) -> T?): Pair<String, T> {
        var failure: Exception? = null
        for (mode in MODES) {
            try {
                open(mode)?.let { return mode to it }
            } catch (e: FileNotFoundException) {
                failure = e
            } catch (e: IllegalArgumentException) {
                failure = e
            } catch (e: UnsupportedOperationException) {
                failure = e
            }
        }
        throw IOException("The backup file can't be written", failure)
    }

    /** Writes [source] over the document at [uri]. Blocking: call on an IO dispatcher. */
    fun write(
        resolver: ContentResolver,
        uri: Uri,
        source: File,
    ) {
        val (_, descriptor) = openFirst { resolver.openFileDescriptor(uri, it) }
        try {
            val fd = descriptor.fileDescriptor
            // Not closed on its own: the ParcelFileDescriptor owns the descriptor and reports the outcome.
            val out = FileOutputStream(fd)
            val written = source.inputStream().use { it.copyTo(out) }
            out.flush()
            truncateRegularFile(fd, written)
        } catch (e: Exception) {
            runCatching { descriptor.closeWithError(e.message ?: e.javaClass.simpleName) }
            throw e
        }
        descriptor.close()
    }

    private fun truncateRegularFile(
        fd: FileDescriptor,
        length: Long,
    ) {
        try {
            if (OsConstants.S_ISREG(Os.fstat(fd).st_mode)) Os.ftruncate(fd, length)
        } catch (e: ErrnoException) {
            throw IOException("The backup file can't be trimmed", e)
        }
    }
}
