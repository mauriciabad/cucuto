package app.mauri.cucuto.packspec

import app.mauri.cucuto.packspec.authoring.BundleAssembler
import app.mauri.cucuto.packspec.authoring.BundleDraft
import app.mauri.cucuto.packspec.authoring.BundleProblem
import app.mauri.cucuto.packspec.authoring.DraftFile
import app.mauri.cucuto.packspec.authoring.PackAssembler
import app.mauri.cucuto.packspec.authoring.PackEditing
import app.mauri.cucuto.packspec.authoring.RepositoryIndexer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.relativeTo

/** Opening packs for editing, bundles and the repository indexer (#191), shared by packer and the website. */
class AuthoringToolsTest {
    private val valid = Path.of(System.getProperty("cucuto.specDir")).resolve("fixtures/valid")

    private fun files(folder: Path): Map<String, ByteArray> =
        Files.walk(folder).use { paths ->
            paths
                .filter { it.isRegularFile() }
                .toList()
                .associate { it.relativeTo(folder).toString().replace('\\', '/') to it.readBytes() }
        }

    private fun manifest(folder: Path): PackManifest {
        val content = JvmPackIo.validatePath(folder).content
        return (content as PackContent.Single).pack.manifest
    }

    /** [m] with every file path blanked, `type` dropped and an explicit `auto` haptic removed: what an edit keeps. */
    private fun kept(m: PackManifest) =
        m.copy(
            type = null,
            cover = m.cover?.let { "" },
            characters =
                m.characters.map { c ->
                    c.copy(
                        sprite = "",
                        spriteAnimated = c.spriteAnimated?.let { "" },
                        clips = c.clips.map { it.copy(file = "") },
                        ringtones = c.ringtones.map { it.copy(file = "") },
                        haptic = c.haptic?.takeIf { it.mode != "auto" },
                    )
                },
        )

    @Test
    fun `an opened pack is written back with everything the format allows`() {
        for (name in listOf("full-pack", "minimal-pack", "ringtone-song", "all-formats", "with-warnings")) {
            val folder = valid.resolve(name)
            val original = manifest(folder)
            val draft = PackEditing.draftOf(original)
            assertEquals(original.id, draft.id, name)
            assertEquals(original.version, draft.version, name)
            assertEquals(emptyList<Any>(), PackAssembler.problems(draft), name)

            val pack = PackAssembler.assemble(draft)
            assertEquals(kept(original), kept(pack.manifest), name)

            // The files come from the archive by their keys (the original paths), and the result passes every rule.
            val source = files(folder)
            val written = mapOf("pack.json" to pack.manifestJson.encodeToByteArray()) +
                pack.files.associate { it.path to source.getValue(it.key) }
            val report = PackValidator().validate(InMemoryPackSource(written)).report
            assertTrue(report.isValid, "$name: ${report.errors}")
        }
    }

    @Test
    fun `an opened pack saved as a copy gets a derived ID`() {
        val draft = PackEditing.draftOf(manifest(valid.resolve("minimal-pack"))).copy(id = null, version = "1.0.0")
        val pack = PackAssembler.assemble(draft)
        assertTrue(pack.manifest.id.startsWith("local."), pack.manifest.id)
        assertEquals("1.0.0", pack.manifest.version)
    }

    @Test
    fun `bundles are laid out like packer bundle and pass every rule`() {
        val first = files(valid.resolve("bundle/packs/first"))
        val second = files(valid.resolve("bundle/packs/second"))
        val ids = listOf(first, second).map { SpecJson.decodeFromString(PackManifest.serializer(), it.getValue("pack.json").decodeToString()).id }
        val draft =
            BundleDraft(
                id = "io.github.me.everything",
                name = " Everything ",
                author = "Me",
                license = "CC0-1.0",
                description = "  ",
                cover = DraftFile("cover", "png"),
                packIds = ids,
            )
        assertEquals(emptyList<BundleProblem>(), BundleAssembler.problems(draft))
        val bundle = BundleAssembler.assemble(draft)
        assertEquals(ids.map { "packs/$it" }, bundle.roots)
        assertEquals("cover.png", bundle.coverPath)
        assertEquals("Everything", bundle.manifest.name)
        assertNull(bundle.manifest.description)
        assertEquals("everything.soundpack.zip", BundleAssembler.fileName(draft))

        val written = buildMap {
            put("bundle.json", bundle.manifestJson.encodeToByteArray())
            put("cover.png", valid.resolve("bundle/cover.png").readBytes())
            first.forEach { (path, bytes) -> put("${bundle.roots[0]}/$path", bytes) }
            second.forEach { (path, bytes) -> put("${bundle.roots[1]}/$path", bytes) }
        }
        val validation = PackValidator().validate(InMemoryPackSource(written))
        assertTrue(validation.report.isValid, validation.report.errors.toString())
        assertTrue(validation.content is PackContent.Bundle)
    }

    @Test
    fun `bundle drafts say what they still need`() {
        val draft = BundleDraft(id = "Not An ID", name = "", version = "1", packIds = emptyList(), cover = DraftFile("c", "ogg"))
        assertEquals(
            listOf(
                BundleProblem.BUNDLE_ID_INVALID,
                BundleProblem.BUNDLE_NAME_MISSING,
                BundleProblem.VERSION_INVALID,
                BundleProblem.NO_PACKS,
                BundleProblem.UNSUPPORTED_FILE,
            ),
            BundleAssembler.problems(draft),
        )
        val twice = BundleDraft(id = "a.b", name = "x", packIds = listOf("a.b", "a.b"))
        assertEquals(listOf(BundleProblem.PACK_TWICE, BundleProblem.ID_CLASH), BundleAssembler.problems(twice))
        assertEquals(listOf(BundleProblem.TOO_MANY_PACKS), BundleAssembler.problems(twice.copy(id = "c.d", packIds = (0..100).map { "p.p$it" })))
    }

