package app.mauri.cucuto.packer

import app.mauri.cucuto.packspec.BundleManifest
import app.mauri.cucuto.packspec.DocumentKind
import app.mauri.cucuto.packspec.DocumentValidator
import app.mauri.cucuto.packspec.FileFormat
import app.mauri.cucuto.packspec.FileKind
import app.mauri.cucuto.packspec.Issue
import app.mauri.cucuto.packspec.Limits
import app.mauri.cucuto.packspec.PackContent
import app.mauri.cucuto.packspec.PackPaths
import app.mauri.cucuto.packspec.PackValidator
import app.mauri.cucuto.packspec.Ringtone
import app.mauri.cucuto.packspec.SpecJson
import app.mauri.cucuto.packspec.ValidationReport
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readBytes

fun main(args: Array<String>) = Packer().subcommands(Build(), Validate(), Bundle(), Index(), Ring(), Cut()).main(args)

class Packer : CliktCommand(name = "packer") {
    override fun help(context: Context) =
        "Builds, validates and indexes Cucuto sound packs (.soundpack.zip). Pack format: docs/pack-format.md."

    override fun run() = Unit
}

/** Shared output helpers. */
private fun CliktCommand.printIssues(label: String, report: ValidationReport, quiet: Boolean = false) {
    if (report.issues.isEmpty()) {
        if (!quiet) echo("$label: OK")
        return
    }
    val status = if (report.isValid) "OK with ${report.warnings.size} warning(s)" else "INVALID (${report.errors.size} error(s), ${report.warnings.size} warning(s))"
    echo("$label: $status")
    for (issue in report.issues) echo("  ${format(issue)}")
}

private fun format(i: Issue): String = buildString {
    append(if (i.severity == app.mauri.cucuto.packspec.Severity.ERROR) "error   " else "warning ")
    append(i.code.name)
    i.file?.let { append("  ").append(it) }
    i.pointer?.let { append(" ").append(it) }
    append(": ").append(i.message)
}

class ProcessingOptions : OptionGroup("Media processing (opt-in; needs ffmpeg or cjxl on PATH)") {
    val normalize by option("--normalize", help = "Normalize loudness (EBU R128, -16 LUFS) with ffmpeg").flag()
    val trimSilence by option("--trim-silence", help = "Trim leading and trailing silence from clips with ffmpeg").flag()
    val opus by option("--opus", help = "Transcode audio to Ogg Opus (.ogg) with ffmpeg").flag()
    val jxl by option("--jxl", help = "Convert PNG, GIF and JPEG images to JPEG XL with cjxl").flag()
    fun options() = ProcessOptions(normalize, trimSilence, opus, jxl)
}

/** Validates an assembled pack, prints the report and fails the command on errors. */
private fun CliktCommand.validateOrFail(label: String, pack: AssembledPack): PackContent {
    val validation = PackValidator().validate(pack)
    printIssues(label, validation.report, quiet = true)
    if (!validation.report.isValid) throw ProgramResult(1)
    return validation.content!!
}

/**
 * Where `build` and `bundle` write: `<id>-<version>.soundpack.zip` in the current folder, in [output] when it is a
 * folder (or has no `.zip` name), or [output] itself when it ends in `.zip`. Archives always end in `.zip`
 * (ADR 0007); a bare `.soundpack` name is refused.
 */
private fun CliktCommand.outputFile(output: Path?, id: String, version: String): Path {
    val name = "$id-$version$PACK_SUFFIX"
    return when {
        output == null -> Path(name)
        output.isDirectory() -> output.resolve(name)
        output.name.endsWith(".zip", ignoreCase = true) -> output
        output.name.endsWith(".soundpack", ignoreCase = true) -> {
            echo("error: $output must end in .zip (for example ${output.name}.zip)", err = true); throw ProgramResult(2)
        }
        else -> output.resolve(name)
    }
}

