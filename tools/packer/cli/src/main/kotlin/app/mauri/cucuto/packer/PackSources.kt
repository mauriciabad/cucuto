package app.mauri.cucuto.packer

import app.mauri.cucuto.packspec.FileFormat
import app.mauri.cucuto.packspec.FileKind
import app.mauri.cucuto.packspec.FileInput
import app.mauri.cucuto.packspec.JvmPackIo
import app.mauri.cucuto.packspec.Limits
import app.mauri.cucuto.packspec.PackPaths
import app.mauri.cucuto.packspec.zip.ZipArchive
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

class PackerException(message: String) : Exception(message)

/** A pack assembled in memory, with its manifest as JSON. */
class AssembledInput(val manifest: JsonObject, val manifestName: String, val pack: AssembledPack)

/** Turns pack sources (folders with pack.yaml or pack.json, or built archives) into [AssembledPack]s. */
object PackSources {
    /** Reads a source folder (pack.yaml or pack.json) or a pack or bundle archive (any .zip, conventionally <name>.soundpack.zip). */
    fun load(path: Path): AssembledInput = when {
        path.isDirectory() && path.resolve("pack.yaml").exists() -> fromYaml(path.resolve("pack.yaml"))
        path.isDirectory() && path.resolve("pack.yml").exists() -> fromYaml(path.resolve("pack.yml"))
        path.isDirectory() -> fromFolder(path)
        path.isRegularFile() && (path.name == "pack.yaml" || path.name == "pack.yml") -> fromYaml(path)
        path.isRegularFile() -> fromArchive(path)
        else -> throw PackerException("$path does not exist")
    }

    /** An unpacked pack or bundle folder, taken as is (operating system artifacts are skipped). */
    fun fromFolder(dir: Path): AssembledInput {
        val files = Files.walk(dir).use { s ->
            s.filter { Files.isRegularFile(it) && !Files.isSymbolicLink(it) }.toList()
        }.associate { dir.relativize(it).joinToString("/") to Content.OnDisk(it) as Content }
            .filterKeys { !PackPaths.isIgnored(it) && it != "pack.yaml" && it != "pack.yml" }
        return withManifest(AssembledPack(files))
    }

    fun fromArchive(file: Path): AssembledInput {
        val opened = FileInput(file).use { input ->
            val zip = ZipArchive.open(input, JvmPackIo.inflater)
            val archive = zip.source ?: throw PackerException("$file: ${zip.issues.joinToString("; ")}")
            archive.records.filter { it.kind == app.mauri.cucuto.packspec.EntryKind.FILE && !PackPaths.isIgnored(it.name) }
                .associate { it.name to Content.InMemory(archive.read(it.name)) as Content }
        }
        return withManifest(AssembledPack(opened))
    }

    private fun withManifest(pack: AssembledPack): AssembledInput {
        val name = listOf("pack.json", "bundle.json").firstOrNull { it in pack.files }
            ?: throw PackerException("No pack.json or bundle.json at the root")
        val json = try {
            app.mauri.cucuto.packspec.SpecJson.parseToJsonElement(pack.files.getValue(name).bytes().decodeToString()) as JsonObject
        } catch (e: Exception) {
            throw PackerException("$name is not a JSON object: ${e.message}")
        }
        return AssembledInput(json, name, pack)
    }

    // ------------------------------------------------------------------ pack.yaml

    /** Keys whose YAML scalars become JSON numbers or booleans; every other scalar stays a string. */
    private val TYPED_KEYS = setOf(
        "formatVersion", "weight", "fadeInMs", "startMs", "endMs", "at", "scale", "intensity", "sharpness", "ms",
        "initialSharpness", "loop", "lengthMs", "fromMs", "toMs",
    )

    fun fromYaml(yamlFile: Path): AssembledInput {
        val dir = yamlFile.toAbsolutePath().parent
        val node = try {
            Yaml.default.parseToYamlNode(yamlFile.readText())
        } catch (e: Exception) {
            throw PackerException("$yamlFile: ${e.message}")
        }
        val yaml = toJson(node, null) as? JsonObject ?: throw PackerException("$yamlFile: expected a mapping at the top level")
        return PackYamlMapper(dir, yamlFile.name).map(yaml)
    }

    private fun toJson(node: YamlNode, key: String?): JsonElement = when (node) {
        is YamlNull -> JsonNull
        is YamlTaggedNode -> toJson(node.innerNode, key)
        is YamlList -> JsonArray(node.items.map { toJson(it, key) })
        is YamlMap -> JsonObject(node.entries.entries.associate { (k, v) -> k.content to toJson(v, k.content) })
        is YamlScalar -> if (key in TYPED_KEYS) typedScalar(node.content) else JsonPrimitive(node.content)
    }

