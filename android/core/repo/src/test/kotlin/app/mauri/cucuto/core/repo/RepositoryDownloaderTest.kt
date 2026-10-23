package app.mauri.cucuto.core.repo

import app.mauri.cucuto.core.pack.ImportEvent
import app.mauri.cucuto.core.pack.ImportResult
import app.mauri.cucuto.core.pack.PackImporter
import app.mauri.cucuto.core.pack.PackStore
import app.mauri.cucuto.core.pack.media.AudioProbe
import app.mauri.cucuto.core.pack.media.AudioProbeResult
import app.mauri.cucuto.core.pack.media.ImageProbe
import app.mauri.cucuto.core.pack.media.ImageProbeResult
import app.mauri.cucuto.core.pack.media.MediaVerifier
import app.mauri.cucuto.packspec.FileFormat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import app.mauri.cucuto.packspec.media.AudioProbe as HeaderAudioProbe
import app.mauri.cucuto.packspec.media.ImageProbe as HeaderImageProbe

class RepositoryDownloaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var workDir: File
    private lateinit var downloader: RepositoryDownloader

    // The JVM has no platform decoders: "decode" with pack-spec's header parsers, as :core:pack's own tests do.
    private val audio =
        AudioProbe { file, format, _ ->
            HeaderAudioProbe.durationMs(format, file.readBytes())?.let { AudioProbeResult.Decoded(it) }
                ?: AudioProbeResult.Undecodable("Unreadable header")
        }
    private val image =
        ImageProbe { file, format ->
            if (format == FileFormat.SVG) {
                ImageProbeResult.NotChecked("SVG")
            } else {
                HeaderImageProbe.size(format, file.readBytes())?.let { ImageProbeResult.Decoded(it.width, it.height) }
                    ?: ImageProbeResult.Undecodable("Unreadable header")
            }
        }

    @Before
    fun setUp() {
        server = httpsServer()
        val root = tmp.newFolder("app")
        workDir = File(root, "cache/repo-download")
        val importer =
            PackImporter(
                store = PackStore(File(root, "files/packs")),
                workDir = File(root, "cache/pack-import"),
                verifier = MediaVerifier(audio, image),
            )
        downloader = RepositoryDownloader(testClient(), importer, workDir)
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun verifiedDownloadGoesThroughTheImporter() =
        runTest {
            server.enqueue(ok(indexJson()))
            server.enqueue(ok(minimalPack.readBytes()))
            val access = RepositoryAccess(server.at("/repository.json"))
            val snapshot = (testClient().fetchIndex(access) as RepoResult.Success).value.snapshot

            val events = downloader.downloadAndPrepare(snapshot.entries.first(), access).toList()

            assertTrue(events.first() is DownloadEvent.Downloading)
            val downloaded = events.filterIsInstance<DownloadEvent.Downloaded>().single()
            assertEquals(sha256(minimalPack.readBytes()), downloaded.sha256)
            val finished = (events.last() as DownloadEvent.Import).event as ImportEvent.Finished
            val ready = finished.result as ImportResult.Ready
            assertEquals(listOf("app.mauri.fixtures.minimal"), ready.import.packs.map { it.id })
            assertEquals(emptyList<String>(), workDir.list().orEmpty().toList())
        }

    @Test
    fun failedVerificationNeverReachesTheImporter() =
        runTest {
            server.enqueue(ok(indexJson(sha = "2".repeat(64))))
            server.enqueue(ok(minimalPack.readBytes()))
            val access = RepositoryAccess(server.at("/repository.json"))
            val snapshot = (testClient().fetchIndex(access) as RepoResult.Success).value.snapshot

            val events = downloader.downloadAndPrepare(snapshot.entries.first(), access).toList()

            assertTrue(events.last() is DownloadEvent.Failed)
            assertTrue(events.none { it is DownloadEvent.Import })
            assertEquals(emptyList<String>(), workDir.list().orEmpty().toList())
        }

    @Test
    fun directLinksAreImportedWithoutAnIndex() =
        runTest {
            server.enqueue(ok(minimalPack.readBytes()))
            val events = downloader.downloadAndPrepare(server.at("/minimal.soundpack.zip")).toList()
            val finished = (events.last() as DownloadEvent.Import).event as ImportEvent.Finished
            assertTrue("${finished.result}", finished.result is ImportResult.Ready)
        }
}