/** File name suffix for packs and bundles (ADR 0007). Readers accept any `.zip` and detect the type from its contents. */
const val PACK_SUFFIX = ".soundpack.zip"

private fun CliktCommand.reportWritten(file: Path, pack: AssembledPack) {
    echo("Wrote $file (${pack.files.size} files, ${file.fileSize()} bytes, sha256 ${Indexer.sha256(file)})")
}

class Build : CliktCommand(name = "build") {
    override fun help(context: Context) =
        "Builds a .soundpack.zip from a folder with pack.yaml (or pack.json). Validates the result and writes a reproducible zip."

    private val source by argument(help = "Folder with pack.yaml or pack.json, or a pack.yaml file").path(mustExist = true)
    private val output by option("-o", "--output", help = "Output .soundpack.zip file or folder (default: <id>-<version>.soundpack.zip here)").path()
    private val processing by ProcessingOptions()

    override fun run() {
        val input = try {
            MediaTools { echo(it) }.process(PackSources.load(source), processing.options())
        } catch (e: PackerException) {
            echo("error: ${e.message}", err = true); throw ProgramResult(2)
        }
        val content = validateOrFail(source.toString(), input.pack)
        val (id, version) = when (content) {
            is PackContent.Single -> content.pack.manifest.id to content.pack.manifest.version
            is PackContent.Bundle -> content.manifest.id to content.manifest.version
        }
        val file = outputFile(output, id, version)
        DeterministicZip.write(input.pack, file)
        reportWritten(file, input.pack)
    }
}

class Bundle : CliktCommand(name = "bundle") {
    override fun help(context: Context) =
        "Combines packs (source folders or .soundpack.zip files) into one bundle .soundpack.zip. Each pack goes to packs/<pack id>/."

    private val inputs by argument(help = "Pack folders (pack.yaml or pack.json) or pack archives (.zip)").path(mustExist = true).multiple(required = true)
    private val id by option("--id", help = "Bundle ID, reverse-domain (for example io.github.me.all-packs)").required()
    private val bundleName by option("--name", help = "Bundle name").required()
    private val version by option("--version", help = "Bundle version (semver)").required()
    private val author by option("--author")
    private val license by option("--license", help = "SPDX license expression")
    private val description by option("--description")
    private val homepage by option("--homepage")
    private val tags by option("--tag", help = "Tag (repeatable)").multiple()
    private val cover by option("--cover", help = "Cover image").path(mustExist = true)
    private val output by option("-o", "--output", help = "Output .soundpack.zip file or folder").path()
    private val processing by ProcessingOptions()

    override fun run() {
        val files = sortedMapOf<String, Content>()
        val roots = mutableListOf<String>()
        try {
            for (input in inputs) {
                val loaded = MediaTools { echo(it) }.process(PackSources.load(input), processing.options())
                if (loaded.manifestName != "pack.json") throw PackerException("$input is a bundle; bundles cannot contain bundles")
                val packId = (loaded.manifest["id"] as? JsonPrimitive)?.content ?: throw PackerException("$input has no id")
                val root = "packs/$packId"
                if (root in roots) throw PackerException("Pack $packId is listed twice")
                roots += root
                files.putAll(loaded.pack.prefixed(root).files)
            }
        } catch (e: PackerException) {
            echo("error: ${e.message}", err = true); throw ProgramResult(2)
        }
        val coverPath = cover?.let { c ->
            val ext = c.name.substringAfterLast('.', "").lowercase()
            if (FileFormat.of(c.name)?.kind != FileKind.IMAGE) {
                echo("error: --cover must be an image", err = true); throw ProgramResult(2)
            }
            "cover.$ext".also { files[it] = Content.OnDisk(c) }
        }
        val manifest = BundleManifest(
            formatVersion = Limits.FORMAT_VERSION, type = "bundle", id = id, name = bundleName, version = version,
            author = author, license = license, homepage = homepage, description = description, cover = coverPath,
            tags = tags, packs = roots,
        )
        files["bundle.json"] = Content.InMemory((SpecJson.encodeToString(BundleManifest.serializer(), manifest) + "\n").encodeToByteArray())
        val pack = AssembledPack(files)
        validateOrFail("bundle $id", pack)
        val file = outputFile(output, id, version)
        DeterministicZip.write(pack, file)
        reportWritten(file, pack)
    }
}

