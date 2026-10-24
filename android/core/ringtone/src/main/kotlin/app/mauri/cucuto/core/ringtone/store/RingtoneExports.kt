package app.mauri.cucuto.core.ringtone.store

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** A ring file Cucuto exported: its content URI (what goes into `CUSTOM_RINGTONE`) and its file name. */
data class RingExport(
    val uri: String,
    val displayName: String,
)

/** Where ring files live so that Telecom (in `system_server`) can open them. */
interface RingtoneExports {
    /** Every ring file Cucuto exported and still owns. */
    suspend fun list(): List<RingExport>

    /** Publishes [file] as [displayName] with [title] (what the Contacts app shows); returns its URI. */
    suspend fun publish(
        file: File,
        displayName: String,
        title: String,
        mimeType: String,
    ): String

    suspend fun delete(uri: String)
}

/**
 * Ring files in the shared `Ringtones/cucuto/` folder through MediaStore (brief, Ringtones; ADR 0023 decision 1,
 * ADR 0039). Telecom opens a contact's ringtone with a local `MediaPlayer` in `system_server`, which reads MediaStore
 * URIs through MediaProvider (always running), so ringing never starts Cucuto (unlike a provider URI, M9 report §3.3).
 *
 * Writing its own files to a MediaStore collection needs no permission (scoped storage), and an app sees its own files
 * without a media permission, so [list] only ever returns Cucuto's files. The URI stored on contacts uses the
 * `external` volume name, the form the system ringtone picker writes.
 */
@Singleton
class MediaStoreRingtoneExports
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : RingtoneExports {
        private val resolver: ContentResolver get() = context.contentResolver
        private val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        override suspend fun list(): List<RingExport> {
            val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME)
            val selection =
                "${MediaStore.Audio.Media.RELATIVE_PATH} = ? AND ${MediaStore.Audio.Media.OWNER_PACKAGE_NAME} = ?"
            val args = arrayOf(RELATIVE_PATH, context.packageName)
            return resolver
                .query(collection, projection, selection, args, null)
                ?.use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(RingExport(contactUri(cursor.getLong(0)), cursor.getString(1).orEmpty()))
                        }
                    }
                }.orEmpty()
        }

        override suspend fun publish(
            file: File,
            displayName: String,
            title: String,
            mimeType: String,
        ): String {
            val values =
                ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Audio.Media.TITLE, title)
                    put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, RELATIVE_PATH)
                    put(MediaStore.Audio.Media.IS_RINGTONE, 1)
                    put(MediaStore.Audio.Media.IS_NOTIFICATION, 0)
                    put(MediaStore.Audio.Media.IS_ALARM, 0)
                    put(MediaStore.Audio.Media.IS_MUSIC, 0)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
            val item = resolver.insert(collection, values) ?: throw IOException("MediaStore refused $displayName")
            try {
                resolver.openOutputStream(item, "w")?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    ?: throw IOException("Cannot write $displayName")
                resolver.update(item, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(item, null, null) }
                throw e
            }
            return contactUri(ContentUris.parseId(item))
        }

        override suspend fun delete(uri: String) {
            val id = uri.substringAfterLast('/').toLongOrNull() ?: return
            runCatching { resolver.delete(ContentUris.withAppendedId(collection, id), null, null) }
        }

        private fun contactUri(id: Long): String =
            ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()

        companion object {
            /** `Ringtones/cucuto/`, the folder the brief names. */
            val RELATIVE_PATH: String = "${Environment.DIRECTORY_RINGTONES}/cucuto/"
        }
    }
