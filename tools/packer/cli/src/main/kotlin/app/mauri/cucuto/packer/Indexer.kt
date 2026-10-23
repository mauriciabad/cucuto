package app.mauri.cucuto.packer

import app.mauri.cucuto.packspec.BundledPack
import app.mauri.cucuto.packspec.CharacterPreview
import app.mauri.cucuto.packspec.DocumentKind
import app.mauri.cucuto.packspec.DocumentValidator
import app.mauri.cucuto.packspec.Download
import app.mauri.cucuto.packspec.FileInput
import app.mauri.cucuto.packspec.JvmPackIo
import app.mauri.cucuto.packspec.Limits
import app.mauri.cucuto.packspec.PackContent
import app.mauri.cucuto.packspec.PackEntry
import app.mauri.cucuto.packspec.PackPaths
import app.mauri.cucuto.packspec.RepositoryEntry
import app.mauri.cucuto.packspec.RepositoryIndex
import app.mauri.cucuto.packspec.SemVer
import app.mauri.cucuto.packspec.SpecJson
import app.mauri.cucuto.packspec.zip.ZipArchive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/** Builds a repository folder: repository.json, previews/ and (unless downloads live elsewhere) packs/. */
class Indexer(private val log: (String) -> Unit) {
    data class Options(
        val name: String,
        val description: String? = null,
        val homepage: String? = null,
        val downloadBaseUrl: String? = null,
        val generatedAt: String? = null,
    )

    fun index(inputs: List<Path>, outDir: Path, options: Options): RepositoryIndex {
        val archives = inputs.flatMap { p ->
            if (p.isDirectory()) p.listDirectoryEntries("*.soundpack").sortedBy { it.name } else listOf(p)
        }
        if (archives.isEmpty()) throw PackerException("No .soundpack files to index")
        Files.createDirectories(outDir)
        val entries = mutableMapOf<String, Pair<RepositoryEntry, SemVer>>()
        for (file in archives) {
            val validation = JvmPackIo.validatePath(file)
            if (!validation.report.isValid) {
                throw PackerException("$file is not a valid pack:\n" + validation.report.errors.joinToString("\n") { "  $it" })
            }
            val content = validation.content!!
            val entry = entry(file, content, outDir, options)
            val version = SemVer.parse(entry.version)!!
            val existing = entries[entry.id]
            if (existing != null) {
                log("  ${entry.id}: keeping the highest of ${existing.first.version} and ${entry.version}")
                if (existing.second >= version) continue
            }
            entries[entry.id] = entry to version
            log("  indexed ${entry.id} ${entry.version} (${file.name})")
        }
        val index = RepositoryIndex(
            formatVersion = Limits.FORMAT_VERSION,
            name = options.name,
            description = options.description,
            homepage = options.homepage,
            generatedAt = options.generatedAt,
            packs = entries.values.map { it.first }.sortedBy { it.id },
        )
        val json = SpecJson.encodeToString(RepositoryIndex.serializer(), index) + "\n"
        val report = DocumentValidator.validate(DocumentKind.REPOSITORY, json.encodeToByteArray())
        if (!report.isValid) throw PackerException("Generated index is invalid:\n" + report.issues.joinToString("\n") { "  $it" })
        outDir.resolve("repository.json").writeText(json)
        return index
    }

    private fun entry(file: Path, content: PackContent, outDir: Path, options: Options): RepositoryEntry {
        val (zip, unpacked) = FileInput(file).use { input ->
            val z = ZipArchive.open(input, JvmPackIo.inflater).source!!
            val previews = mutableMapOf<String, ByteArray>()
            // Read everything the previews need while the file is open.
            for (pack in content.packs) {
                val m = pack.manifest
                listOfNotNull(m.cover).plus(m.characters.flatMap { c -> listOfNotNull(c.sprite, c.clips.firstOrNull()?.file) })
                    .forEach { previews[PackPaths.join(pack.root, it)] = z.read(PackPaths.join(pack.root, it)) }
            }
            if (content is PackContent.Bundle) content.manifest.cover?.let { previews[it] = z.read(it) }
            previews to z.entries.sumOf { it.size }
        }
        val download = download(file, outDir, options)
        fun copy(archivePath: String, previewPath: String): String {
            val target = outDir.resolve(previewPath)
            Files.createDirectories(target.parent)
            target.writeBytes(zip.getValue(archivePath))
            return previewPath
        }
        fun ext(p: String) = p.substringAfterLast('.')
        fun previews(pack: PackEntry, prefix: String): Pair<String?, List<CharacterPreview>> {
            val m = pack.manifest
            val cover = m.cover?.let { copy(PackPaths.join(pack.root, it), "$prefix/cover.${ext(it)}") }
            val chars = m.characters.map { c ->
                CharacterPreview(
                    id = c.id,
                    name = c.name,
                    sprite = copy(PackPaths.join(pack.root, c.sprite), "$prefix/${c.id}.${ext(c.sprite)}"),
                    sample = c.clips.firstOrNull()?.let { copy(PackPaths.join(pack.root, it.file), "$prefix/${c.id}-sample.${ext(it.file)}") },
                    seedColor = c.seedColor,
                )
            }
            return cover to chars
        }
        return when (content) {
            is PackContent.Single -> {
                val m = content.pack.manifest
                val (cover, chars) = previews(content.pack, "previews/${m.id}")
                RepositoryEntry(
                    id = m.id, type = "pack", packFormatVersion = m.formatVersion, name = m.name, version = m.version,
                    author = m.author, license = m.license, description = m.description, tags = m.tags,
                    cover = cover ?: chars.firstOrNull()?.sprite, characters = chars, sizeUnpacked = unpacked, download = download,
                )
            }
            is PackContent.Bundle -> {
                val b = content.manifest
                val packs = content.packs.map { p ->
                    val (cover, chars) = previews(p, "previews/${b.id}/${p.manifest.id}")
                    BundledPack(p.manifest.id, p.manifest.name, p.manifest.version, p.manifest.license, cover, chars)
                }
                val cover = b.cover?.let { copy(it, "previews/${b.id}/cover.${ext(it)}") } ?: packs.firstNotNullOfOrNull { it.cover }
                RepositoryEntry(
                    id = b.id, type = "bundle", packFormatVersion = b.formatVersion, name = b.name, version = b.version,
                    author = b.author, license = b.license, description = b.description, tags = b.tags,
                    cover = cover, packs = packs, sizeUnpacked = unpacked, download = download,
                )
            }
        }
    }

    private fun download(file: Path, outDir: Path, options: Options): Download {
        val url = if (options.downloadBaseUrl != null) {
            options.downloadBaseUrl.trimEnd('/') + "/" + file.name
        } else {
            val target = outDir.resolve("packs").resolve(file.name)
            Files.createDirectories(target.parent)
            if (file.toAbsolutePath() != target.toAbsolutePath()) Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING)
            "packs/${file.name}"
        }
        return Download(url, file.fileSize(), sha256(file))
    }

    companion object {
        fun sha256(file: Path): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { s ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
