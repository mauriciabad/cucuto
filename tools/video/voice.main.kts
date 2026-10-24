#!/usr/bin/env kotlin

// Speaks the explainer video's voice-over with a local, open-source text-to-speech model (issue #271, ADR 0070,
// docs/explainer-video.md#voice-over-script). No cloud service, no account, no human voice.
//
// Engine: sherpa-onnx (Apache-2.0), the prebuilt command-line tools of a pinned release.
// Model:  Kokoro-82M v1.0 (Apache-2.0, by hexgrad), as packaged by sherpa-onnx; voice "af_heart" (speaker 3),
//         American English. Apache-2.0 puts no limits on the audio it makes, so the voice can be published and
//         bundled with the video. Pronunciations Kokoro gets wrong are in voice-lexicon.txt (Cucuto is koo-KOO-toe).
//
// Input: the voice script that `./gradlew :video:renderExplainerFrames` writes from the timeline (voice.tsv: id,
// start s, end s, text). Each line must fit its slot: if it is too long at the normal speed, the line is spoken a
// little faster (at most 1.15×) and the script fails if it still doesn't fit. Output in --out:
//   lines/<id>.wav     each line as spoken
//   voice-over.wav     every line at its time, 48 kHz mono, loudness-normalized for the mix
//   voice.tsv          what was spoken: id, start, end, slot length, spoken length, speed
//
// The engine and the model (about 370 MB) are downloaded once into --cache (default ~/.cache/cucuto/tts), checked
// against the SHA-256 digests below.
//
// Usage (from the repository root; `./gradlew :video:explainerVoice` renders the frames first):
//   kotlin tools/video/voice.main.kts --script android/video/build/explainer/frames/voice.tsv \
//       --out android/video/build/explainer/voice [--cache DIR] [--threads 4]
// Needs ffmpeg, tar and bzip2; runs on Linux x86-64 and macOS.

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

val sherpaVersion = "1.13.8"
val modelName = "kokoro-multi-lang-v1_0"
val modelUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$modelName.tar.bz2"
val modelSha256 = "c5f7e2d2caf082bc1d20fb70334a61d99d20b484500aad32e7cf84c128ea3298"

/** The voice: af_heart, speaker 3 of Kokoro v1.0 (a calm, warm American English narrator). */
val voiceName = "af_heart"
val speakerId = 3
/** The fastest a line may be spoken to fit its slot. */
val speedMax = 1.15

/** The sherpa-onnx build for this machine, and its SHA-256. */
data class Engine(
    val asset: String,
    val sha256: String,
)

val engines =
    mapOf(
        "linux-amd64" to Engine("sherpa-onnx-v$sherpaVersion-linux-x64-shared", "c0bdb7907d3a74bba1d55d22bf4d9fa75586cf1530614ebe88a27b9118e015c4"),
        "linux-x86_64" to Engine("sherpa-onnx-v$sherpaVersion-linux-x64-shared", "c0bdb7907d3a74bba1d55d22bf4d9fa75586cf1530614ebe88a27b9118e015c4"),
        "mac-aarch64" to Engine("sherpa-onnx-v$sherpaVersion-osx-arm64-shared", "b10e5c7e2c30ea03de9c442655d14860d9edc475c6251d58a8f5f06e913a1d56"),
        "mac-x86_64" to Engine("sherpa-onnx-v$sherpaVersion-osx-universal2-shared", "2249f97f10df7d828af1b0d10e3d1eeaa2198b60b9a999a7ed853c150181ccfd"),
    )

fun fail(message: String): Nothing {
    System.err.println("voice: $message")
    exitProcess(1)
}

val options =
    args.toList().chunked(2).associate { pair ->
        val key = pair[0].removePrefix("--")
        key to (pair.getOrNull(1) ?: fail("missing value for --$key"))
    }

val root =
    generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, "docs/explainer-video.md").isFile }
        ?: fail("run from inside the cucuto repository")
