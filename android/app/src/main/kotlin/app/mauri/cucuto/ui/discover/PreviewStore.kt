package app.mauri.cucuto.ui.discover

import app.mauri.cucuto.core.repo.RepoResult
import app.mauri.cucuto.core.repo.RepositoryAccess
import app.mauri.cucuto.core.repo.RepositoryClient
import app.mauri.cucuto.packspec.FileFormat
import app.mauri.cucuto.packspec.FileKind
import app.mauri.cucuto.packspec.Limits
import app.mauri.cucuto.packspec.media.AudioProbe
import app.mauri.cucuto.packspec.media.ImageProbe
import app.mauri.cucuto.packspec.media.SvgSafety
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Covers, sprites and sample clips of repository entries, fetched only when Discover shows or plays them and kept in
 * the app's cache folder (Android may clear it; that only costs another fetch). Previews follow the same media rules
 * as pack files (docs/pack-format.md, "Pack repositories"): an allowed image or audio extension, a readable header,
 * bitmaps within the size limit and SVGs that pass pack-spec's safety check. Anything else is never written.
 */
class PreviewStore(
    private val client: RepositoryClient,
    private val dir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** URLs that failed in this process: not asked again until the app restarts. */
    private val failed = ConcurrentHashMap.newKeySet<String>()

    private val permits = Semaphore(MAX_PARALLEL_FETCHES)

    /**
     * The preview at [url] as a local file, fetched with [access]'s credentials when it is on the repository's
     * origin, or null when it cannot be fetched or breaks the media rules.
     */
    suspend fun file(
        url: HttpUrl,
        access: RepositoryAccess?,
    ): File? {
        val key = url.toString()
        if (key in failed) return null
        val format = previewFormat(url) ?: return null.also { failed += key }
        val target = File(dir, "${sha256(key)}.${format.extensions.first()}")
        if (target.isFile) return target
        return locks.computeIfAbsent(key) { Mutex() }.withLock {
            if (target.isFile) return@withLock target
            val bytes =
                when (val result = permits.withPermit { client.fetchPreview(url, access) }) {
                    is RepoResult.Success -> result.value
                    is RepoResult.Failure -> null
                }
            if (bytes == null || !isAcceptable(format, bytes)) {
                failed += key
                return@withLock null
            }
            withContext(ioDispatcher) {
                try {
                    if (!dir.isDirectory) dir.mkdirs()
                    val tmp = File(dir, "${target.name}.tmp")
                    tmp.writeBytes(bytes)
                    if (tmp.renameTo(target)) target else null.also { tmp.delete() }
                } catch (e: IOException) {
                    null
                }
            }
        }
    }

    companion object {
        private const val MAX_PARALLEL_FETCHES = 4

        /** The preview's format from its URL path, if it is an image or audio format packs may use. */
        fun previewFormat(url: HttpUrl): FileFormat? =
            FileFormat.of(url.encodedPath)?.takeIf { it.kind == FileKind.IMAGE || it.kind == FileKind.AUDIO }

        /** The pack media rules, applied to a preview's bytes. */
        fun isAcceptable(
            format: FileFormat,
            bytes: ByteArray,
        ): Boolean =
            when {
                bytes.size > format.maxBytes -> {
                    false
                }

                format == FileFormat.SVG -> {
                    SvgSafety.check(bytes) == SvgSafety.Result.Safe
                }

                format.isBitmap -> {
                    val size = ImageProbe.size(format, bytes)
                    size != null && size.width <= Limits.MAX_BITMAP_SIDE_PX && size.height <= Limits.MAX_BITMAP_SIDE_PX
                }

                format.kind == FileKind.AUDIO -> {
                    AudioProbe.durationMs(format, bytes) != null
                }

                else -> {
                    false
                }
            }

        private fun sha256(text: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(text.encodeToByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