    private fun typedScalar(s: String): JsonPrimitive = when {
        s == "true" -> JsonPrimitive(true)
        s == "false" -> JsonPrimitive(false)
        s.toLongOrNull() != null -> JsonPrimitive(s.toLong())
        s.toDoubleOrNull() != null -> JsonPrimitive(s.toDouble())
        else -> JsonPrimitive(s)
    }

    /** Maps the friendly pack.yaml format onto pack.json and the canonical archive layout. */
    private class PackYamlMapper(private val dir: Path, private val yamlName: String) {
        private val files = sortedMapOf<String, Content>()

        fun map(yaml: JsonObject): AssembledInput {
            val manifest = buildJsonObject {
                put("formatVersion", yaml["formatVersion"] ?: JsonPrimitive(1))
                for ((k, v) in yaml) {
                    when (k) {
                        "formatVersion" -> {}
                        "cover" -> put("cover", add(source(v, "cover"), "cover"))
                        "characters" -> put("characters", JsonArray((v as? JsonArray ?: fail("characters must be a list")).mapIndexed { i, c -> character(c, i) }))
                        else -> put(k, v)
                    }
                }
            }
            // Text files such as LICENSE or README.md next to pack.yaml travel with the pack.
            for (p in dir.listDirectoryEntries().sortedBy { it.name }) {
                val format = FileFormat.of(p.name)
                if (p.isRegularFile() && format?.kind == FileKind.TEXT && p.name.substringBefore('.').lowercase() in FileFormat.TEXT_FILE_NAMES) {
                    files[p.name] = Content.OnDisk(p)
                }
            }
            files["pack.json"] = Content.InMemory(app.mauri.cucuto.packspec.SpecJson.encodeToString(JsonObject.serializer(), manifest).plus("\n").encodeToByteArray())
            return AssembledInput(manifest, "pack.json", AssembledPack(files))
        }

        private fun fail(message: String): Nothing = throw PackerException("$yamlName: $message")

        private fun source(v: JsonElement, what: String): Path {
            val rel = (v as? JsonPrimitive)?.contentOrNull ?: fail("$what must be a file path")
            val p = dir.resolve(rel).normalize()
            if (!p.isRegularFile()) fail("$what: $rel is not a file")
            return p
        }

        /** Adds [file] at [base] + its extension, avoiding collisions, and returns the archive path. */
        private fun add(file: Path, base: String): JsonPrimitive {
            val ext = file.extension.lowercase()
            var candidate = if (ext.isEmpty()) base else "$base.$ext"
            var n = 2
            while (candidate in files && (files[candidate] as? Content.OnDisk)?.path != file) {
                candidate = if (ext.isEmpty()) "$base-$n" else "$base-$n.$ext"
                n++
            }
            files[candidate] = Content.OnDisk(file)
            return JsonPrimitive(candidate)
        }

        private fun character(c: JsonElement, index: Int): JsonObject {
            val obj = c as? JsonObject ?: fail("characters[$index] must be a mapping")
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: fail("characters[$index].name is required")
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: slug(name)
            val base = "characters/$id"
            return buildJsonObject {
                put("id", id)
                for ((k, v) in obj) {
                    when (k) {
                        "id" -> {}
                        "sprite" -> put("sprite", add(source(v, "characters[$index].sprite"), "$base/sprite"))
                        "spriteAnimated" -> put("spriteAnimated", add(source(v, "characters[$index].spriteAnimated"), "$base/sprite-anim"))
                        "clips" -> put("clips", audioList(v, "$base/clips", "characters[$index].clips"))
                        "ringtones" -> put("ringtones", audioList(v, "$base/ringtones", "characters[$index].ringtones"))
                        "haptic" -> put("haptic", haptic(v))
                        else -> put(k, v)
                    }
                }
            }
        }