class Validate : CliktCommand(name = "validate") {
    override fun help(context: Context) =
        "Validates pack and bundle archives (any .zip), unpacked pack or bundle folders, pack.yaml sources, repository indexes and backups. " +
            "Prints stable error codes (spec/error-codes.json). Exit code 1 if anything is invalid."

    private val paths by argument(help = "Files or folders").path(mustExist = true).multiple(required = true)
    private val kind by option("--kind", help = "What the inputs are (default: detect)")
        .choice("auto", "package", "repository", "backup", "envelope").default("auto")
    private val json by option("--json", help = "Print a JSON report").flag()
    private val strict by option("--strict", help = "Treat warnings as errors").flag()
    private val deep by option("--deep", help = "Also fully decode every audio and image file with ffmpeg / djxl").flag()

    override fun run() {
        val results = paths.map { it to validate(it) }
        if (json) {
            echo(
                SpecJson.encodeToString(
                    JsonArray.serializer(),
                    buildJsonArray {
                        for ((p, r) in results) add(
                            buildJsonObject {
                                put("input", p.toString())
                                put("valid", r.isValid && !(strict && r.warnings.isNotEmpty()))
                                put("errors", issuesJson(r.errors))
                                put("warnings", issuesJson(r.warnings))
                            },
                        )
                    },
                ),
            )
        } else {
            for ((p, r) in results) printIssues(p.toString(), r)
        }
        if (results.any { (_, r) -> !r.isValid || (strict && r.warnings.isNotEmpty()) }) throw ProgramResult(1)
    }

    private fun issuesJson(issues: List<Issue>) = buildJsonArray {
        for (i in issues) add(
            buildJsonObject {
                put("code", i.code.name)
                put("message", i.message)
                i.file?.let { put("file", it) }
                i.pointer?.let { put("pointer", it) }
            },
        )
    }

    private fun documentKind(p: Path): DocumentKind? = when (kind) {
        "repository" -> DocumentKind.REPOSITORY
        "backup" -> DocumentKind.BACKUP
        "envelope" -> DocumentKind.BACKUP_ENVELOPE
        "package" -> null
        else -> when {
            p.isDirectory() -> null
            p.name == "repository.json" || p.name == "index.json" -> DocumentKind.REPOSITORY
            p.name == "backup.json" -> DocumentKind.BACKUP
            p.name == "envelope.json" -> DocumentKind.BACKUP_ENVELOPE
            p.name.endsWith(".json") -> null
            else -> null
        }
    }

    private fun validate(p: Path): ValidationReport {
        documentKind(p)?.let { return DocumentValidator.validate(it, p.readBytes(), p.name) }
        val target = if (!p.isDirectory() && (p.name == "pack.json" || p.name == "bundle.json")) p.toAbsolutePath().parent else p
        val validation = if (target.isDirectory() && (Files.exists(target.resolve("pack.yaml")) || Files.exists(target.resolve("pack.yml")))) {
            val loaded = try {
                PackSources.load(target)
            } catch (e: PackerException) {
                echo("error: ${e.message}", err = true); throw ProgramResult(2)
            }
            PackValidator().validate(loaded.pack)
        } else {
            app.mauri.cucuto.packspec.JvmPackIo.validatePath(target)
        }
        if (!deep || !validation.report.isValid) return validation.report
        return ValidationReport(validation.report.issues + DeepCheck.run(target))
    }
}

