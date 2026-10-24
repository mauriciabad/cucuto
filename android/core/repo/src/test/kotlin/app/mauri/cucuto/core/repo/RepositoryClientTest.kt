package app.mauri.cucuto.core.repo

import app.mauri.cucuto.packspec.ErrorCode
import app.mauri.cucuto.packspec.Limits
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RepositoryClientTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val client = testClient()
    private val packBytes = minimalPack.readBytes()
    private val expected = ExpectedFile(packBytes.size.toLong(), sha256(packBytes))
    private val basic = RepositoryCredentials.Basic("ana", "s3cret")

    @Before
    fun setUp() {
        server = httpsServer()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun access(credentials: RepositoryCredentials? = null) =
        RepositoryAccess(server.at("/repo/repository.json"), credentials)

    private fun <T> RepoResult<T>.success(): T =
        (this as? RepoResult.Success)?.value ?: throw AssertionError("Expected success, got $this")

    private fun RepoResult<*>.error(): RepoError =
        (this as? RepoResult.Failure)?.error ?: throw AssertionError("Expected failure, got $this")

    private fun target() = File(tmp.root, "downloads/pack.soundpack.zip")

    @Test
    fun happyPathFetchesIndexResolvesRelativeUrlsAndVerifiesDownload() =
        runTest {
            server.enqueue(ok(indexJson()))
            server.enqueue(ok(packBytes))

            val update = client.fetchIndex(access(basic)).success()
            assertFalse(update.notModified)
            assertEquals("Fixture repository", update.snapshot.index.name)
            val entry = update.snapshot.entries.first()
            assertEquals(server.at("/repo/packs/app.mauri.fixtures.minimal-1.0.0.soundpack.zip"), entry.downloadUrl)
            assertEquals(server.at("/repo/previews/app.mauri.fixtures.minimal/cover.png"), entry.coverUrl)
            assertTrue(entry.supported)

            val file = client.download(entry.downloadUrl, target(), entry.expected, access(basic)).success()
            assertArrayEquals(packBytes, file.file.readBytes())
            assertEquals(expected.sha256, file.sha256)
            assertEquals(expected.size, file.size)
            assertFalse(File(target().parentFile, "pack.soundpack.zip.part").exists())

            val indexRequest = server.takeRequest()
            assertEquals(basic.authorizationHeader(), indexRequest.headers["Authorization"])
            val downloadRequest = server.takeRequest()
            assertEquals(basic.authorizationHeader(), downloadRequest.headers["Authorization"])
            assertEquals("identity", downloadRequest.headers["Accept-Encoding"])
        }

    @Test
    fun relativeUrlsResolveAgainstTheUrlAfterRedirects() =
        runTest {
            server.enqueue(redirect("/moved/index.json", code = 301))
            server.enqueue(ok(indexJson()))

            val snapshot = client.fetchIndex(access()).success().snapshot
            assertEquals(server.at("/repo/repository.json"), snapshot.indexUrl)
            assertEquals(server.at("/moved/index.json"), snapshot.baseUrl)
            assertEquals(
                server.at("/moved/packs/app.mauri.fixtures.minimal-1.0.0.soundpack.zip"),
                snapshot.entries[0].downloadUrl,
            )
            // Absolute references stay as they are.
            assertEquals("github.com", snapshot.entries[1].downloadUrl.host)
        }

    @Test
    fun etagRevalidationReusesTheCachedIndexOn304() =
        runTest {
            val cache = FileIndexCache(tmp.newFolder("index-cache"))
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .addHeader("ETag", "\"v1\"")
                    .body(indexJson())
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(304)
                    .addHeader("ETag", "\"v1\"")
                    .build(),
            )

            val first = client.fetchIndex(access(), cache).success()
            assertFalse(first.notModified)
            assertEquals("\"v1\"", first.snapshot.etag)

            val second = client.fetchIndex(access(), cache).success()
            assertTrue(second.notModified)
            assertEquals(first.snapshot.index, second.snapshot.index)
            assertEquals(first.snapshot.entries, second.snapshot.entries)

            assertNull(server.takeRequest().headers["If-None-Match"])
            assertEquals("\"v1\"", server.takeRequest().headers["If-None-Match"])
        }

    @Test
    fun cachedIndexWorksOfflineAndKeepsTheBaseAfterRedirects() =
        runTest {
            val cache = FileIndexCache(tmp.newFolder("offline-cache"))
            assertNull(client.cachedIndex(access(), cache))
            server.enqueue(redirect("/moved/index.json", code = 301))
            server.enqueue(ok(indexJson()))
            client.fetchIndex(access(), cache).success()
            val requests = server.requestCount

            val offline = client.cachedIndex(access(), cache) ?: throw AssertionError("Expected the cached copy")
            assertEquals("Fixture repository", offline.index.name)
            assertEquals(server.at("/moved/index.json"), offline.baseUrl)
            assertEquals(requests, server.requestCount)

            // A cache entry that no longer validates reads as missing.
            val key = access().indexUrl.toString()
            cache.write(CachedIndex(key, key, null, "{}".encodeToByteArray()))
            assertNull(client.cachedIndex(access(), cache))
        }

    @Test
    fun hugeAdvertisedSizeIsRejectedBeforeAnyRequest() =
        runTest {
            val huge = ExpectedFile(10 * Limits.GiB, expected.sha256)
            val error = client.download(server.at("/huge.soundpack.zip"), target(), huge).error()
            assertTrue(error is RepoError.TooLarge)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun contentLengthThatDiffersFromTheIndexAbortsBeforeReading() =
        runTest {
            server.enqueue(ok(packBytes))
            val lying = expected.copy(size = expected.size + 10)
            val error = client.download(server.at("/pack.soundpack.zip"), target(), lying).error()
            assertEquals(RepoError.SizeMismatch(lying.size, expected.size), error)
            assertEquals(ErrorCode.DOWNLOAD_SIZE_MISMATCH, error.code)
            assertFalse(target().exists())
        }

    @Test
    fun bodyLargerThanAdvertisedIsCutOffWhileStreaming() =
        runTest {
            server.close()
            server = httpsServer(http1Only = true)
            // Chunked: no Content-Length, so only the streaming check can catch it.
            val oversized = packBytes + ByteArray(200_000)
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .chunkedBody(Buffer().write(oversized), 4096)
                    .build(),
            )
            val error = client.download(server.at("/pack.soundpack.zip"), target(), expected).error()
            assertTrue(
                "$error",
                error is RepoError.SizeMismatch && error.actual > expected.size && error.actual < oversized.size,
            )
            assertFalse(target().exists())
            assertFalse(File(target().parentFile, "pack.soundpack.zip.part").exists())
        }

    @Test
    fun bodyShorterThanAdvertisedIsASizeMismatch() =
        runTest {
            server.close()
            server = httpsServer(http1Only = true)
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .chunkedBody(Buffer().write(packBytes.copyOf(1000)), 256)
                    .build(),
            )
            val error = client.download(server.at("/pack.soundpack.zip"), target(), expected).error()
            assertEquals(RepoError.SizeMismatch(expected.size, 1000), error)
        }

    @Test
    fun wrongSha256IsRejectedAndNothingIsKept() =
        runTest {
            server.enqueue(ok(packBytes))
            val wrong = expected.copy(sha256 = "1".repeat(64))
            val error = client.download(server.at("/pack.soundpack.zip"), target(), wrong).error()
            assertEquals(RepoError.HashMismatch("1".repeat(64), expected.sha256), error)
            assertEquals(ErrorCode.DOWNLOAD_HASH_MISMATCH, error.code)
            assertFalse(target().exists())
            assertFalse(File(target().parentFile, "pack.soundpack.zip.part").exists())
        }

    @Test
    fun redirectLoopStopsAtTheCap() =
        runTest {
            repeat(10) { server.enqueue(redirect("/repo/repository.json")) }
            val error = client.fetchIndex(access()).error()
            assertEquals(RepoError.TooManyRedirects(5), error)
            assertEquals(6, server.requestCount)
        }

    @Test
    fun sameOriginRedirectKeepsCredentials() =
        runTest {
            server.enqueue(redirect("/elsewhere/repository.json"))
            server.enqueue(ok(indexJson()))
            client.fetchIndex(access(basic)).success()
            assertEquals(basic.authorizationHeader(), server.takeRequest().headers["Authorization"])
            assertEquals(basic.authorizationHeader(), server.takeRequest().headers["Authorization"])
        }

    @Test
    fun crossHostRedirectDropsCredentials() =
        runTest {
            httpsServer().use { other ->
                // localhost -> 127.0.0.1 (another server): a different host, so the token must not follow.
                server.enqueue(redirect(other.at("/cdn/repository.json", host = "127.0.0.1").toString()))
                other.enqueue(ok(indexJson()))
                val bearer = RepositoryCredentials.Bearer("tok3n")

                client.fetchIndex(access(bearer)).success()

                assertEquals("Bearer tok3n", server.takeRequest().headers["Authorization"])
                assertNull(other.takeRequest().headers["Authorization"])
            }
        }

    @Test
    fun redirectBackToTheRepositoryHostAfterLeavingStaysWithoutCredentials() =
        runTest {
            httpsServer().use { other ->
                server.enqueue(redirect(other.at("/hop", host = "127.0.0.1").toString()))
                other.enqueue(redirect(server.at("/repo/back.json").toString()))
                server.enqueue(ok(indexJson()))

                client.fetchIndex(access(basic)).success()

                assertEquals(basic.authorizationHeader(), server.takeRequest().headers["Authorization"])
                assertNull(other.takeRequest().headers["Authorization"])
                assertNull(server.takeRequest().headers["Authorization"])
            }
        }

    @Test
    fun downloadsOnAnotherHostNeverGetTheRepositoryCredentials() =
        runTest {
            httpsServer().use { cdn ->
                cdn.enqueue(ok(packBytes))
                client
                    .download(
                        cdn.at("/pack.soundpack.zip", host = "127.0.0.1"),
                        target(),
                        expected,
                        access(basic),
                    ).success()
                assertNull(cdn.takeRequest().headers["Authorization"])
            }
        }

    @Test
    fun plainHttpIsRejectedWithoutConnecting() =
        runTest {
            plainServer().use { plain ->
                plain.enqueue(ok(packBytes))
                val error = client.download(plain.url("/pack.soundpack.zip"), target(), expected).error()
                assertTrue("$error", error is RepoError.InsecureUrl)
                assertEquals(0, plain.requestCount)
            }
        }

    @Test
    fun redirectToPlainHttpIsRejected() =
        runTest {
            plainServer().use { plain ->
                server.enqueue(redirect(plain.url("/repository.json").toString()))
                val error = client.fetchIndex(access(basic)).error()
                assertTrue("$error", error is RepoError.InsecureUrl)
                assertEquals(0, plain.requestCount)
            }
        }

    @Test
    fun repositoryAccessRequiresHttps() {
        val result =
            runCatching {
                RepositoryAccess(
                    server
                        .url("/repository.json")
                        .newBuilder()
                        .scheme("http")
                        .build(),
                )
            }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun malformedJsonIndexIsInvalid() =
        runTest {
            server.enqueue(ok("{\"formatVersion\": 1, \"name\": "))
            val error = client.fetchIndex(access()).error()
            assertTrue(error is RepoError.InvalidIndex)
            assertEquals(listOf("MANIFEST_INVALID_JSON"), (error as RepoError.InvalidIndex).report.errorCodes())
        }

    @Test
    fun schemaViolatingIndexIsInvalidAndNotCached() =
        runTest {
            val cache = FileIndexCache(tmp.newFolder("index-cache"))
            server.enqueue(ok(File(fixturesDir, "invalid/repository-http-url.json").readText()))
            val error = client.fetchIndex(access(), cache).error()
            assertEquals(listOf("SCHEMA_VIOLATION"), (error as RepoError.InvalidIndex).report.errorCodes())
            assertNull(cache.read(access().indexUrl.toString()))
        }

    @Test
    fun indexOverTheCapIsRejected() =
        runTest {
            server.close()
            server = httpsServer(http1Only = true)
            val small = testClient(RepoLimits(maxIndexBytes = 1024))
            server.enqueue(ok(indexJson())) // Content-Length over the cap
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .chunkedBody(indexJson(), 128)
                    .build(),
            ) // no Content-Length
            assertTrue(small.fetchIndex(access()).error() is RepoError.TooLarge)
            assertTrue(small.fetchIndex(access()).error() is RepoError.TooLarge)
        }

    @Test
    fun directLinksAreCappedAndHashed() =
        runTest {
            server.enqueue(ok(packBytes))
            val file = client.download(server.at("/one.soundpack.zip"), target()).success()
            assertEquals(expected.sha256, file.sha256)

            val small = testClient(RepoLimits(maxDownloadBytes = 100))
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .chunkedBody(Buffer().write(packBytes), 64)
                    .build(),
            )
            assertTrue(small.download(server.at("/one.soundpack.zip"), target()).error() is RepoError.TooLarge)
        }

    @Test
    fun unauthorizedAndMissingAreReported() =
        runTest {
            server.enqueue(MockResponse.Builder().code(401).build())
            server.enqueue(MockResponse.Builder().code(404).build())
            assertEquals(RepoError.Unauthorized(401), client.fetchIndex(access()).error())
            assertEquals(RepoError.HttpStatus(404), client.fetchIndex(access()).error())
        }

    @Test
    fun previewsAreCapped() =
        runTest {
            server.enqueue(ok(ByteArray(10)))
            server.enqueue(ok(ByteArray(Limits.BITMAP_FILE_BYTES.toInt() + 1)))
            assertEquals(10, client.fetchPreview(server.at("/cover.png")).success().size)
            assertTrue(client.fetchPreview(server.at("/cover.png")).error() is RepoError.TooLarge)
        }
}
