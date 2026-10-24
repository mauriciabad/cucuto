package app.mauri.cucuto.core.drive

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DriveBackupStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var store: DriveBackupStore

    @Before
    fun start() {
        server = MockWebServer().apply { start() }
        store = DriveBackupStore(server.driveClient())
    }

    @After
    fun stop() {
        server.closeServer()
    }

    private val isBackup: (String) -> Boolean = { it.startsWith("cucuto-backup-") }

    @Test
    fun `the folder is found by its app property and remembered`() =
        runTest {
            server.enqueue(json(listJson(fileJson("f1", "Renamed by the user", mimeType = DriveFile.FOLDER_MIME_TYPE))))
            server.enqueue(json(listJson()))
            server.enqueue(json(listJson()))

            assertEquals("f1", store.folder())
            store.backups(isBackup)
            store.backups(isBackup)

            val query =
                server
                    .takeRequest()
                    .url
                    .queryParameter("q")
                    .orEmpty()
            assertTrue(query.contains("appProperties has { key = 'cucuto' and value = 'backups' }"))
            assertEquals(3, server.requestCount)
        }

    @Test
    fun `a missing folder is created with its app property`() =
        runTest {
            server.enqueue(json(listJson()))
            server.enqueue(json(fileJson("f2", "Cucuto backups", mimeType = DriveFile.FOLDER_MIME_TYPE)))

            assertEquals("f2", store.folder())

            server.takeRequest()
            val create = server.takeRequest()
            assertEquals("POST", create.method)
            assertTrue(create.text.contains(""""appProperties":{"cucuto":"backups"}"""))
            assertTrue(create.text.contains(""""name":"Cucuto backups""""))
        }

    @Test
    fun `the same name gets new content, like one file per day in a folder`() =
        runTest {
            val file = temp.newFile("b.zip").apply { writeText("zip") }
            server.enqueue(json(listJson(fileJson("f1", "Cucuto backups", mimeType = DriveFile.FOLDER_MIME_TYPE))))
            server.enqueue(json(listJson(fileJson("today", "cucuto-backup-2026-10-24.zip"))))
            server.enqueue(server.session())
            server.enqueue(json(fileJson("today", "cucuto-backup-2026-10-24.zip")))

            store.upload(file, "cucuto-backup-2026-10-24.zip", "application/zip")

            server.takeRequest()
            val lookup =
                server
                    .takeRequest()
                    .url
                    .queryParameter("q")
                    .orEmpty()
            assertTrue(lookup.contains("name = 'cucuto-backup-2026-10-24.zip'"))
            assertEquals("/upload/drive/v3/files/today", server.takeRequest().url.encodedPath)
        }

    @Test
    fun `backups are newest first and other files are ignored`() =
        runTest {
            server.enqueue(json(listJson(fileJson("f1", "Cucuto backups", mimeType = DriveFile.FOLDER_MIME_TYPE))))
            server.enqueue(
                json(
                    listJson(
                        fileJson("1", "cucuto-backup-2026-10-22.zip", modified = "2026-10-22T09:00:00.000Z"),
                        fileJson("2", "notes.txt", modified = "2026-10-25T09:00:00.000Z"),
                        fileJson("3", "cucuto-backup-2026-10-24.zip", modified = "2026-10-24T09:00:00.000Z"),
                    ),
                ),
            )

            assertEquals(listOf("3", "1"), store.backups(isBackup).map { it.id })
        }

    @Test
    fun `pruning deletes only what the naming rules pick`() =
        runTest {
            server.enqueue(json(listJson(fileJson("f1", "Cucuto backups", mimeType = DriveFile.FOLDER_MIME_TYPE))))
            server.enqueue(
                json(
                    listJson(
                        fileJson("old", "cucuto-backup-2026-10-01.zip"),
                        fileJson("new", "cucuto-backup-2026-10-24.zip"),
                        fileJson("mine", "notes.txt"),
                    ),
                ),
            )
            server.enqueue(status(204))

            val deleted = store.prune { names -> names.filter { it.endsWith("10-01.zip") } }

            assertEquals(1, deleted)
            server.takeRequest()
            server.takeRequest()
            val delete = server.takeRequest()
            assertEquals("DELETE", delete.method)
            assertEquals("/drive/v3/files/old", delete.url.encodedPath)
        }

    @Test
    fun `a folder lists its index and its packs`() {
        val contents =
            DrivePackFolder.classify(
                listOf(
                    DriveFile("2", "Zebra.soundpack.zip"),
                    DriveFile("1", "repository.json"),
                    DriveFile("3", "cats.soundpack.zip"),
                    DriveFile("4", "sub", DriveFile.FOLDER_MIME_TYPE),
                    DriveFile("5", "readme.txt"),
                ),
            )
        assertEquals("1", contents.index?.id)
        assertEquals(listOf("cats.soundpack.zip", "Zebra.soundpack.zip"), contents.packs.map { it.name })
        assertNull(DrivePackFolder.classify(emptyList()).index)
    }
}
