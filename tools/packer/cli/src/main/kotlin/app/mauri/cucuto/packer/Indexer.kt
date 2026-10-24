package app.mauri.cucuto.packer

import app.mauri.cucuto.packspec.DocumentKind
import app.mauri.cucuto.packspec.DocumentValidator
import app.mauri.cucuto.packspec.Download
import app.mauri.cucuto.packspec.FileInput
import app.mauri.cucuto.packspec.JvmPackIo
import app.mauri.cucuto.packspec.PackContent
import app.mauri.cucuto.packspec.RepositoryEntry
import app.mauri.cucuto.packspec.RepositoryIndex
import app.mauri.cucuto.packspec.authoring.RepositoryIndexer
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

/**
 * Builds a repository folder: repository.json, previews/ and (unless downloads live elsewhere) packs/. The layout,
 * previews and entries come from shared/pack-spec's `RepositoryIndexer`, which the website's repository helper runs
 * in the browser too.
 */
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
            // Any .zip in a folder; each one must be a valid pack or bundle (detected from its contents).
            if (p.isDirectory()) p.listDirectoryEntries("*.zip").sortedBy { it.name } else listOf(p)
        }
        if (archives.isEmpty()) throw PackerException("No pack archives (.zip) to index")
        Files.createDirectories(outDir)
        // Ringtone-only characters get a sample cut from their ringtone, which needs ffmpeg (ADR 0069, #325).
        val cutSamples = MediaTools.hasTool("ffmpeg") && MediaTools.hasTool("ffprobe")
        val entries = mutableListOf<RepositoryEntry>()
        for (file in archives) {
            val validation = JvmPackIo.validatePath(file)
            if (!validation.report.isValid) {
                throw PackerException("$file is not a valid pack:\n" + validation.report.errors.joinToString("\n") { "  $it" })
            }
            val entry = entry(file, validation.content!!, outDir, options, cutSamples)
            entries.firstOrNull { it.id == entry.id }?.let {
                log("  ${entry.id}: keeping the highest of ${it.version} and ${entry.version}")
            }
            entries += entry
            log("  indexed ${entry.id} ${entry.version} (${file.name})")
        }
        val index = RepositoryIndexer.index(options.name, options.description, options.homepage, options.generatedAt, entries)
        val json = RepositoryIndexer.json(index)
        val report = DocumentValidator.validate(DocumentKind.REPOSITORY, json.encodeToByteArray())
        if (!report.isValid) throw PackerException("Generated index is invalid:\n" + report.issues.joinToString("\n") { "  $it" })
        outDir.resolve(RepositoryIndexer.INDEX_FILE).writeText(json)
        return index
    }

    private fun entry(file: Path, content: PackContent, outDir: Path, options: Options, cutSamples: Boolean): RepositoryEntry {
        val download = download(file, outDir, options)
        return FileInput(file).use { input ->
            val zip = ZipArchive.open(input, JvmPackIo.inflater).source!!
            val indexed = RepositoryIndexer.entry(content, zip.entries.sumOf { it.size }, download, cutSamples)
            if (!cutSamples) {
                val songs = ringtoneOnly(content)
                if (songs.isNotEmpty()) log("  warning: no sample for ${songs.joinToString()} (ringtone only; cutting one needs ffmpeg)")
            }
            for (preview in indexed.previews) {
                val target = outDir.resolve(preview.path)
                Files.createDirectories(target.parent)
                val cut = preview.cut
                if (cut == null) {
                    target.writeBytes(zip.read(preview.archivePath))
                } else {
                    val source = Files.createTempFile("packer-sample-", "." + preview.archivePath.substringAfterLast('.'))
                    try {
                        source.writeBytes(zip.read(preview.archivePath))
                        val range = Ringtones.cutClip(source, target, fromMs = cut.fromMs, toMs = cut.toMs)
                        log("  cut ${preview.path} from ${preview.archivePath} (${range.startMs}..${range.endMs} ms)")
                    } finally {
                        Files.deleteIfExists(source)
                    }
                }
            }
            indexed.entry
        }
    }

    /** The characters of [content] with ringtones and no clips, as `pack/character`. */
    private fun ringtoneOnly(content: PackContent): List<String> {
        val packs = when (content) {
            is PackContent.Single -> listOf(content.pack)
            is PackContent.Bundle -> content.packs
        }
        return packs.flatMap { p ->
            p.manifest.characters.filter { it.clips.isEmpty() && it.ringtones.isNotEmpty() }.map { "${p.manifest.id}/${it.id}" }
        }
    }

    private fun download(file: Path, outDir: Path, options: Options): Download {
        if (options.downloadBaseUrl == null) {
            val target = outDir.resolve(RepositoryIndexer.PACKS_FOLDER).resolve(file.name)
            Files.createDirectories(target.parent)
            if (file.toAbsolutePath() != target.toAbsolutePath()) Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING)
        }
        return Download(RepositoryIndexer.downloadUrl(file.name, options.downloadBaseUrl), file.fileSize(), sha256(file))
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
