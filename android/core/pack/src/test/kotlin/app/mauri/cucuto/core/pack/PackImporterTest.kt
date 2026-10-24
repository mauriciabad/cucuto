package app.mauri.cucuto.core.pack

import app.mauri.cucuto.core.pack.media.AudioProbeResult
import app.mauri.cucuto.core.pack.media.ImageProbeResult
import app.mauri.cucuto.packspec.ErrorCode
import app.mauri.cucuto.packspec.RingFiles
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

class PackImporterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var archives: File

    private val minimalId = "app.mauri.fixtures.minimal"

    @Before
    fun setUp() {
        root = tmp.newFolder("app")
        archives = tmp.newFolder("archives")
    }

    private fun importer(
        audio: app.mauri.cucuto.core.pack.media.AudioProbe = headerAudioProbe,
        image: app.mauri.cucuto.core.pack.media.ImageProbe = headerImageProbe,
        analyzer: PackAnalyzer? = null,
    ) = testImporter(root, audio = audio, image = image, analyzer = analyzer)

    private val store get() = PackStore(File(root, "files/packs"))

    /** The minimal fixture pack with another version. */
    private fun minimal(version: String): File =
        zipFolder(File(fixturesDir, "valid/minimal-pack"), File(archives, "minimal-$version.zip")) { path, bytes ->
            if (path != "pack.json") return@zipFolder bytes
            bytes.decodeToString().replace("\"1.0.0\"", "\"$version\"").encodeToByteArray()
        }

    private suspend fun PackImporter.ready(file: File): PreparedImport {
        val result = prepare(ImportSource.of(file)).result()
        return (result as? ImportResult.Ready)?.import ?: throw AssertionError("Expected Ready, got $result")
    }

    private suspend fun PackImporter.installAll(
        file: File,
        activate: Boolean = true,
    ): ImportResult.Installed {
        val prepared = ready(file)
        return install(prepared, PackSelection.all(prepared), activate).result() as ImportResult.Installed
    }

    @Test
    fun `installs a pack with its manifest, media and licenses only`() =
        runTest {
            val importer = importer()
            val archive = fixtureArchive("valid/full-pack", archives)
            val prepared = importer.ready(archive)
            val sha256 =
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(archive.readBytes())
                    .joinToString("") { "%02x".format(it) }
            assertEquals(sha256, prepared.archiveSha256)
            assertEquals(archive.length(), prepared.archiveSizeBytes)
            val candidate = prepared.packs.single()
            assertEquals(InstallAction.INSTALL, candidate.action)
            assertNull(candidate.installedVersion)
            assertFalse(prepared.isBundle)

            val result = importer.install(prepared).result() as ImportResult.Installed
            val pack = result.packs.single()
            assertTrue(pack.active)
            assertNull(pack.previousVersion)
            val files = filesIn(pack.directory)
            assertTrue(files.toString(), "pack.json" in files && "LICENSE" in files && "README.md" in files)
            assertTrue(files.toString(), "characters/song/ringtones/day.ogg" in files)
            assertTrue(files.toString(), "characters/robin/sprite.svg" in files)
            assertEquals(candidate.fileCount, files.size)
            assertEquals(store.packDir(candidate.id, candidate.version), pack.directory)
            assertEquals(candidate.version, store.activeVersion(candidate.id))
            assertEquals(candidate.manifest, store.activeManifest(candidate.id))
            assertNoLeftovers()
        }

    @Test
    fun `ringtone variants and loop settings are stored for ring files`() =
        runTest {
            // A song with only ringtones (no clips): stored with its loop settings, which #52 renders into ring files.
            val result = importer().installAll(fixtureArchive("valid/ringtone-song", archives))
            val pack = result.packs.single()
            val song = store.activeManifest(pack.id)!!.characters.single()
            assertTrue(song.clips.isEmpty())
            assertEquals(listOf("Intro and loop", "Last second", "Once"), song.ringtones.map { it.label })
            assertTrue("characters/song/song.ogg" in filesIn(pack.directory))
            val plans = song.ringtones.map { RingFiles.plan(it, 6_000) }
            assertEquals(listOf(1_000L, 5_000L, 0L), plans.map { it.loopStartMs })
            assertEquals(listOf(93_000L, 90_000L, 90_000L), plans.map { it.totalMs })
            assertEquals(10_000L, plans.first().fadeInMs)
            assertTrue(plans.last().playOnce)
            assertNoLeftovers()
        }

    @Test
    fun `skips operating system files and unreferenced media`() =
        runTest {
            val importer = importer()
            val result = importer.installAll(fixtureArchive("valid/with-warnings", archives))
            val files = filesIn(result.packs.single().directory)
            assertEquals(setOf("pack.json", "characters/beep/sprite.png", "characters/beep/clips/beep.ogg"), files)
        }

    @Test
    fun `reports progress for every step`() =
        runTest {
            val source = ImportSource.of(fixtureArchive(archiveFixture("valid/minimal-pack"), archives))
            val events = importer().prepare(source).toList()
            val steps = events.filterIsInstance<ImportEvent.Progress>().map { it.progress::class }.distinct()
            assertEquals(
                listOf(
                    ImportProgress.Copying::class,
                    ImportProgress.Validating::class,
                    ImportProgress.Extracting::class,
                    ImportProgress.Decoding::class,
                ),
                steps,
            )
            assertTrue(events.last() is ImportEvent.Finished)
            assertEquals(1, events.count { it is ImportEvent.Finished })
            ((events.last() as ImportEvent.Finished).result as ImportResult.Ready).import.let { importer().discard(it) }
        }

    @Test
    fun `a higher version updates in place and replaces the old one`() =
        runTest {
            val importer = importer()
            importer.installAll(minimal("1.0.0"))
            val prepared = importer.ready(minimal("1.1.0"))
            assertEquals(InstallAction.UPDATE, prepared.packs.single().action)
            assertEquals("1.0.0", prepared.packs.single().installedVersion)
            assertTrue(prepared.report.warnings.isEmpty())

            val pack = (importer.install(prepared).result() as ImportResult.Installed).packs.single()
            assertEquals("1.0.0", pack.previousVersion)
            assertEquals(InstalledPackInfo(minimalId, "1.1.0", listOf("1.1.0")), store.info(minimalId))
        }

    @Test
    fun `without activation the old version stays until the switch`() =
        runTest {
            val importer = importer()
            importer.installAll(minimal("1.0.0"))
            val pack = importer.installAll(minimal("2.0.0-beta.1"), activate = false).packs.single()
            assertFalse(pack.active)
            assertEquals(InstalledPackInfo(minimalId, "1.0.0", listOf("1.0.0", "2.0.0-beta.1")), store.info(minimalId))

            store.activate(minimalId, "2.0.0-beta.1")
            assertEquals(InstalledPackInfo(minimalId, "2.0.0-beta.1", listOf("2.0.0-beta.1")), store.info(minimalId))
        }

    /** Writes the clips it was asked about to `.cucuto/analysis.txt`, like auto haptics writes its file. */
    private class RecordingAnalyzer(
        private val fail: Boolean = false,
    ) : PackAnalyzer {
        val packs = mutableListOf<String>()

        override suspend fun analyze(
            packDir: File,
            manifest: app.mauri.cucuto.packspec.PackManifest,
            progress: suspend (done: Int, total: Int) -> Unit,
        ) {
            packs += manifest.id
            if (fail) throw IllegalStateException("analysis bug")
            val clips = manifest.characters.flatMap { c -> c.clips.map { it.file } }
            clips.indices.forEach { progress(it, clips.size) }
            progress(clips.size, clips.size)
            File(packDir, ".cucuto").mkdir()
            File(packDir, ".cucuto/analysis.txt").writeText(clips.joinToString("\n"))
        }
    }

    @Test
    fun `the analysis is installed together with its pack`() =
        runTest {
            val analyzer = RecordingAnalyzer()
            val importer = importer(analyzer = analyzer)
            val prepared = importer.ready(fixtureArchive("valid/full-pack", archives))
            // Nothing is analysed before the user confirms.
            assertTrue(analyzer.packs.isEmpty())

            val events = importer.install(prepared).toList()
            val pack = ((events.last() as ImportEvent.Finished).result as ImportResult.Installed).packs.single()
            val clips =
                prepared.packs
                    .single()
                    .manifest.characters
                    .flatMap { c -> c.clips.map { it.file } }
            assertEquals(clips.joinToString("\n"), File(pack.directory, ".cucuto/analysis.txt").readText())
            val analyzing = events.mapNotNull { (it as? ImportEvent.Progress)?.progress as? ImportProgress.Analyzing }
            assertEquals(ImportProgress.Analyzing(pack.id, clips.size, clips.size), analyzing.last())
            // Analysis comes before the packs move into place.
            val steps = events.filterIsInstance<ImportEvent.Progress>().map { it.progress::class }.distinct()
            assertEquals(listOf(ImportProgress.Analyzing::class, ImportProgress.Installing::class), steps)
            assertNoLeftovers()
        }

    @Test
    fun `a failing analysis never fails the import`() =
        runTest {
            val analyzer = RecordingAnalyzer(fail = true)
            val result = importer(analyzer = analyzer).installAll(minimal("1.0.0"))
            assertEquals(listOf(minimalId), analyzer.packs)
            assertTrue(result.packs.single().active)
            assertFalse(File(result.packs.single().directory, ".cucuto").exists())
        }

    @Test
    fun `packs that will not be installed are not analysed`() =
        runTest {
            importer().installAll(minimal("1.0.0"))
            val analyzer = RecordingAnalyzer()
            val importer = importer(analyzer = analyzer)
            // An unconfirmed reinstall is skipped.
            val result = importer.install(importer.ready(minimal("1.0.0"))).result() as ImportResult.Installed
            assertTrue(result.packs.isEmpty())
            // A bundle pack the user left out.
            val bundle = importer.ready(fixtureArchive("valid/bundle", archives))
            val ids = bundle.packs.map { it.id }
            importer.install(bundle, PackSelection.default(bundle).with(ids[1], selected = false)).result()
            assertEquals(listOf(ids[0]), analyzer.packs)
        }

    @Test
    fun `the same version needs a confirmation`() =
        runTest {
            val importer = importer()
            importer.installAll(minimal("1.0.0"))

            val first = importer.ready(minimal("1.0.0"))
            assertEquals(InstallAction.REINSTALL, first.packs.single().action)
            assertEquals(listOf("PACK_SAME_VERSION"), first.report.warningCodes())
            val skipped = importer.install(first).result() as ImportResult.Installed
            assertTrue(skipped.packs.isEmpty())
            assertEquals(minimalId, skipped.skipped.single().id)

            val second = importer.ready(minimal("1.0.0"))
            val selection = PackSelection.default(second)
            assertEquals(1, selection.unconfirmed(second).size)
            val installed = importer.install(second, selection.confirm(minimalId)).result() as ImportResult.Installed
            assertEquals("1.0.0", installed.packs.single().version)
            assertEquals(InstalledPackInfo(minimalId, "1.0.0", listOf("1.0.0")), store.info(minimalId))
            assertNoLeftovers()
        }

    @Test
    fun `a lower version is a confirmed downgrade`() =
        runTest {
            val importer = importer()
            importer.installAll(minimal("1.2.0"))
            val prepared = importer.ready(minimal("1.0.0"))
            assertEquals(InstallAction.DOWNGRADE, prepared.packs.single().action)
            assertEquals(listOf("PACK_DOWNGRADE"), prepared.report.warningCodes())
            importer.install(prepared, PackSelection.all(prepared)).result()
            assertEquals(InstalledPackInfo(minimalId, "1.0.0", listOf("1.0.0")), store.info(minimalId))
        }

    @Test
    fun `bundle packs install on their own and only when selected`() =
        runTest {
            val importer = importer()
            val prepared = importer.ready(fixtureArchive("valid/bundle", archives))
            assertTrue(prepared.isBundle)
            assertEquals("app.mauri.fixtures.bundle", prepared.bundle?.id)
            val ids = prepared.packs.map { it.id }
            assertEquals(2, ids.size)
            assertEquals(ids.toSet(), PackSelection.default(prepared).selected)

            val selection = PackSelection.default(prepared).with(ids[1], selected = false)
            val result = importer.install(prepared, selection).result() as ImportResult.Installed
            assertEquals(listOf(ids[0]), result.packs.map { it.id })
            assertEquals(listOf(ids[0]), store.installedPacks().map { it.id })
            // Each pack is installed as if imported alone: its pack.json at the root of its folder.
            assertTrue(File(result.packs.single().directory, "pack.json").isFile)
            assertNoLeftovers()
        }

    @Test
    fun `a file the device cannot decode is rejected`() =
        runTest {
            val importer = importer(audio = { _, _, _ -> AudioProbeResult.Undecodable("no decoder") })
            val result = importer.prepare(ImportSource.of(fixtureArchive("valid/minimal-pack", archives))).result()
            assertEquals(listOf("MEDIA_UNDECODABLE"), (result as ImportResult.Invalid).report.errorCodes())
            assertNoLeftovers()
        }

    @Test
    fun `decoded durations and sizes are checked again`() =
        runTest {
            val longClip = importer(audio = { _, _, _ -> AudioProbeResult.Decoded(31_000) })
            val clip = longClip.prepare(ImportSource.of(fixtureArchive("valid/minimal-pack", archives))).result()
            assertEquals(listOf("CLIP_DURATION_OUT_OF_RANGE"), (clip as ImportResult.Invalid).report.errorCodes())

            val bigImage = importer(image = { _, _ -> ImageProbeResult.Decoded(1024, 16) })
            val image = bigImage.prepare(ImportSource.of(fixtureArchive("valid/minimal-pack", archives))).result()
            assertEquals(listOf("IMAGE_TOO_LARGE"), (image as ImportResult.Invalid).report.errorCodes())

            val noJxlDecoder = importer(image = { _, _ -> ImageProbeResult.NotChecked("no decoder") })
            val ok = noJxlDecoder.prepare(ImportSource.of(fixtureArchive("valid/minimal-pack", archives))).result()
            noJxlDecoder.discard((ok as ImportResult.Ready).import)
        }

    @Test
    fun `discarding deletes the unpacked files and uses the import up`() =
        runTest {
            val importer = importer()
            val prepared = importer.ready(fixtureArchive("valid/minimal-pack", archives))
            assertTrue(prepared.stagingDir.isDirectory)
            val staged = prepared.stagedFolder(prepared.packs.single())
            assertTrue(File(staged, "pack.json").isFile)
            importer.discard(prepared)
            assertFalse(prepared.stagingDir.exists())
            assertNull(prepared.stagedFolder(prepared.packs.single()))
            val result = importer.install(prepared).result()
            assertEquals(FailureReason.NOT_PREPARED, (result as ImportResult.Failed).reason)
            assertTrue(store.installedPacks().isEmpty())
        }

    @Test
    fun `source failures are reported, not thrown`() =
        runTest {
            val huge =
                object : ImportSource {
                    override fun describe() = ImportSource.Description("huge.zip", PackImporter.MAX_ARCHIVE_BYTES + 1)

                    override fun open(): InputStream = ByteArrayInputStream(ByteArray(0))
                }
            assertEquals(FailureReason.SOURCE_TOO_LARGE, failure(huge))

            val broken =
                object : ImportSource {
                    override fun describe() = ImportSource.Description(null, null)

                    override fun open(): InputStream =
                        object : InputStream() {
                            override fun read(): Int = throw IOException("connection reset")
                        }
                }
            assertEquals(FailureReason.SOURCE_UNREADABLE, failure(broken))

            val missing = ImportSource.of(File(archives, "missing.soundpack.zip"))
            assertEquals(FailureReason.SOURCE_UNREADABLE, failure(missing))
            assertNoLeftovers()
        }

    @Test
    fun `invalid archives leave nothing behind`() =
        runTest {
            val source = ImportSource.of(fixtureArchive(archiveFixture("invalid/zip-slip"), archives))
            val result = importer().prepare(source).result()
            assertEquals(listOf(ErrorCode.UNSAFE_PATH.name), (result as ImportResult.Invalid).report.errorCodes())
            assertNoLeftovers()
            assertFalse(File(root, "files").walkTopDown().any { it.isFile })
        }

    @Test
    fun `leftovers of interrupted imports are deleted`() =
        runTest {
            val importer = importer()
            val prepared = importer.ready(fixtureArchive("valid/minimal-pack", archives))
            File(root, "cache/pack-import/stale.zip").apply { parentFile!!.mkdirs() }.writeText("x")
            importer.deleteLeftovers()
            assertFalse(prepared.stagingDir.exists())
            assertNoLeftovers()
        }

    @Test
    fun `uninstall removes every version`() =
        runTest {
            val importer = importer()
            importer.installAll(minimal("1.0.0"))
            importer.installAll(minimal("1.1.0"), activate = false)
            assertTrue(importer.uninstall(minimalId))
            assertFalse(importer.uninstall(minimalId))
            assertTrue(store.installedPacks().isEmpty())
            assertNull(store.activeVersion(minimalId))
        }

    @Test
    fun `install actions follow SemVer precedence`() {
        assertEquals(InstallAction.INSTALL, PackImporter.actionFor("1.0.0", null))
        assertEquals(InstallAction.UPDATE, PackImporter.actionFor("1.10.0", "1.9.0"))
        assertEquals(InstallAction.UPDATE, PackImporter.actionFor("1.0.0", "1.0.0-rc.1"))
        assertEquals(InstallAction.REINSTALL, PackImporter.actionFor("1.0.0", "1.0.0"))
        assertEquals(InstallAction.DOWNGRADE, PackImporter.actionFor("1.0.0-beta", "1.0.0"))
        assertTrue(InstallAction.DOWNGRADE.needsConfirmation && InstallAction.REINSTALL.needsConfirmation)
        assertFalse(InstallAction.UPDATE.needsConfirmation || InstallAction.INSTALL.needsConfirmation)
    }

    private suspend fun failure(source: ImportSource): FailureReason =
        (importer().prepare(source).result() as ImportResult.Failed).reason

    private fun filesIn(dir: File): Set<String> =
        dir
            .walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(dir).invariantSeparatorsPath }
            .toSet()

    private fun assertNoLeftovers() {
        assertTrue(File(root, "cache/pack-import").listFiles().orEmpty().isEmpty())
        assertTrue(File(root, "files/packs/.staging").listFiles().orEmpty().isEmpty())
        assertTrue(File(root, "files/packs/.trash").listFiles().orEmpty().isEmpty())
    }
}