        private fun audioList(v: JsonElement, folder: String, what: String): JsonArray {
            val items = if (v is JsonArray) v.toList() else listOf(v)
            val out = mutableListOf<JsonElement>()
            for (item in items) {
                when (item) {
                    is JsonPrimitive -> for (f in expand(item.contentOrNull ?: fail("$what: bad entry"), what)) {
                        out += buildJsonObject {
                            put("file", add(f, "$folder/${slug(f.nameWithoutExtension)}"))
                            put("label", label(f.nameWithoutExtension))
                        }
                    }
                    is JsonObject -> if ("cut" in item) {
                        out += cut(item, folder, what)
                    } else {
                        val file = source(item["file"] ?: fail("$what: every entry needs a file"), what)
                        out += buildJsonObject {
                            put("file", add(file, "$folder/${slug(file.nameWithoutExtension)}"))
                            if ("label" !in item) put("label", label(file.nameWithoutExtension))
                            for ((k, value) in item) {
                                when (k) {
                                    "file" -> {}
                                    "when" -> put("when", whenShorthand(value))
                                    else -> put(k, value)
                                }
                            }
                        }
                    }
                    else -> fail("$what: bad entry")
                }
            }
            return JsonArray(out)
        }

        /**
         * A clip cut from a song at build time (`- cut: song.flac`, optional `startMs`, `lengthMs`, `fromMs`, `toMs`):
         * the hook by default (docs/pack-format.md#auto-cut-clips). Needs ffmpeg; the result is an ordinary clip file.
         */
        private fun cut(item: JsonObject, folder: String, what: String): JsonObject {
            if (!folder.endsWith("/clips")) fail("$what: cut is only allowed in clips")
            val song = source(item.getValue("cut"), what)
            fun ms(key: String): Long? = item[key]?.let { v -> (v as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: fail("$what: $key must be a number of milliseconds") }
            val clip = Files.createTempFile("packer-cut-", ".ogg").also { it.toFile().deleteOnExit() }
            val range = try {
                Ringtones.cutClip(song, clip, ms("startMs"), ms("lengthMs") ?: Limits.CUT_CLIP_DEFAULT_MS, ms("fromMs") ?: 0, ms("toMs"))
            } catch (e: PackerException) {
                fail("$what: ${e.message}")
            }
            return buildJsonObject {
                put("file", add(clip, "$folder/${slug(song.nameWithoutExtension)}-hook"))
                put("label", (item["label"] as? JsonPrimitive)?.contentOrNull ?: "${label(song.nameWithoutExtension)} (hook)")
                for ((k, value) in item) {
                    if (k !in setOf("cut", "label", "startMs", "lengthMs", "fromMs", "toMs")) put(k, value)
                }
                // Where the clip came from, for people reading the manifest; readers ignore x- keys.
                put("x-cut", buildJsonObject { put("from", song.name); put("startMs", range.startMs); put("endMs", range.endMs) })
            }
        }

        /** A file, a folder (all audio files in it) or a glob, relative to pack.yaml; sorted by name. */
        private fun expand(pattern: String, what: String): List<Path> {
            val p = dir.resolve(pattern).normalize()
            val result = when {
                p.isDirectory() -> p.listDirectoryEntries().filter { it.isRegularFile() && FileFormat.of(it.name)?.kind == FileKind.AUDIO }
                pattern.any { it == '*' || it == '?' || it == '[' || it == '{' } -> {
                    val matcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
                    Files.walk(dir).use { s -> s.filter { it.isRegularFile() && matcher.matches(dir.relativize(it)) }.toList() }
                }
                p.isRegularFile() -> listOf(p)
                else -> fail("$what: $pattern matches no file")
            }
            if (result.isEmpty()) fail("$what: $pattern matches no audio file")
            return result.sortedBy { it.toString() }
        }

        private fun whenShorthand(v: JsonElement): JsonElement {
            val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return v
            return when (s) {
                "day", "night" -> buildJsonObject { put("time", s) }
                "spring", "summer", "autumn", "winter" -> buildJsonObject { put("season", s) }
                "mon", "tue", "wed", "thu", "fri", "sat", "sun" -> buildJsonObject { put("weekday", s) }
                else -> fail("when: '$s' is not day, night, a season or a weekday")
            }
        }

        private fun haptic(v: JsonElement): JsonElement = when {
            v is JsonPrimitive && v.isString -> buildJsonObject { put("mode", v.content) }
            v is JsonArray -> buildJsonObject { put("mode", "custom"); put("events", v) }
            v is JsonObject && "mode" !in v && "events" in v -> JsonObject(mapOf("mode" to JsonPrimitive("custom")) + v)
            else -> v
        }
    }

    /** Lowercase [a-z0-9] words joined by single hyphens. */
    fun slug(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "x" }

    /** "pika-pika_2" becomes "Pika pika 2". */
    fun label(fileStem: String): String =
        fileStem.replace(Regex("[-_]+"), " ").trim().replaceFirstChar { it.uppercase() }.ifEmpty { fileStem }
}
