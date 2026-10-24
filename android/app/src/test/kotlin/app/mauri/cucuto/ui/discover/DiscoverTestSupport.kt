package app.mauri.cucuto.ui.discover

import app.mauri.cucuto.core.data.db.RepositoryAuthType
import app.mauri.cucuto.core.pack.ImportProgress
import app.mauri.cucuto.core.pack.ImportSource
import app.mauri.cucuto.core.pack.InstallAction
import app.mauri.cucuto.core.pack.InstalledPack
import app.mauri.cucuto.core.pack.PackCandidate
import app.mauri.cucuto.core.pack.PackSelection
import app.mauri.cucuto.core.repo.RepositoryClient
import app.mauri.cucuto.core.repo.RepositoryCredentials
import app.mauri.cucuto.packs.ImportReview
import app.mauri.cucuto.packs.ImportStep
import app.mauri.cucuto.packs.LibraryPack
import app.mauri.cucuto.packs.PackCatalog
import app.mauri.cucuto.packs.PackOrigin
import app.mauri.cucuto.packs.ReviewPack
import app.mauri.cucuto.packspec.Character
import app.mauri.cucuto.packspec.Clip
import app.mauri.cucuto.packspec.PackManifest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** A throwaway certificate for the loopback names: the repository client is HTTPS only, so tests run real TLS. */
object TestTls {
    private val certificate: HeldCertificate =
        HeldCertificate
            .Builder()
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .build()

    val server: HandshakeCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()

    val client: HandshakeCertificates =
        HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
}

/** A started fake pack repository: an HTTPS [MockWebServer] on 127.0.0.1. */
fun repositoryServer(): MockWebServer =
    MockWebServer().apply {
        useHttps(TestTls.server.sslSocketFactory())
        start(InetAddress.getByName("127.0.0.1"), 0)
    }

/** [path] on this server, addressed as `localhost`. */
fun MockWebServer.at(path: String): HttpUrl = url(path).newBuilder().host("localhost").build()

fun testClient(): RepositoryClient =
    RepositoryClient(
        httpClient =
            RepositoryClient
                .defaultHttpClient()
                .newBuilder()
                .sslSocketFactory(TestTls.client.sslSocketFactory(), TestTls.client.trustManager)
                .build(),
    )

fun ok(body: String): MockResponse =
    MockResponse
        .Builder()
        .code(200)
        .body(body)
        .build()

fun ok(bytes: ByteArray): MockResponse =
    MockResponse
        .Builder()
        .code(200)
        .body(Buffer().write(bytes))
        .build()

fun status(code: Int): MockResponse = MockResponse.Builder().code(code).build()

fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** The bytes the fake repository serves as a pack file (the fake catalog never unzips them). */
val packBytes: ByteArray = ByteArray(4096) { (it % 251).toByte() }

/** A valid 64 × 64 PNG header: enough for pack-spec's image probe. */
val pngBytes: ByteArray =
    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
        byteArrayOf(0, 0, 0, 13) + "IHDR".encodeToByteArray() +
        byteArrayOf(0, 0, 0, 64, 0, 0, 0, 64, 8, 6, 0, 0, 0) + byteArrayOf(0, 0, 0, 0)

/** One index entry (a pack with one character), schema-valid. */
fun packEntry(
    id: String,
    name: String,
    version: String = "1.0.0",
    tags: List<String> = emptyList(),
    character: String = "Bleep",
    size: Long = packBytes.size.toLong(),
    sha: String = sha256(packBytes),
): String =
    """
    {
      "id": "$id", "type": "pack", "name": "$name", "version": "$version", "author": "Test author",
      "license": "CC0-1.0", "tags": [${tags.joinToString { "\"$it\"" }}],
      "cover": "previews/$id/cover.png",
      "characters": [
        { "id": "c1", "name": "$character", "sprite": "previews/$id/c1.png", "sample": "previews/$id/c1.ogg" }
      ],
      "download": { "url": "packs/$id-$version.soundpack.zip", "size": $size, "sha256": "$sha" }
    }
    """.trimIndent()

fun indexJson(
    name: String,
    vararg entries: String,
): String =
    """
    { "formatVersion": 1, "name": "$name", "packs": [${entries.joinToString(",")}] }
    """.trimIndent()

