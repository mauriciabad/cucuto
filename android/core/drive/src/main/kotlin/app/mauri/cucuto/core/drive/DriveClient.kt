package app.mauri.cucuto.core.drive

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A file or folder in Drive, with the fields Cucuto asks for. */
@Serializable
data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String? = null,
    /** Bytes; Drive sends it as a string and leaves it out for folders and Google Docs. */
    val size: Long? = null,
    /** RFC 3339, for example `2026-10-24T10:00:00.000Z`. */
    val modifiedTime: String? = null,
) {
    val isFolder: Boolean get() = mimeType == FOLDER_MIME_TYPE

    companion object {
        const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
    }
}

/** Why a Drive call failed, for a plain message and the retry decision. */
sealed interface DriveError {
    /** No token: see [reason]. */
    data class NotAuthorized(
        val reason: DriveAuthorization.Reason?,
    ) : DriveError

    /** Drive rejected the token twice (access removed). */
    data object Unauthorized : DriveError

    /** No connection, or it broke: try again later. */
    data object Offline : DriveError

    data object NotFound : DriveError

    /** The user's Drive is full. */
    data object QuotaExceeded : DriveError

    /** 429 or a 5xx: Drive asks to come back later. */
    data object Busy : DriveError

    /** The download is larger than the caller allows. */
    data class TooLarge(
        val limit: Long,
    ) : DriveError

    data class Http(
        val code: Int,
    ) : DriveError

    /** Drive answered something that does not parse, or a redirect to another host. */
    data object BadResponse : DriveError
}

class DriveException(
    val error: DriveError,
    cause: Throwable? = null,
) : IOException(error.toString(), cause)

/**
 * The few Drive REST v3 calls Cucuto needs (developers.google.com/workspace/drive/api/reference/rest/v3), over OkHttp
 * like `:core:repo`. Every call gets a token from [authorizer]; a 401 invalidates it and retries once. The token goes
 * only to [apiBase]'s and [uploadBase]'s hosts: an upload session URL on any other host is refused.
 *
 * Nothing here runs by itself: callers use it when the user acts or an automatic backup runs (ADR 0040).
 */
