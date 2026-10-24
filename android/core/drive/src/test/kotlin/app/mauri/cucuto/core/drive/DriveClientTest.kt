package app.mauri.cucuto.core.drive

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DriveClientTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun stop() {
        server.closeServer()
    }

    private suspend fun expectError(
        error: DriveError,
        block: suspend () -> Unit,
    ) {
        try {
            block()
            fail("Expected $error")
        } catch (e: DriveException) {
            assertEquals(error, e.error)
        }
    }

    @Test
    fun `list follows every page and sends the token`() =
        runTest {
            server.enqueue(json(listJson(fileJson("a", "one.zip"), next = "p2")))
            server.enqueue(json(listJson(fileJson("b", "two.zip", size = null))))

            val files = server.driveClient().list("folder'1")

            assertEquals(listOf("one.zip", "two.zip"), files.map { it.name })
            assertEquals(3L, files[0].size)
            val first = server.takeRequest()
            assertEquals("Bearer t1", first.headers["Authorization"])
            assertEquals("'folder\\'1' in parents and trashed = false", first.url.queryParameter("q"))
            assertEquals("p2", server.takeRequest().url.queryParameter("pageToken"))
        }

    @Test
    fun `a rejected token is invalidated and the call retried once`() =
        runTest {
            val auth = FakeAuthorizer()
            server.enqueue(status(401))
            server.enqueue(json(listJson()))

            server.driveClient(auth).search("trashed = false")

            assertEquals(listOf(DriveToken("t1")), auth.invalidated)
            assertEquals("Bearer t1", server.takeRequest().headers["Authorization"])
            assertEquals("Bearer t2", server.takeRequest().headers["Authorization"])
        }

    @Test
    fun `two rejections mean access was removed`() =
        runTest {
            server.enqueue(status(401))
            server.enqueue(status(401))
            expectError(DriveError.Unauthorized) { server.driveClient().search("x") }
        }

    @Test
    fun `without an OAuth client nothing is sent`() =
        runTest {
            val client = server.driveClient(FakeAuthorizer(DriveAuthorization.Reason.NOT_CONFIGURED))
            expectError(DriveError.NotAuthorized(DriveAuthorization.Reason.NOT_CONFIGURED)) { client.search("x") }
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `errors map to plain reasons`() =
        runTest {
            val client = server.driveClient()
            server.enqueue(json("""{"error":{"errors":[{"reason":"storageQuotaExceeded"}]}}""", code = 403))
            expectError(DriveError.QuotaExceeded) { client.search("x") }
            server.enqueue(status(404))
            expectError(DriveError.NotFound) { client.search("x") }
            server.enqueue(status(503))
            expectError(DriveError.Busy) { client.search("x") }
            server.enqueue(json("not json"))
            expectError(DriveError.BadResponse) { client.search("x") }
        }

    @Test
    fun `a new file is uploaded through a resumable session`() =
        runTest {
            val file = temp.newFile("b.zip").apply { writeText("zip") }
            server.enqueue(server.session())
            server.enqueue(json(fileJson("new", "cucuto-backup-2026-10-24.zip")))

            val uploaded = server.driveClient().upload(file, "cucuto-backup-2026-10-24.zip", "application/zip", "f1")

            assertEquals("new", uploaded.id)
            val start = server.takeRequest()
            assertEquals("POST", start.method)
            assertEquals("/upload/drive/v3/files", start.url.encodedPath)
            assertEquals("resumable", start.url.queryParameter("uploadType"))
            assertEquals("3", start.headers["X-Upload-Content-Length"])
            assertTrue(start.text.contains(""""parents":["f1"]"""))
            val put = server.takeRequest()
            assertEquals("PUT", put.method)
            assertEquals("s1", put.url.queryParameter("upload_id"))
            assertEquals("zip", put.text)
        }

    @Test
    fun `replacing a file patches its content`() =
        runTest {
            val file = temp.newFile("b.zip").apply { writeText("zip") }
            server.enqueue(server.session())
            server.enqueue(json(fileJson("old", "b.zip")))

            server.driveClient().upload(file, "b.zip", "application/zip", parentId = "f1", replaceId = "old")

            val start = server.takeRequest()
            assertEquals("PATCH", start.method)
            assertEquals("/upload/drive/v3/files/old", start.url.encodedPath)
            assertFalse(start.text.contains("parents"))
        }

    @Test
    fun `an upload session on another host is refused`() =
        runTest {
            val file = temp.newFile("b.zip").apply { writeText("zip") }
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .addHeader("Location", "http://elsewhere.example/upload")
                    .build(),
            )
            expectError(DriveError.BadResponse) {
                server.driveClient().upload(file, "b.zip", "application/zip", "f1")
            }
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `downloads stop past the limit and leave nothing behind`() =
        runTest {
            val target = temp.root.resolve("out.zip")
            server.enqueue(json("0123456789"))
            expectError(DriveError.TooLarge(4)) { server.driveClient().download("id", target, maxBytes = 4) }
            assertFalse(target.exists())
            assertFalse(temp.root.resolve("out.zip.part").exists())

            server.enqueue(json("0123"))
            server.driveClient().download("id", target, maxBytes = 4)
            assertEquals("0123", target.readText())
            assertEquals("media", server.takeRequest().url.queryParameter("alt"))
        }

    @Test
    fun `deleting a missing file is fine`() =
        runTest {
            server.enqueue(status(404))
            server.driveClient().delete("gone")
            assertEquals("DELETE", server.takeRequest().method)
        }

    @Test
    fun `tokens never show up in logs`() {
        assertFalse(DriveToken("secret").toString().contains("secret"))
    }
}
