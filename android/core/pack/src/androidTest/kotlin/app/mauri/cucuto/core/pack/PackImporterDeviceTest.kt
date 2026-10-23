package app.mauri.cucuto.core.pack

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mauri.cucuto.core.pack.media.MediaVerifier
import app.mauri.cucuto.core.pack.media.PlatformAudioProbe
import app.mauri.cucuto.core.pack.media.PlatformImageProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The whole pipeline on a device: a document Uri, the platform decoders, and installation into app storage. */
@RunWith(AndroidJUnit4::class)
class PackImporterDeviceTest {
    private val dir = DeviceFixtures.newDir("importer")
    private val store = PackStore(File(dir, "packs"))
    private val importer =
        PackImporter(
            store = store,
            workDir = File(dir, "work"),
            verifier = MediaVerifier(PlatformAudioProbe(), PlatformImageProbe()),
            ioDispatcher = Dispatchers.IO,
        )

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun uri(fixture: String): ImportSource {
        val file = DeviceFixtures.archive(fixture, dir)
        return ImportSource.of(DeviceFixtures.targetContext.contentResolver, Uri.fromFile(file))
    }

    @Test
    fun importsEveryValidPackageFixture() =
        runTest {
            for (fixture in listOf(
                DeviceFixtures.archivePath("valid/minimal-pack"),
                "valid/all-formats",
                "valid/full-pack",
                "valid/bundle",
            )) {
                val result = importer.prepare(uri(fixture)).result()
                assertTrue("$fixture: $result", result is ImportResult.Ready)
                val prepared = (result as ImportResult.Ready).import
                assertEquals("$fixture: ${prepared.report.issues}", emptyList<String>(), prepared.report.errorCodes())
                val installed = importer.install(prepared).result() as ImportResult.Installed
                assertEquals(prepared.packs.map { it.id }, installed.packs.map { it.id })
                for (pack in installed.packs) {
                    assertTrue(File(pack.directory, "pack.json").isFile)
                    assertEquals(pack.version, store.activeVersion(pack.id))
                }
            }
        }

    @Test
    fun rejectsMediaTheDeviceCannotDecode() =
        runTest {
            for (fixture in listOf("invalid/audio-undecodable", "invalid/image-undecodable")) {
                val result = importer.prepare(uri(fixture)).result()
                assertEquals(fixture, listOf("MEDIA_UNDECODABLE"), (result as ImportResult.Invalid).report.errorCodes())
            }
            assertTrue(store.installedPacks().isEmpty())
        }
}