class DriveClient(
    private val authorizer: DriveAuthorizer,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val apiBase: HttpUrl = API_BASE.toHttpUrl(),
    private val uploadBase: HttpUrl = UPLOAD_BASE.toHttpUrl(),
) {
    /** Files and folders in [folderId] (not trashed), all pages. [extraQuery] is ANDed to the parent clause. */
    suspend fun list(
        folderId: String,
        extraQuery: String? = null,
    ): List<DriveFile> {
        val parent = "${quote(folderId)} in parents and trashed = false"
        return search(if (extraQuery == null) parent else "$parent and ($extraQuery)")
    }

    /** Files matching the Drive query [q] (search query syntax), all pages. */
    suspend fun search(q: String): List<DriveFile> {
        val files = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val url =
                apiBase
                    .newBuilder()
                    .addPathSegment("files")
                    .addQueryParameter("q", q)
                    .addQueryParameter("spaces", "drive")
                    .addQueryParameter("pageSize", PAGE_SIZE.toString())
                    .addQueryParameter("fields", "nextPageToken,files($FILE_FIELDS)")
                    .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                    .build()
            val page = json<FileList>(call { Request.Builder().url(url).get() })
            files += page.files
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return files
    }

    /** Creates a folder named [name] in My Drive, tagged with [appProperties] so it can be found again. */
    suspend fun createFolder(
        name: String,
        appProperties: Map<String, String> = emptyMap(),
    ): DriveFile {
        val metadata =
            buildJsonObject {
                put("name", name)
                put("mimeType", DriveFile.FOLDER_MIME_TYPE)
                if (appProperties.isNotEmpty()) {
                    putJsonObject("appProperties") { appProperties.forEach { (k, v) -> put(k, v) } }
                }
            }
        val url =
            apiBase
                .newBuilder()
                .addPathSegment("files")
                .addQueryParameter("fields", FILE_FIELDS)
                .build()
        return json(call { Request.Builder().url(url).post(metadata.toBody()) })
    }

    /**
     * Uploads [file] as a new file [name] in [parentId], or as the new content of [replaceId] (Drive keeps the old
     * content as a revision). Uses a resumable session, so large full backups don't need multipart in memory.
     */
    suspend fun upload(
        file: File,
        name: String,
        mimeType: String,
        parentId: String? = null,
        replaceId: String? = null,
    ): DriveFile {
        val metadata =
            buildJsonObject {
                put("name", name)
                if (replaceId == null) {
                    put("mimeType", mimeType)
                    parentId?.let { putJsonArray("parents") { add(JsonPrimitive(it)) } }
                }
            }
        val start =
            uploadBase
                .newBuilder()
                .addPathSegment("files")
                .apply { replaceId?.let { addPathSegment(it) } }
                .addQueryParameter("uploadType", "resumable")
                .addQueryParameter("fields", FILE_FIELDS)
                .build()
        val session =
            call {
                Request
                    .Builder()
                    .url(start)
                    .header("X-Upload-Content-Type", mimeType)
                    .header("X-Upload-Content-Length", file.length().toString())
                    .method(if (replaceId == null) "POST" else "PATCH", metadata.toBody())
            }.use { response ->
                response.header("Location")?.toHttpUrlOrNull() ?: throw DriveException(DriveError.BadResponse)
            }
        if (!session.isHttps && apiBase.isHttps) throw DriveException(DriveError.BadResponse)
        if (session.host != uploadBase.host) throw DriveException(DriveError.BadResponse)
        return json(call { Request.Builder().url(session).put(file.asRequestBody(mimeType.toMediaType())) })
    }

    /** Downloads [fileId]'s content to [destination], refusing more than [maxBytes]. */
    suspend fun download(
        fileId: String,
        destination: File,
        maxBytes: Long,
    ) {
        val url =
            apiBase
                .newBuilder()
                .addPathSegment("files")
                .addPathSegment(fileId)
                .addQueryParameter("alt", "media")
                .build()
        call { Request.Builder().url(url).get() }.use { response ->
            val body = response.body
            val length = body.contentLength()
            if (length > maxBytes) throw DriveException(DriveError.TooLarge(maxBytes))
            withContext(Dispatchers.IO) {
                val part = File(destination.path + ".part")
                try {
                    var total = 0L
                    part.outputStream().use { out ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(BUFFER)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > maxBytes) throw DriveException(DriveError.TooLarge(maxBytes))
                                out.write(buffer, 0, read)
                            }
                        }
                    }
                    if (destination.exists()) destination.delete()
                    if (!part.renameTo(destination)) throw IOException("Cannot move the download into place")
                } finally {
                    part.delete()
                }
            }
        }
    }

    /** Deletes [fileId] for good (Cucuto only deletes its own old backups). A missing file is not an error. */
    suspend fun delete(fileId: String) {
        val url =
            apiBase
                .newBuilder()
                .addPathSegment("files")
                .addPathSegment(fileId)
                .build()
        try {
            call { Request.Builder().url(url).delete() }.close()
        } catch (e: DriveException) {
            if (e.error != DriveError.NotFound) throw e
        }
    }

    /** Runs a request with a token; a 401 invalidates it and tries once more with a fresh one. */
    private suspend fun call(build: () -> Request.Builder): Response {
        repeat(2) { attempt ->
            val token =
                when (val auth = authorizer.authorize()) {
                    is DriveAuthorization.Granted -> auth.token
                    is DriveAuthorization.NeedsConsent -> throw DriveException(DriveError.NotAuthorized(null))
                    is DriveAuthorization.Unavailable -> throw DriveException(DriveError.NotAuthorized(auth.reason))
                }
            val request = build().build()
            val authorized =
                if (request.url.host == apiBase.host || request.url.host == uploadBase.host) {
                    request.newBuilder().header("Authorization", "Bearer ${token.value}").build()
                } else {
                    request
                }
            val response =
                try {
                    httpClient.newCall(authorized).await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    throw DriveException(DriveError.Offline, e)
                }
            if (response.isSuccessful) return response
            val body = response.use { runCatching { it.body.string() }.getOrDefault("") }
            when (response.code) {
                401 -> {
                    authorizer.invalidate(token)
                    if (attempt == 1) throw DriveException(DriveError.Unauthorized)
                }

                403 -> {
                    throw DriveException(
                        when {
                            "storageQuotaExceeded" in body -> DriveError.QuotaExceeded
                            "rateLimitExceeded" in body || "userRateLimitExceeded" in body -> DriveError.Busy
                            else -> DriveError.Http(403)
                        },
                    )
                }

                404 -> {
                    throw DriveException(DriveError.NotFound)
                }

                429, in 500..599 -> {
                    throw DriveException(DriveError.Busy)
                }

                else -> {
                    throw DriveException(DriveError.Http(response.code))
                }
            }
        }
        throw DriveException(DriveError.Unauthorized)
    }

    private inline fun <reified T> json(response: Response): T =
        response.use {
            try {
                JSON.decodeFromString<T>(it.body.string())
            } catch (e: SerializationException) {
                throw DriveException(DriveError.BadResponse, e)
            } catch (e: IllegalArgumentException) {
                throw DriveException(DriveError.BadResponse, e)
            }
        }

    @Serializable
    private data class FileList(
        val files: List<DriveFile> = emptyList(),
        @SerialName("nextPageToken") val nextPageToken: String? = null,
    )

    companion object {
        const val API_BASE = "https://www.googleapis.com/drive/v3/"
        const val UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3/"
        private const val FILE_FIELDS = "id,name,mimeType,size,modifiedTime"
        private const val PAGE_SIZE = 100
        private const val BUFFER = 64 * 1024

        private val JSON =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }

        private val JSON_TYPE = "application/json; charset=UTF-8".toMediaType()

        private fun JsonObject.toBody(): RequestBody = toString().toRequestBody(JSON_TYPE)

        /** A Drive query string literal: `'` and `\` escaped (search query syntax). */
        fun quote(value: String): String = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"

        /** HTTPS only (no cleartext spec), generous timeouts for large uploads on mobile data. */
        fun defaultHttpClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
    }
}

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
                    continuation.resume(response) { _, _, _ -> response.close() }
                }
            },
        )
    }
