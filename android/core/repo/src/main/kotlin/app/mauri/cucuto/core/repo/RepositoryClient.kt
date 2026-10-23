package app.mauri.cucuto.core.repo

import app.mauri.cucuto.core.pack.PackImporter
import app.mauri.cucuto.packspec.DocumentKind
import app.mauri.cucuto.packspec.DocumentValidator
import app.mauri.cucuto.packspec.Limits
import app.mauri.cucuto.packspec.RepositoryIndex
import app.mauri.cucuto.packspec.SpecJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionSpec
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Caps for remote content. Everything remote is untrusted (docs/project-brief.md, "Pack repositories"). */
data class RepoLimits(
    val maxRedirects: Int = 5,
    /** The shared validator's cap for standalone indexes (16 MiB). */
    val maxIndexBytes: Long = DocumentValidator.REPOSITORY_BYTES,
    /** Covers, sprites and sample clips. */
    val maxPreviewBytes: Long = Limits.BITMAP_FILE_BYTES,
    /** The importer's own cap: anything larger cannot be a valid pack or bundle. */
    val maxDownloadBytes: Long = PackImporter.MAX_ARCHIVE_BYTES,
)

/** Progress of a download: [bytes] of [total] (null when unknown). */
fun interface DownloadProgress {
    suspend fun onProgress(
        bytes: Long,
        total: Long?,
    )
}

/** A verified file on disk. */
data class DownloadedFile(
    val file: File,
    val size: Long,
    /** Lowercase hex SHA-256 of [file], what installed packs and backups record. */
    val sha256: String,
)

/** A fresh or unchanged index. */
data class IndexUpdate(
    val snapshot: RepositorySnapshot,
    /** True when the server answered 304 and the cached copy was used. */
    val notModified: Boolean,
)

/**
 * HTTPS client for pack repositories (docs/decisions/0011-repository-client-and-credentials.md).
 *
 * - **HTTPS only**, at every hop: a plain `http://` start, redirect or reference fails with [RepoError.InsecureUrl].
 * - **Redirects** are followed by hand, at most [RepoLimits.maxRedirects]. Credentials go only to the repository's
 *   origin and are dropped for the rest of the chain once a redirect leaves the current origin.
 * - **Indexes** use `ETag`/`If-None-Match` with an [IndexCache], are capped at [RepoLimits.maxIndexBytes] (from
 *   `Content-Length` and while reading) and are validated with the shared pack-spec rules.
 * - **Downloads** check the advertised size before the request, `Content-Length` before reading and the byte count
 *   while streaming, hash with SHA-256 while streaming, and write to a temporary file that is renamed only when both
 *   match.
 *
 * Suspending functions are main-safe; cancelling them cancels the HTTP call.
 */