class Index : CliktCommand(name = "index") {
    override fun help(context: Context) =
        "Writes a pack repository folder: repository.json, previews/ (covers, sprites, sample clips) and packs/ " +
            "(unless --download-base-url points elsewhere), with sizes and SHA-256 hashes."

    private val inputs by argument(help = "Pack and bundle archives (.zip), or folders containing them").path(mustExist = true).multiple(required = true)
    private val output by option("-o", "--output", help = "Repository folder to write").path().required()
    private val repoName by option("--name", help = "Repository name").required()
    private val description by option("--description")
    private val homepage by option("--homepage", help = "https URL")
    private val downloadBaseUrl by option("--download-base-url", help = "Absolute URL where the .soundpack.zip files are hosted (for example a GitHub release)")
    private val generatedAt by option("--generated-at", help = "RFC 3339 UTC timestamp to record (default: SOURCE_DATE_EPOCH if set, else omitted)")

    override fun run() {
        val timestamp = generatedAt ?: System.getenv("SOURCE_DATE_EPOCH")?.toLongOrNull()?.let {
            DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(it))
        }
        try {
            val index = Indexer { echo(it) }.index(inputs, output, Indexer.Options(repoName, description, homepage, downloadBaseUrl, timestamp))
            echo("Wrote ${output.resolve("repository.json")} (${index.packs.size} entries)")
        } catch (e: PackerException) {
            echo("error: ${e.message}", err = true); throw ProgramResult(1)
        }
    }
}

/** Optional full decode with external tools (`validate --deep`). */
private object DeepCheck {
    fun run(target: Path): List<Issue> {
        MediaTools.requireTool("ffmpeg")
        val loaded = PackSources.load(target)
        val tmp = createTempDirectory("packer-deep-")
        val issues = mutableListOf<Issue>()
        try {
            for ((path, content) in loaded.pack.files) {
                val format = FileFormat.of(path) ?: continue
                if (format.kind != FileKind.AUDIO && !format.isBitmap) continue
                val file = tmp.resolve("f.${path.substringAfterLast('.')}")
                Files.write(file, content.bytes())
                val cmd = if (format == FileFormat.JXL) {
                    if (!MediaTools.hasTool("djxl")) continue
                    listOf("djxl", file.toString(), tmp.resolve("out.png").toString())
                } else {
                    listOf("ffmpeg", "-hide_banner", "-v", "error", "-xerror", "-i", file.toString(), "-f", "null", "-")
                }
                try {
                    MediaTools.run(cmd)
                } catch (e: PackerException) {
                    issues += Issue(app.mauri.cucuto.packspec.ErrorCode.MEDIA_UNDECODABLE, "Decoding failed: ${e.message?.lines()?.lastOrNull { it.isNotBlank() }}", file = path)
                }
            }
        } finally {
            tmp.toFile().deleteRecursively()
        }
        return issues
    }
}

/** True for the Ogg file names packer writes ring files and cut clips to. */
private fun isOggName(p: Path) = p.name.substringAfterLast('.', "").lowercase() in setOf("ogg", "opus", "oga")

class Ring : CliktCommand(name = "ring") {
    override fun help(context: Context) =
        "Renders ring files: the audio the app exports for Android's per-contact ringtone. Telecom loops the whole file, " +
            "so the intro plays once and the loop section repeats until the file lasts at least 90 s (docs/pack-format.md#ring-files). " +
            "Writes Ogg Opus. Needs ffmpeg."

    private val source by argument(help = "Pack or bundle: a folder with pack.yaml or pack.json, or a .zip").path(mustExist = true)
    private val output by option("-o", "--output", help = "Folder for <pack id>-<character id>-<n>.ogg files, or one .ogg file with --character and --variant").path().required()
    private val character by option("-c", "--character", help = "Only this character ID")
    private val variant by option("-v", "--variant", help = "Only this ringtone of the character, counting from 1").int()
    private val normalize by option("--normalize", help = "Normalize loudness (EBU R128, -16 LUFS)").flag()

