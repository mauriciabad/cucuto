package app.mauri.cucuto.core.creator

import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Where the creator keeps the pack being made, in app-private storage (never shared, excluded from Android backups
 * like the rest of `files/`):
 *
 * ```
 * files/creator/
 * ├── draft/
 * │   ├── draft.json      the CreatorDraft, replaced atomically on every save
 * │   └── media/          cropped sprites and covers, encoded clips (file names from the draft)
 * └── author.txt          the last author name, to prefill the next pack
 * ```
 *
 * One draft at a time. All methods do blocking IO: call them off the main thread.
 */
class DraftStore(
    val root: File,
) {
    private val draftDir get() = File(root, "draft")
    val mediaDir: File get() = File(draftDir, "media")
    private val draftFile get() = File(draftDir, "draft.json")
    private val authorFile get() = File(root, "author.txt")
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    /** The saved draft, or null when there is none (or it cannot be read). */
    fun load(): CreatorDraft? =
        try {
            draftFile.takeIf { it.isFile }?.readText()?.let { json.decodeFromString<CreatorDraft>(it) }
        } catch (e: Exception) {
            null
        }

    /** Saves [draft], replacing the previous one atomically. */
    fun save(draft: CreatorDraft) {
        draftDir.mkdirs()
        val tmp = File(draftDir, "draft.json.tmp")
        tmp.writeText(json.encodeToString(CreatorDraft.serializer(), draft))
        if (!tmp.renameTo(draftFile)) {
            tmp.delete()
            throw IOException("Cannot save the draft")
        }
        if (draft.author.isNotBlank()) runCatching { authorFile.writeText(draft.author.trim()) }
    }

    /**
     * Deletes media files [draft] no longer uses (a replaced sprite, a removed clip). Call it when no media is being
     * written: when the creator opens.
     */
    fun prune(draft: CreatorDraft) {
        val keep = draft.mediaFiles()
        mediaDir
            .listFiles()
            .orEmpty()
            .filter { it.name !in keep }
            .forEach { it.delete() }
    }

    /** A new, unused file in the media folder, `<prefix>-<random>.<extension>`. */
    fun newMediaFile(
        prefix: String,
        extension: String,
    ): File {
        mediaDir.mkdirs()
        return File(mediaDir, "$prefix-${UUID.randomUUID().toString().take(UNIQUE_LENGTH)}.$extension")
    }

    /** The file of a media name from the draft. */
    fun media(name: String): File = File(mediaDir, name)

    /** Deletes the draft and its media. */
    fun clear() {
        draftDir.deleteRecursively()
    }

    /** The author of the last saved draft, to prefill a new one. */
    fun lastAuthor(): String = runCatching { authorFile.readText().trim() }.getOrDefault("")

    companion object {
        private const val UNIQUE_LENGTH = 13

        fun inFilesDir(filesDir: File) = DraftStore(File(filesDir, "creator"))
    }
}