class RepositoryClient(
    httpClient: OkHttpClient = defaultHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val limits: RepoLimits = RepoLimits(),
) {
    // Whatever client is passed in, redirects and caching stay under this class's control.
    private val http: OkHttpClient =
        httpClient
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .cache(null)
            .build()

    /**
     * Fetches the index of [access]. With a [cache] that holds an earlier copy, sends `If-None-Match` and reuses the
     * copy on 304. The registered URL ([RepositoryAccess.indexUrl]) is the cache key; relative references resolve
     * against the URL after redirects.
     */
    suspend fun fetchIndex(
        access: RepositoryAccess,
        cache: IndexCache? = null,
    ): RepoResult<IndexUpdate> =
        boundary {
            val key = access.indexUrl.toString()
            val cached = cache?.read(key)
            val headers =
                Headers
                    .Builder()
                    .add("Accept", "application/json")
                    .apply { cached?.etag?.let { add("If-None-Match", it) } }
                    .build()
            send(access.indexUrl, access, headers) { response, finalUrl ->
                if (response.code == HTTP_NOT_MODIFIED && cached != null) {
                    val base = cached.finalUrl.toHttpUrlOrNull()?.takeIf { it.isHttps } ?: access.indexUrl
                    return@send IndexUpdate(
                        parseIndex(access.indexUrl, base, cached.body, cached.etag),
                        notModified = true,
                    )
                }
                requireSuccess(response)
                val body = readCapped(response, limits.maxIndexBytes, expected = null, sink = null)
                val etag = response.header("ETag")
                val snapshot = parseIndex(access.indexUrl, finalUrl, body.bytes, etag)
                cache?.write(CachedIndex(key, finalUrl.toString(), etag, body.bytes))
                IndexUpdate(snapshot, notModified = false)
            }
        }

    /**
     * Downloads [url] to [destination] (replaced only on success). With [expected] (an index entry) the size and
     * SHA-256 must match; without it (a direct link) the file is capped at [RepoLimits.maxDownloadBytes] and its
     * hash is only reported. [access] supplies credentials, sent only if [url] has the repository's origin.
     */
    suspend fun download(
        url: HttpUrl,
        destination: File,
        expected: ExpectedFile? = null,
        access: RepositoryAccess? = null,
        progress: DownloadProgress? = null,
    ): RepoResult<DownloadedFile> =
        boundary {
            if (expected != null && (expected.size > limits.maxDownloadBytes || expected.size < 0)) {
                throw RepoException(RepoError.TooLarge(limits.maxDownloadBytes, expected.size))
            }
            // Ask for the raw bytes: the size and hash describe the file, not a compressed transfer.
            val headers = Headers.headersOf("Accept-Encoding", "identity")
            send(url, access, headers) { response, _ ->
                requireSuccess(response)
                val dir =
                    destination.absoluteFile.parentFile ?: throw RepoException(RepoError.Storage("No parent folder"))
                if (!dir.isDirectory && !dir.mkdirs()) throw RepoException(RepoError.Storage("Cannot create $dir"))
                val part = File(dir, "${destination.name}.part")
                try {
                    val result =
                        part.outputStream().use { out ->
                            readCapped(response, limits.maxDownloadBytes, expected, out, progress)
                        }
                    if (expected != null && !result.sha256.equals(expected.sha256, ignoreCase = true)) {
                        throw RepoException(RepoError.HashMismatch(expected.sha256.lowercase(), result.sha256))
                    }
                    if (destination.exists() && !destination.delete()) {
                        throw RepoException(RepoError.Storage("Cannot replace $destination"))
                    }
                    if (!part.renameTo(destination)) throw RepoException(RepoError.Storage("Cannot move $part"))
                    DownloadedFile(destination, result.size, result.sha256)
                } finally {
                    part.delete()
                }
            }
        }

    /** Fetches a small preview file (cover, sprite, sample clip), capped at [RepoLimits.maxPreviewBytes]. */
    suspend fun fetchPreview(
        url: HttpUrl,
        access: RepositoryAccess? = null,
    ): RepoResult<ByteArray> =
        boundary {
            send(url, access, Headers.headersOf()) { response, _ ->
                requireSuccess(response)
                readCapped(response, limits.maxPreviewBytes, expected = null, sink = null).bytes
            }
        }

    private fun parseIndex(
        indexUrl: HttpUrl,
        baseUrl: HttpUrl,
        bytes: ByteArray,
        etag: String?,
    ): RepositorySnapshot {
        val report = DocumentValidator.validate(DocumentKind.REPOSITORY, bytes)
        if (!report.isValid) throw RepoException(RepoError.InvalidIndex(report))
        val index =
            try {
                SpecJson.decodeFromString(RepositoryIndex.serializer(), bytes.decodeToString())
            } catch (e: SerializationException) {
                throw IOException("Schema-valid index did not decode", e)
            }
        return RepositorySnapshot(indexUrl, baseUrl, index, report.warnings, etag)
    }

    /** Runs [block] on the IO dispatcher and turns failures into [RepoResult.Failure]. */
    private suspend fun <T> boundary(block: suspend () -> T): RepoResult<T> =
        withContext(ioDispatcher) {
            try {
                RepoResult.Success(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: RepoException) {
                RepoResult.Failure(e.error)
            } catch (e: IOException) {
                RepoResult.Failure(RepoError.Network(e.message ?: e.javaClass.simpleName))
            }
        }

    /**
     * Sends a GET to [start], following redirects by hand, and hands the final response to [handle]. The response is
     * closed afterwards. Credentials of [access] are attached while the request stays on the repository's origin.
     */
    private suspend fun <T> send(
        start: HttpUrl,
        access: RepositoryAccess?,
        headers: Headers,
        handle: suspend (Response, HttpUrl) -> T,
    ): T {
        val credentials = access?.credentials
        var url = start
        var sendCredentials = credentials != null && start.sameOrigin(access.indexUrl)
        var redirects = 0
        while (true) {
            if (!url.isHttps) throw RepoException(RepoError.InsecureUrl(url.redact()))
            val request =
                Request
                    .Builder()
                    .url(url)
                    .headers(headers)
                    .apply {
                        if (sendCredentials &&
                            credentials != null
                        ) {
                            header("Authorization", credentials.authorizationHeader())
                        }
                    }.build()
            val call = http.newCall(request)
            val response = call.await()
            try {
                if (response.code in REDIRECT_CODES) {
                    val next =
                        response.header("Location")?.let(url::resolve)
                            ?: throw RepoException(RepoError.HttpStatus(response.code))
                    if (++redirects >
                        limits.maxRedirects
                    ) {
                        throw RepoException(RepoError.TooManyRedirects(limits.maxRedirects))
                    }
                    // Never forward credentials to another origin, and not back again after leaving.
                    if (!next.sameOrigin(url)) sendCredentials = false
                    url = next
                    continue
                }
                return handle(response, url)
            } catch (e: CancellationException) {
                call.cancel()
                throw e
            } finally {
                response.close()
            }
        }
    }

    private fun requireSuccess(response: Response) {
        when (response.code) {
            in 200..299 -> Unit
            401, 403 -> throw RepoException(RepoError.Unauthorized(response.code))
            else -> throw RepoException(RepoError.HttpStatus(response.code))
        }
    }

    private class Body(
        val bytes: ByteArray,
        val size: Long,
        val sha256: String,
    )

    /**
     * Reads the body into [sink] (or memory when null) while hashing it. The cap is [expected]'s size when known,
     * otherwise [max]; `Content-Length` is checked before reading and the count while reading.
     */
    private suspend fun readCapped(
        response: Response,
        max: Long,
        expected: ExpectedFile?,
        sink: OutputStream?,
        progress: DownloadProgress? = null,
    ): Body {
        val body = response.body
        val declared = body.contentLength().takeIf { it >= 0 }
        if (expected != null && declared != null && declared != expected.size) {
            throw RepoException(RepoError.SizeMismatch(expected.size, declared))
        }
        if (declared != null && declared > max) throw RepoException(RepoError.TooLarge(max, declared))
        val cap = expected?.size ?: max
        val total = expected?.size ?: declared
        val memory = if (sink == null) ByteArrayOutputStream() else null
        val out = sink ?: memory!!
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        var count = 0L
        var reported = 0L
        progress?.onProgress(0, total)
        body.byteStream().use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                count += n
                if (count > cap) {
                    throw RepoException(
                        if (expected !=
                            null
                        ) {
                            RepoError.SizeMismatch(expected.size, count)
                        } else {
                            RepoError.TooLarge(max, count)
                        },
                    )
                }
                digest.update(buffer, 0, n)
                try {
                    out.write(buffer, 0, n)
                } catch (e: IOException) {
                    throw RepoException(RepoError.Storage(e.message ?: "Write failed"))
                }
                if (progress != null && count - reported >= PROGRESS_STEP_BYTES) {
                    reported = count
                    progress.onProgress(count, total)
                }
            }
        }
        if (expected != null &&
            count != expected.size
        ) {
            throw RepoException(RepoError.SizeMismatch(expected.size, count))
        }
        progress?.onProgress(count, total ?: count)
        return Body(memory?.toByteArray() ?: EMPTY, count, digest.digest().toHex())
    }

    companion object {
        private const val HTTP_NOT_MODIFIED = 304
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private const val BUFFER_BYTES = 64 * 1024
        private const val PROGRESS_STEP_BYTES = 256 * 1024L
        private val EMPTY = ByteArray(0)

        /**
         * The production client: TLS only (no cleartext connection spec), no automatic redirects, bounded connect,
         * read and write timeouts. A stalled server fails after the read timeout; large downloads have no total cap.
         */
        fun defaultHttpClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()
    }
}

/** Suspends until the response headers arrive; cancelling the coroutine cancels the call. */
private suspend fun Call.await(): Response =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                }
            },
        )
    }