    override fun run() {
        val loaded = try {
            PackSources.load(source)
        } catch (e: PackerException) {
            echo("error: ${e.message}", err = true); throw ProgramResult(2)
        }
        val content = validateOrFail(source.toString(), loaded.pack)
        val targets = content.packs.flatMap { pack ->
            pack.manifest.characters.filter { character == null || it.id == character }.flatMap { c ->
                c.ringtones.mapIndexed { i, r -> RingTarget(pack.root, pack.manifest.id, c.id, i + 1, r) }
                    .filter { variant == null || it.number == variant }
            }
        }
        if (targets.isEmpty()) {
            echo("error: no ringtone matches", err = true); throw ProgramResult(1)
        }
        val single = isOggName(output)
        if (single && targets.size > 1) {
            echo("error: ${targets.size} ringtones match; pick one with --character and --variant, or pass a folder", err = true)
            throw ProgramResult(2)
        }
        val tmp = createTempDirectory("packer-ring-src-")
        try {
            for (t in targets) {
                val path = PackPaths.join(t.root, t.ringtone.file)
                val src = when (val c = loaded.pack.files.getValue(path)) {
                    is Content.OnDisk -> c.path
                    is Content.InMemory -> tmp.resolve("${t.number}-${path.substringAfterLast('/')}").also { Files.write(it, c.bytes()) }
                }
                val out = if (single) output else output.resolve("${t.packId}-${t.characterId}-${t.number}.ogg")
                val result = try {
                    Ringtones.renderRingFile(src, t.ringtone.loopRange, t.ringtone.fadeInMs?.toLong() ?: 0L, out, normalize)
                } catch (e: PackerException) {
                    echo("error: ${e.message}", err = true); throw ProgramResult(2)
                }
                val p = result.plan
                val layout = when {
                    result.copied -> "the source, copied as is"
                    p.playOnce -> "plays once, then ${p.silenceMs} ms of silence"
                    else -> "intro ${p.loopStartMs} ms, loop ${p.loopStartMs}..${p.loopEndMs} ms x${p.loopCount}"
                }
                val fade = if (p.fadeInMs > 0 && !result.copied) ", fade-in ${p.fadeInMs} ms" else ""
                echo("Wrote $out (${p.totalMs} ms: $layout$fade; ${out.fileSize()} bytes)")
            }
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    private class RingTarget(val root: String, val packId: String, val characterId: String, val number: Int, val ringtone: Ringtone)
}

class Cut : CliktCommand(name = "cut") {
    override fun help(context: Context) =
        "Cuts a notification clip from a song or ringtone and writes it as Ogg Opus. Without --start-ms it finds the hook: " +
            "the loudest stretch, moved back to its beat (docs/pack-format.md#auto-cut-clips). Needs ffmpeg."

    private val source by argument(help = "Audio file").path(mustExist = true)
    private val output by option("-o", "--output", help = "Clip to write (.ogg)").path().required()
    private val startMs by option("--start-ms", help = "Start of the clip (default: the hook)").long()
    private val lengthMs by option("--length-ms", help = "Length of the clip").long().default(Limits.CUT_CLIP_DEFAULT_MS)
    private val fromMs by option("--from-ms", help = "Search for the hook from here").long().default(0)
    private val toMs by option("--to-ms", help = "Search for the hook up to here (default: the end)").long()

    override fun run() {
        if (!isOggName(output)) {
            echo("error: $output must end in .ogg", err = true); throw ProgramResult(2)
        }
        val cut = try {
            Ringtones.cutClip(source, output, startMs, lengthMs, fromMs, toMs)
        } catch (e: PackerException) {
            echo("error: ${e.message}", err = true); throw ProgramResult(2)
        }
        echo("Wrote $output (${cut.startMs}..${cut.endMs} ms of ${source.name}; ${output.fileSize()} bytes)")
    }
}
