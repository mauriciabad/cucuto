package app.mauri.cucuto.packer

import app.mauri.cucuto.packspec.FileFormat
import app.mauri.cucuto.packspec.FileKind
import app.mauri.cucuto.packspec.SpecJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/** Opt-in conversions through external tools. None of them is ever required to build a valid pack. */
data class ProcessOptions(
    val normalize: Boolean = false,
    val trimSilence: Boolean = false,
    val opus: Boolean = false,
    val jxl: Boolean = false,
) {
    val any: Boolean get() = normalize || trimSilence || opus || jxl
}

/**
 * Runs ffmpeg and cjxl over the media of an assembled pack and rewrites the manifest paths of converted files.
 * Loudness: EBU R128 two-pass loudnorm to -16 LUFS integrated, -1.5 dBTP. Silence trimming: leading and trailing
 * audio below -50 dBFS, clips only (ringtone loop points stay valid). Opus: Ogg Opus, 96 kb/s for clips and 128 kb/s
 * for ringtones, written as .ogg. JPEG XL: lossless for PNG and GIF, lossless JPEG transcoding for JPEG.
 */
class MediaTools(private val log: (String) -> Unit) {
    private val bitexact = listOf("-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact")

    fun process(input: AssembledInput, options: ProcessOptions): AssembledInput {
        if (!options.any) return input
        if (options.normalize || options.trimSilence || options.opus) requireTool("ffmpeg")
        if (options.jxl) requireTool("cjxl")
        val roles = roles(input.manifest)
        val work = Files.createTempDirectory("packer-media-")
        try {
            val files = input.pack.files.toMutableMap()
            val renames = mutableMapOf<String, String>()
            for ((path, role) in roles) {
                val content = files[path] ?: continue
                val format = FileFormat.of(path) ?: continue
                val result: Pair<String, ByteArray>? = when {
                    format.kind == FileKind.AUDIO && (options.normalize || options.opus || (options.trimSilence && role == Role.CLIP)) ->
                        audio(path, content, role, options, work)
                    format.isBitmap && options.jxl && format in setOf(FileFormat.PNG, FileFormat.GIF, FileFormat.JPEG) ->
                        jxl(path, content, format, work)
                    else -> null
                }
                if (result != null) {
                    files.remove(path)
                    files[result.first] = Content.InMemory(result.second)
                    if (result.first != path) renames[path] = result.first
                }
            }
            val manifest = rename(input.manifest, renames) as JsonObject
            files[input.manifestName] = Content.InMemory((SpecJson.encodeToString(JsonObject.serializer(), manifest) + "\n").encodeToByteArray())
            return AssembledInput(manifest, input.manifestName, AssembledPack(files))
        } finally {
            @OptIn(kotlin.io.path.ExperimentalPathApi::class)
            work.deleteRecursively()
        }
    }

    enum class Role { CLIP, RINGTONE, IMAGE }

    private fun roles(manifest: JsonObject): Map<String, Role> {
        val out = linkedMapOf<String, Role>()
        fun str(e: JsonElement?) = (e as? JsonPrimitive)?.contentOrNull
        str(manifest["cover"])?.let { out[it] = Role.IMAGE }
        for (c in manifest["characters"] as? JsonArray ?: JsonArray(emptyList())) {
            val o = c.jsonObject
            str(o["sprite"])?.let { out[it] = Role.IMAGE }
            str(o["spriteAnimated"])?.let { out[it] = Role.IMAGE }
            (o["clips"] as? JsonArray)?.forEach { clip -> str(clip.jsonObject["file"])?.let { out.putIfAbsent(it, Role.CLIP) } }
            (o["ringtones"] as? JsonArray)?.forEach { r -> str(r.jsonObject["file"])?.let { out[it] = Role.RINGTONE } }
        }
        return out
    }