val script = File(options["script"] ?: "$root/android/video/build/explainer/frames/voice.tsv")
val outDir = File(options["out"] ?: "$root/android/video/build/explainer/voice").apply { mkdirs() }
val cache = File(options["cache"] ?: "${System.getProperty("user.home")}/.cache/cucuto/tts").apply { mkdirs() }
val threads = options["threads"] ?: "4"
if (!script.isFile) fail("missing $script (run ./gradlew :video:renderExplainerFrames first)")

fun run(
    vararg command: String,
    quiet: Boolean = false,
) {
    if (!quiet) println("$ ${command.joinToString(" ").take(300)}")
    val process =
        ProcessBuilder(*command)
            .apply { if (quiet) redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD) else inheritIO() }
            .start()
    if (!process.waitFor(1, TimeUnit.HOURS) || process.exitValue() != 0) fail("${command.first()} failed")
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 20)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Downloads [url] once into the cache, checks its digest and unpacks it; returns the unpacked folder. */
fun fetch(
    url: String,
    sha256: String,
    folder: String,
): File {
    val target = File(cache, folder)
    if (File(target, ".ok").isFile) return target
    val archive = File(cache, url.substringAfterLast('/'))
    if (!archive.isFile || sha256(archive) != sha256) {
        println("voice: downloading $url")
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        val response = client.send(HttpRequest.newBuilder(URI(url)).build(), HttpResponse.BodyHandlers.ofFile(archive.toPath()))
        if (response.statusCode() != 200) fail("download failed: HTTP ${response.statusCode()} for $url")
    }
    val actual = sha256(archive)
    if (actual != sha256) fail("${archive.name}: SHA-256 is $actual, expected $sha256")
    target.deleteRecursively()
    run("tar", "xjf", archive.path, "-C", cache.path)
    if (!target.isDirectory) fail("${archive.name} did not unpack to $target")
    File(target, ".ok").writeText(sha256)
    archive.delete()
    return target
}

val os = System.getProperty("os.name").lowercase(Locale.ROOT).let { if (it.contains("mac")) "mac" else if (it.contains("linux")) "linux" else it }
val arch = System.getProperty("os.arch").lowercase(Locale.ROOT)
val engine = engines["$os-$arch"] ?: fail("no sherpa-onnx build pinned for $os-$arch")
val engineDir =
    fetch("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/${engine.asset}.tar.bz2", engine.sha256, engine.asset)
val modelDir = fetch(modelUrl, modelSha256, modelName)
val tts = File(engineDir, "bin/sherpa-onnx-offline-tts").path
val lexicon = File(root, "tools/video/voice-lexicon.txt")

/** A mono 16-bit PCM WAV file: sample rate and samples. */
class Wav(
    val rate: Int,
    val samples: ShortArray,
) {
    val seconds: Double get() = samples.size / rate.toDouble()

    fun write(file: File) {
        val data = samples.size * 2
        val buffer = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(data)
        samples.forEach { buffer.putShort(it) }
        file.writeBytes(buffer.array())
    }
}

/** Reads a mono 16-bit PCM WAV file. */
fun readWav(file: File): Wav {
    val bytes = file.readBytes()
    val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    if (header.getShort(22).toInt() != 1 || header.getShort(34).toInt() != 16) fail("${file.name}: not mono 16-bit PCM")
    val rate = header.getInt(24)
    var offset = 12
    while (offset + 8 <= bytes.size) {
        val id = String(bytes, offset, 4, Charsets.US_ASCII)
        val size = header.getInt(offset + 4)
        if (id == "data") return Wav(rate, ShortArray(size / 2) { header.getShort(offset + 8 + it * 2) })
        offset += 8 + size
    }
    fail("${file.name}: no data chunk")
}

/**
 * Cuts the silence the model leaves before and after a line (anything under -55 dBFS), keeping 60 ms before the
 * first word and 150 ms after the last (so a soft final "s" stays), so the slot holds speech, not padding.
 */
fun trim(file: File): Double {
    val wav = readWav(file)
    val threshold = (32768 * 0.00178).toInt()
    val first = wav.samples.indexOfFirst { kotlin.math.abs(it.toInt()) > threshold }.coerceAtLeast(0)
    val last = wav.samples.indexOfLast { kotlin.math.abs(it.toInt()) > threshold }.coerceAtLeast(first)
    val from = (first - wav.rate * 60 / 1000).coerceAtLeast(0)
    val until = (last + wav.rate * 150 / 1000).coerceAtMost(wav.samples.size)
    val trimmed = Wav(wav.rate, wav.samples.copyOfRange(from, until))
    trimmed.write(file)
    return trimmed.seconds
}