    @Test
    fun `repository entries carry previews with relative paths and the index keeps the latest versions`() {
        val content = JvmPackIo.validatePath(valid.resolve("full-pack")).content!!
        val download = Download(RepositoryIndexer.downloadUrl("full.soundpack.zip", null), 1234, "a".repeat(64))
        val indexed = RepositoryIndexer.entry(content, 99, download)
        val entry = indexed.entry
        assertEquals("packs/full.soundpack.zip", entry.download.url)
        assertEquals("previews/app.mauri.fixtures.full/cover.svg", entry.cover)
        assertEquals("previews/app.mauri.fixtures.full/robin.svg", entry.characters!![0].sprite)
        assertEquals("previews/app.mauri.fixtures.full/robin-sample.ogg", entry.characters[0].sample)
        assertNull(entry.characters[2].sample, "a ringtone-only character has no sample")
        assertTrue(indexed.previews.contains(RepositoryIndexer.Preview("characters/robin/clips/chirp.ogg", "previews/app.mauri.fixtures.full/robin-sample.ogg")))
        assertEquals("https://example.com/dl/x.zip", RepositoryIndexer.downloadUrl("x.zip", " https://example.com/dl/ "))

        val older = entry.copy(version = "1.0.0")
        val newer = entry.copy(version = "3.0.0")
        val other = entry.copy(id = "a.first")
        val index = RepositoryIndexer.index(" Mine ", "", null, null, listOf(older, newer, entry, other))
        assertEquals(listOf("a.first", entry.id), index.packs.map { it.id })
        assertEquals("3.0.0", index.packs[1].version)
        assertEquals("Mine", index.name)
        assertNull(index.description)
        val report = DocumentValidator.validate(DocumentKind.REPOSITORY, RepositoryIndexer.json(index).encodeToByteArray())
        assertTrue(report.isValid, report.issues.toString())

        val bundle = JvmPackIo.validatePath(valid.resolve("bundle")).content!!
        val bundled = RepositoryIndexer.entry(bundle, 1, download).entry
        assertEquals("bundle", bundled.type)
        assertEquals("previews/app.mauri.fixtures.bundle/cover.png", bundled.cover)
        assertEquals(2, bundled.packs!!.size)
        assertNull(bundled.characters)
        assertTrue(
            bundled.packs.flatMap { it.characters.orEmpty() }.all { it.haptic != null },
            "bundled characters with a sample carry their haptic too",
        )
    }

    @Test
    fun `characters with a sample carry the pack's haptic as x-haptic, and the index reads back the same`() {
        val content = JvmPackIo.validatePath(valid.resolve("full-pack")).content!!
        val manifest = (content as PackContent.Single).pack.manifest
        val entry = RepositoryIndexer.entry(content, 1, Download("packs/full.soundpack.zip", 1234, "a".repeat(64))).entry
        val (robin, song, ringer) = entry.characters!!
        assertEquals(manifest.characters[0].haptic, robin.haptic, "custom: the pattern as the pack has it")
        assertEquals("custom", robin.haptic!!.mode)
        assertEquals(Haptic("none"), song.haptic)
        assertNull(ringer.haptic, "no sample, nothing to preview")
        val minimal = RepositoryIndexer.entry(JvmPackIo.validatePath(valid.resolve("minimal-pack")).content!!, 1, entry.download).entry
        assertEquals(Haptic("auto"), minimal.characters!!.single().haptic, "a pack without a haptic is auto")

        val json = RepositoryIndexer.json(RepositoryIndexer.index("Mine", null, null, null, listOf(entry, minimal)))
        assertTrue(DocumentValidator.validate(DocumentKind.REPOSITORY, json.encodeToByteArray()).isValid)
        assertTrue("\"x-haptic\": {" in json, json)
        assertTrue("\"haptic\"" !in json.replace("\"x-haptic\"", ""), "only the extension key, which older apps accept")
        val read = SpecJson.decodeFromString(RepositoryIndex.serializer(), json)
        assertEquals(listOf(minimal, entry).sortedBy { it.id }, read.packs)
    }

    @Test
    fun `the schema checks x-haptic, and an index without it stays valid`() {
        fun index(preview: String) =
            """{"formatVersion":1,"name":"R","packs":[{"id":"a.b","type":"pack","name":"P","version":"1.0.0",""" +
                """"characters":[{"id":"c","name":"C",$preview"sample":"c.ogg"}],""" +
                """"download":{"url":"p.zip","size":100,"sha256":"${"a".repeat(64)}"}}]}"""

        fun valid(preview: String) = DocumentValidator.validate(DocumentKind.REPOSITORY, index(preview).encodeToByteArray()).isValid
        assertTrue(valid(""))
        assertTrue(valid(""""x-haptic":{"mode":"auto"},"""))
        assertTrue(valid(""""x-haptic":{"mode":"custom","events":[{"at":0,"preset":"TICK"}]},"""))
        assertTrue(!valid(""""x-haptic":{"mode":"custom"},"""), "custom needs events")
        assertTrue(!valid(""""x-haptic":{"mode":"loud"},"""))
        assertTrue(!valid(""""haptic":{"mode":"auto"},"""), "older apps would reject a plain key, so the format has none")
    }
}