    private fun audio(path: String, content: Content, role: Role, o: ProcessOptions, work: Path): Pair<String, ByteArray> {
        val ext = path.substringAfterLast('.')
        val src = work.resolve("in-${path.hashCode().toUInt()}.$ext").also { it.writeBytes(content.bytes()) }
        val filters = mutableListOf<String>()
        if (o.trimSilence && role == Role.CLIP) {
            val trim = "silenceremove=start_periods=1:start_threshold=-50dB:start_silence=0.02"
            filters += listOf(trim, "areverse", trim, "areverse")
        }
        if (o.normalize) filters += loudnorm(src, filters)
        val targetExt = if (o.opus) "ogg" else ext.lowercase()
        val codec = when {
            o.opus -> listOf("-c:a", "libopus", "-b:a", if (role == Role.RINGTONE) "128k" else "96k", "-vbr", "on")
            else -> when (FileFormat.of(path)) {
                FileFormat.OGG -> listOf("-c:a", "libopus", "-b:a", if (role == Role.RINGTONE) "128k" else "96k")
                FileFormat.MP3 -> listOf("-c:a", "libmp3lame", "-q:a", "2")
                FileFormat.M4A -> listOf("-c:a", "aac", "-b:a", "192k")
                FileFormat.AAC -> listOf("-c:a", "aac", "-b:a", "192k", "-f", "adts")
                FileFormat.FLAC -> listOf("-c:a", "flac")
                else -> listOf("-c:a", "pcm_s16le")
            }
        }
        val out = work.resolve("out-${path.hashCode().toUInt()}.$targetExt")
        val args = mutableListOf("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", src.toString(), "-vn")
        if (filters.isNotEmpty()) args += listOf("-af", filters.joinToString(","))
        if (o.normalize || o.opus) args += listOf("-ar", "48000")
        args += codec + bitexact + out.toString()
        run(args)
        val newPath = path.substringBeforeLast('.') + "." + targetExt
        log("  audio  $path -> $newPath")
        return newPath to out.readBytes()
    }

    /** First loudnorm pass: measure, then return the filter for a linear second pass. */
    private fun loudnorm(src: Path, before: List<String>): String {
        val target = "I=-16:TP=-1.5:LRA=11"
        val chain = (before + "loudnorm=$target:print_format=json").joinToString(",")
        val output = run(listOf("ffmpeg", "-hide_banner", "-nostats", "-i", src.toString(), "-af", chain, "-f", "null", "-"))
        val json = output.substring(output.lastIndexOf('{'), output.lastIndexOf('}') + 1)
        val m = SpecJson.parseToJsonElement(json).jsonObject
        fun v(key: String) = m[key]!!.jsonPrimitive.content
        val measuredI = v("input_i").toDoubleOrNull()
        if (measuredI == null || measuredI.isInfinite()) return "loudnorm=$target" // silence or too short to measure
        return "loudnorm=$target:measured_I=${v("input_i")}:measured_TP=${v("input_tp")}:measured_LRA=${v("input_lra")}:" +
            "measured_thresh=${v("input_thresh")}:offset=${v("target_offset")}:linear=true"
    }

    private fun jxl(path: String, content: Content, format: FileFormat, work: Path): Pair<String, ByteArray> {
        val ext = path.substringAfterLast('.')
        val src = work.resolve("img-${path.hashCode().toUInt()}.$ext").also { it.writeBytes(content.bytes()) }
        val out = work.resolve("img-${path.hashCode().toUInt()}.jxl")
        val args = mutableListOf("cjxl", "--quiet", src.toString(), out.toString())
        if (format != FileFormat.JPEG) args += listOf("-d", "0", "-e", "7")
        run(args)
        val newPath = path.substringBeforeLast('.') + ".jxl"
        log("  image  $path -> $newPath")
        return newPath to out.readBytes()
    }

    private fun rename(e: JsonElement, renames: Map<String, String>): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { rename(it.value, renames) })
        is JsonArray -> JsonArray(e.map { rename(it, renames) })
        is JsonPrimitive -> if (e.isString && e.content in renames) JsonPrimitive(renames.getValue(e.content)) else e
    }

    companion object {
        fun hasTool(name: String): Boolean = try {
            ProcessBuilder(name, if (name == "cjxl") "--version" else "-version").redirectErrorStream(true).start().let {
                it.inputStream.readAllBytes()
                it.waitFor(30, TimeUnit.SECONDS) && it.exitValue() == 0
            }
        } catch (e: Exception) {
            false
        }

        fun requireTool(name: String) {
            if (!hasTool(name)) throw PackerException("$name is not on PATH (macOS: brew install ${if (name == "cjxl") "jpeg-xl" else name})")
        }

        /** Runs a command and returns its combined output; throws on a non-zero exit. */
        fun run(args: List<String>): String {
            val p = ProcessBuilder(args).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(10, TimeUnit.MINUTES) || p.exitValue() != 0) {
                throw PackerException("${args.first()} failed (${args.joinToString(" ")}):\n$out")
            }
            return out
        }
    }
}