fun speak(
    text: String,
    file: File,
    speed: Double,
) {
    run(
        tts,
        "--kokoro-model=${File(modelDir, "model.onnx")}",
        "--kokoro-voices=${File(modelDir, "voices.bin")}",
        "--kokoro-tokens=${File(modelDir, "tokens.txt")}",
        "--kokoro-data-dir=${File(modelDir, "espeak-ng-data")}",
        "--kokoro-lexicon=$lexicon,${File(modelDir, "lexicon-us-en.txt")}",
        "--kokoro-lang=en",
        // Length scale: below 1 speaks faster.
        "--kokoro-length-scale=${"%.3f".format(Locale.ROOT, 1 / speed)}",
        "--sid=$speakerId",
        "--num-threads=$threads",
        "--output-filename=${file.path}",
        text,
        quiet = true,
    )
}

data class Line(
    val id: String,
    val start: Double,
    val end: Double,
    val text: String,
)

val lines =
    script.readLines().filter { it.isNotBlank() }.map {
        val (id, start, end, text) = it.split('\t')
        Line(id, start.toDouble(), end.toDouble(), text)
    }
if (lines.isEmpty()) fail("$script has no lines")

val linesDir = File(outDir, "lines").apply { mkdirs() }

val tooLong = mutableListOf<String>()
val report = StringBuilder("id\tstart\tend\tslot\tspoken\tspeed\n")
for (line in lines) {
    val slot = line.end - line.start
    val file = File(linesDir, "${line.id}.wav")
    // Faster only when needed, a step at a time.
    var speed = 1.0
    speak(line.text, file, speed)
    var length = trim(file)
    while (length > slot && speed < speedMax - 1e-9) {
        speed += 0.05
        speak(line.text, file, speed)
        length = trim(file)
    }
    val fits = length <= slot
    println(
        "voice: ${line.id}  %.2f s in a %.2f s slot at %.2f×%s  \"%s\"".format(Locale.ROOT, length, slot, speed, if (fits) "" else "  TOO LONG", line.text),
    )
    if (!fits) tooLong += line.id
    report.append(listOf(line.id, line.start, line.end, "%.3f".format(Locale.ROOT, slot), "%.3f".format(Locale.ROOT, length), "%.2f".format(Locale.ROOT, speed)).joinToString("\t")).append('\n')
}
File(outDir, "voice.tsv").writeText(report.toString())
if (tooLong.isNotEmpty()) {
    fail("${tooLong.joinToString()} don't fit their slots even at ${speedMax}×: shorten the lines or widen the slots in the timeline")
}

// Every line at its time on one track, then the brief's voice level (about -18 LUFS, docs/explainer-video.md).
val duration = lines.maxOf { it.end } + 1.0
val command = mutableListOf("ffmpeg", "-v", "error", "-y")
lines.forEach { command += listOf("-i", File(linesDir, "${it.id}.wav").path) }
val graph =
    lines.mapIndexed { i, line ->
        "[$i:a]aresample=48000,aformat=channel_layouts=mono,adelay=${Math.round(line.start * 1000)}:all=1[l$i]"
    } +
        (lines.indices.joinToString("") { "[l$it]" } + "amix=inputs=${lines.size}:normalize=0:duration=longest," +
            "apad=whole_dur=$duration,atrim=duration=$duration,loudnorm=I=-18:TP=-2:LRA=7,aresample=48000[v]")
command += listOf("-filter_complex", graph.joinToString(";"), "-map", "[v]", "-c:a", "pcm_s16le", "-ar", "48000", File(outDir, "voice-over.wav").path)
run(*command.toTypedArray())
println("voice: done, ${lines.size} lines in ${File(outDir, "voice-over.wav")} ($voiceName, Kokoro v1.0, sherpa-onnx $sherpaVersion)")