/** Repositories and installed packs in memory. Credentials are kept as given. */
class FakeDiscoverStore(
    private val defaultUrl: String,
) : DiscoverStore {
    private var nextId = 1L
    override val repositories = MutableStateFlow<List<SavedRepository>>(emptyList())
    override val installed = MutableStateFlow<List<InstalledRef>>(emptyList())
    val credentials = mutableMapOf<String, RepositoryCredentials>()

    override suspend fun ensureDefault() {
        if (repositories.value.none { it.url == defaultUrl }) {
            repositories.update {
                it +
                    SavedRepository(
                        nextId++,
                        defaultUrl,
                        null,
                        isDefault = true,
                        enabled = true,
                        RepositoryAuthType.NONE,
                    )
            }
        }
    }

    override suspend fun add(
        url: String,
        name: String?,
        credentials: RepositoryCredentials?,
    ): Long? {
        if (repositories.value.any { it.url == url }) return null
        val id = nextId++
        repositories.update { it + SavedRepository(id, url, name, false, true, authTypeOf(credentials)) }
        if (credentials != null) this.credentials[url] = credentials
        return id
    }

    override suspend fun credentials(url: String): RepositoryCredentials? = credentials[url]

    override suspend fun setCredentials(
        id: Long,
        credentials: RepositoryCredentials?,
    ) {
        val repo = repositories.value.first { it.id == id }
        if (credentials == null) this.credentials.remove(repo.url) else this.credentials[repo.url] = credentials
        change(id) { it.copy(authType = authTypeOf(credentials)) }
    }

    override suspend fun setName(
        id: Long,
        name: String,
    ) = change(id) { it.copy(name = name) }

    override suspend fun setEnabled(
        id: Long,
        enabled: Boolean,
    ) = change(id) { it.copy(enabled = enabled) }

    override suspend fun remove(id: Long) {
        val repo = repositories.value.first { it.id == id }
        credentials.remove(repo.url)
        repositories.update { list -> list.filterNot { it.id == id } }
    }

    fun add(repository: SavedRepository) {
        repositories.update { it + repository }
        nextId = maxOf(nextId, repository.id + 1)
    }

    private fun change(
        id: Long,
        block: (SavedRepository) -> SavedRepository,
    ) = repositories.update { list -> list.map { if (it.id == id) block(it) else it } }
}

/** Records what the downloader hands over; the steps it answers with are up to the test. */
class FakeCatalog : PackCatalog {
    override val installed = MutableStateFlow<List<LibraryPack>>(emptyList())

    /** The downloaded file's bytes and origin, read while the file still exists. */
    val prepared = mutableListOf<Pair<ByteArray, PackOrigin?>>()

    override fun prepare(source: ImportSource): Flow<ImportStep> = record(source, null)

    override fun prepare(
        source: ImportSource,
        origin: PackOrigin,
    ): Flow<ImportStep> = record(source, origin)

    /** Every file is a valid single pack, `app.mauri.demo.cats`, ready for review. */
    private fun record(
        source: ImportSource,
        origin: PackOrigin?,
    ): Flow<ImportStep> =
        flow {
            prepared += source.open().use { it.readBytes() } to origin
            emit(ImportStep.Working(ImportProgress.Validating))
            emit(ImportStep.Ready(review(origin)))
        }

    override fun install(
        review: ImportReview,
        selection: PackSelection,
    ): Flow<ImportStep> =
        flowOf(
            ImportStep.Installed(
                review.packs.map { InstalledPack(it.id, "1.0.0", File("/packs/${it.id}/1.0.0"), null, active = true) },
                emptyList(),
            ),
        )

    private fun review(origin: PackOrigin?): ImportReview {
        val id = "app.mauri.demo.cats"
        val manifest =
            PackManifest(
                formatVersion = 1,
                id = id,
                name = "Pack $id",
                version = "1.0.0",
                author = "Test author",
                license = "CC0-1.0",
                characters =
                    listOf(
                        Character(id = "c1", name = "Tabby", sprite = "c1.png", clips = listOf(Clip("a.ogg"))),
                    ),
            )
        val candidate = PackCandidate(id, manifest, InstallAction.INSTALL, null, sizeBytes = 1_000, fileCount = 3)
        return ImportReview(
            fileName = null,
            bundle = null,
            packs = listOf(ReviewPack(candidate, emptyList())),
            warnings = emptyList(),
            handle = null,
            origin = origin,
        )
    }

    override fun discard(review: ImportReview) = Unit

    override suspend fun remove(
        packId: String,
        clearAssignments: Boolean,
    ) = Unit
}

/** Answers by path (requests run in parallel, so a FIFO queue would be flaky) and records every request. */
class Routes : Dispatcher() {
    private val responses = ConcurrentHashMap<String, () -> MockResponse>()
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    operator fun set(
        path: String,
        response: MockResponse,
    ) {
        responses[path] = { response }
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        return responses[request.url.encodedPath]?.invoke() ?: status(404)
    }

    fun paths(): List<String> = requests.map { it.url.encodedPath }

    fun request(path: String): RecordedRequest = requests.last { it.url.encodedPath == path }
}
