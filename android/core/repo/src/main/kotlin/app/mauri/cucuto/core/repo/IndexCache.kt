package app.mauri.cucuto.core.repo

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** The last index body fetched for a repository and its `ETag`, for conditional requests. */
class CachedIndex(
    /** The registered index URL (the cache key). */
    val url: String,
    /** The URL after redirects, the base for relative references. */
    val finalUrl: String,
    val etag: String?,
    val body: ByteArray,
)

/** Stores [CachedIndex]es. Implementations block; [RepositoryClient] calls them on its IO dispatcher. */
interface IndexCache {
    fun read(url: String): CachedIndex?

    fun write(index: CachedIndex)

    fun remove(url: String)
}

/**
 * An [IndexCache] in a folder (the app's cache directory: Android may clear it, which only costs one full fetch).
 * Each repository has `<sha256 of url>.body` and `<sha256 of url>.json` (URL, final URL, ETag), each written to a
 * temporary file and renamed, metadata last, so a torn write reads as a cache miss.
 */
class FileIndexCache(
    private val dir: File,
) : IndexCache {
    @Serializable
    private data class Meta(
        val url: String,
        val finalUrl: String,
        val etag: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    override fun read(url: String): CachedIndex? {
        val key = key(url)
        return try {
            val meta = json.decodeFromString(Meta.serializer(), File(dir, "$key.json").readText())
            if (meta.url != url) return null
            CachedIndex(meta.url, meta.finalUrl, meta.etag, File(dir, "$key.body").readBytes())
        } catch (e: IOException) {
            null
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    @Synchronized
    override fun write(index: CachedIndex) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
        val key = key(index.url)
        File(dir, "$key.json").delete()
        writeAtomically(File(dir, "$key.body"), index.body)
        val meta = json.encodeToString(Meta.serializer(), Meta(index.url, index.finalUrl, index.etag))
        writeAtomically(File(dir, "$key.json"), meta.encodeToByteArray())
    }

    @Synchronized
    override fun remove(url: String) {
        val key = key(url)
        File(dir, "$key.json").delete()
        File(dir, "$key.body").delete()
    }

    private fun key(url: String): String = MessageDigest.getInstance("SHA-256").digest(url.encodeToByteArray()).toHex()
}

internal fun writeAtomically(
    target: File,
    bytes: ByteArray,
) {
    val tmp = File(target.parentFile, "${target.name}.tmp")
    tmp.writeBytes(bytes)
    if (!tmp.renameTo(target)) {
        tmp.delete()
        throw IOException("Cannot replace $target")
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
