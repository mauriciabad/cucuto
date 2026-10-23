package app.mauri.cucuto.core.pack

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Where the bytes of an import come from. Files picked with the Storage Access Framework, the share sheet, "Open
 * with" and finished repository downloads all end up here, so every pack goes through the same pipeline. Methods
 * block: the importer calls them on its IO dispatcher.
 */
interface ImportSource {
    /** File name and size, when the source knows them (for messages and progress). */
    fun describe(): Description

    /** Opens the bytes from the start. The importer reads the stream once and closes it. */
    fun open(): InputStream

    data class Description(
        val displayName: String?,
        val sizeBytes: Long?,
    )

    companion object {
        /** A `content://` or `file://` document: SAF pickers, the share sheet (`EXTRA_STREAM`) and "Open with". */
        fun of(
            contentResolver: ContentResolver,
            uri: Uri,
        ): ImportSource = UriImportSource(contentResolver, uri)

        /** A local file, such as a finished download in the app's cache. */
        fun of(file: File): ImportSource = FileImportSource(file)
    }
}

private class UriImportSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
) : ImportSource {
    override fun describe(): ImportSource.Description {
        var name: String? = null
        var size: Long? = null
        try {
            val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            resolver.query(uri, columns, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = c.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !c.isNull(nameIndex)) name = c.getString(nameIndex)
                    if (sizeIndex >= 0 && !c.isNull(sizeIndex)) size = c.getLong(sizeIndex)
                }
            }
        } catch (e: Exception) {
            // Some providers do not support queries; the name and size are only informative.
        }
        return ImportSource.Description(name ?: uri.lastPathSegment, size?.takeIf { it >= 0 })
    }

    override fun open(): InputStream = resolver.openInputStream(uri) ?: throw FileNotFoundException("Cannot open $uri")
}

private class FileImportSource(
    private val file: File,
) : ImportSource {
    override fun describe() = ImportSource.Description(file.name, file.length().takeIf { file.isFile })

    override fun open(): InputStream = file.inputStream()
}
