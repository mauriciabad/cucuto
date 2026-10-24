package app.mauri.cucuto.core.drive

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient

/** Hands out tokens `t1`, `t2`, … and records what was invalidated; or refuses with [unavailable]. */
class FakeAuthorizer(
    private val unavailable: DriveAuthorization.Reason? = null,
) : DriveAuthorizer {
    private var next = 1
    private var current: DriveToken? = null
    val invalidated = mutableListOf<DriveToken>()
    var calls = 0
        private set

    override suspend fun authorize(): DriveAuthorization {
        calls++
        unavailable?.let { return DriveAuthorization.Unavailable(it) }
        val token = current ?: DriveToken("t${next++}").also { current = it }
        return DriveAuthorization.Granted(token)
    }

    override suspend fun invalidate(token: DriveToken) {
        invalidated += token
        if (current == token) current = null
    }
}

/** A client against [server], plain HTTP (the production client is HTTPS only). */
fun MockWebServer.driveClient(authorizer: DriveAuthorizer = FakeAuthorizer()): DriveClient =
    DriveClient(
        authorizer = authorizer,
        httpClient = OkHttpClient(),
        apiBase = url("/drive/v3/"),
        uploadBase = url("/upload/drive/v3/"),
    )

fun json(
    body: String,
    code: Int = 200,
): MockResponse =
    MockResponse
        .Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

fun status(code: Int): MockResponse =
    MockResponse
        .Builder()
        .code(code)
        .build()

fun MockWebServer.session(path: String = "/upload/drive/v3/files?upload_id=s1"): MockResponse =
    MockResponse
        .Builder()
        .code(200)
        .addHeader("Location", url(path).toString())
        .build()

fun fileJson(
    id: String,
    name: String,
    modified: String = "2026-10-24T10:00:00.000Z",
    mimeType: String = "application/zip",
    size: Long? = 3,
): String =
    """{"id":"$id","name":"$name","mimeType":"$mimeType",""" +
        (size?.let { """"size":"$it",""" } ?: "") +
        """"modifiedTime":"$modified"}"""

fun listJson(
    vararg files: String,
    next: String? = null,
): String = """{"files":[${files.joinToString(",")}]${next?.let { ""","nextPageToken":"$it"""" } ?: ""}}"""

val RecordedRequest.text: String get() = body?.utf8().orEmpty()
