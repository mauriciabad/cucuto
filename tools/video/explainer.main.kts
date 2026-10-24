#!/usr/bin/env kotlin

// Mixes and encodes the explainer video (issue #271, ADR 0070, docs/explainer-video.md).
//
// Input: the frames that `./gradlew :video:renderExplainerFrames` draws from the timeline in android/video
// (android/video/build/explainer/frames/: png/, frames.txt, cues.tsv, video.properties) and the voice-over that
// tools/video/voice.main.kts speaks (voice/voice-over.wav). Output in --out, with the brief's encoding settings:
//   explainer.webm, explainer.mp4    the English cut (AV1 + Opus, H.264 + AAC, 1920 × 1080, 60 fps)
//   explainer-poster.png             the poster frame (00:32.8)
//   explainer-review.mp4             the H.264 cut with the English and Spanish captions as tracks, for review
//   work/master.mkv                  the lossless master (FFV1 + FLAC)
//
// The web files carry no caption track: the site serves web/static/video/explainer.{en,es}.vtt beside them, and the
// brief never burns captions into the web cuts. Sound: the cues' CC0 demo-pack clips and brand sounds, and the
// voice-over on top; no music, never WhatsApp's tone. `--voice-over none` leaves the voice out (a silent placeholder).
//
// Usage (from the repository root; `./gradlew :video:explainerVideo` runs every step):
//   kotlin tools/video/explainer.main.kts [--frames DIR] [--voice-over FILE|none] [--out DIR] [--av1-preset 4]
// Needs ffmpeg with libsvtav1, libx264 and libopus.

import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

fun fail(message: String): Nothing {
    System.err.println("explainer: $message")
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
val out = File(options["out"] ?: "$root/android/video/build/explainer").apply { mkdirs() }
val framesDir = File(options["frames"] ?: "${out.path}/frames")
val workDir = File(out, "work").apply { mkdirs() }
val av1Preset = options["av1-preset"] ?: "4"
val voiceOver =
    when (val value = options["voice-over"] ?: "${out.path}/voice/voice-over.wav") {
        "none" -> null
        else -> File(value).takeIf { it.isFile } ?: fail("missing voice-over $value (run tools/video/voice.main.kts, or pass --voice-over none)")
    }

fun run(vararg command: String) {
    println("$ ${command.joinToString(" ") { if (it.contains(' ') || it.contains(';')) "'$it'" else it }.take(400)}")
    val process = ProcessBuilder(*command).inheritIO().start()
    if (!process.waitFor(2, TimeUnit.HOURS) || process.exitValue() != 0) fail("${command.first()} failed")
}

val video =
    Properties().apply {
        val file = File(framesDir, "video.properties")
        if (!file.isFile) fail("missing $file (run ./gradlew :video:renderExplainerFrames first)")
        file.inputStream().use { load(it) }
    }
val fps = video.getProperty("fps").toInt()
val duration = video.getProperty("duration").toDouble()
val posterAt = video.getProperty("poster")
val frames = File(framesDir, "frames.txt").readLines().filter { it.isNotBlank() }
if (frames.size != Math.round(duration * fps).toInt()) fail("frames.txt has ${frames.size} frames, expected ${duration * fps}")

fun seconds(value: Double) = "%.3f".format(Locale.ROOT, value)

// A numbered sequence for ffmpeg: hard links to the distinct frames, so holds cost no space.
val sequence =
    File(workDir, "seq").apply {
        deleteRecursively()
        mkdirs()
    }
frames.forEachIndexed { index, png ->
    val link = File(sequence, "%05d.png".format(index)).toPath()
    val target = File(framesDir, "png/$png").toPath()
    runCatching { Files.createLink(link, target) }.getOrElse { Files.copy(target, link) }
}

val inputs = mutableListOf<String>()
val graph = mutableListOf<String>()
var count = 0

// Sound: the cues at their times. The plain ping is tap.ogg an octave down (docs/explainer-video.md#sound-design).
val cues = File(framesDir, "cues.tsv").readLines().filter { it.isNotBlank() }.map { it.split('\t') }
val mixed = mutableListOf<String>()
cues.forEachIndexed { i, cue ->
    val (at, path, gain) = cue
    val effect = cue.getOrElse(3) { "" }
    val file = File(root, path).takeIf { it.isFile } ?: fail("missing sound $path")
    inputs += listOf("-i", file.path)
    val pitch = if (effect == "octave-down") "asetrate=24000,aresample=48000," else ""
    graph += "[$count:a]aresample=48000,aformat=channel_layouts=mono,${pitch}volume=${gain}dB,adelay=$at:all=1[c$i]"
    mixed += "[c$i]"
    count++
}
graph += mixed.joinToString("") + "amix=inputs=${mixed.size}:normalize=0:duration=longest," +
    "apad=whole_dur=${seconds(duration)},atrim=duration=${seconds(duration)}[fx]"
if (voiceOver != null) {
    // The voice on top (voice.main.kts levels it at about -18 LUFS; the clips peak a little under it).
    inputs += listOf("-i", voiceOver.path)
    graph += "[$count:a]aresample=48000,aformat=channel_layouts=mono,apad=whole_dur=${seconds(duration)}[vo]"
    graph += "[fx][vo]amix=inputs=2:normalize=0:duration=first,pan=stereo|c0=c0|c1=c0[a]"
} else {
    graph += "[fx]pan=stereo|c0=c0|c1=c0[a]"
}
val mix = File(workDir, "mix.wav")
File(workDir, "graph.txt").writeText(graph.joinToString(";\n"))
run(
    "ffmpeg", "-v", "warning", "-y", *inputs.toTypedArray(), "-filter_complex", graph.joinToString(";"),
    // Stereo with everything centered, before measuring: loudness counts both channels.
    "-map", "[a]", "-c:a", "pcm_s24le", "-ar", "48000", mix.path,
)

/** Measures [file] with ffmpeg's loudnorm and returns its first-pass report. */
fun measureLoudness(file: File): Map<String, String> {
    val process =
        ProcessBuilder("ffmpeg", "-hide_banner", "-i", file.path, "-af", "loudnorm=I=-16:TP=-1:LRA=11:print_format=json", "-f", "null", "-")
            .redirectErrorStream(true)
            .start()
    val report = process.inputStream.bufferedReader().readText()
    process.waitFor()
    return Regex("\"(\\w+)\"\\s*:\\s*\"([^\"]*)\"").findAll(report).associate { it.groupValues[1] to it.groupValues[2] }
}

// The brief's loudness, in two passes so it lands exactly: -16 LUFS integrated, -1 dBTP. Without a voice,
// normalizing the sparse clips to -16 LUFS would make them shout, so they keep their levels under a limiter.
val loudness =
    if (voiceOver != null) {
        val m = measureLoudness(mix)
        "loudnorm=I=-16:TP=-1:LRA=11:linear=true:measured_I=${m["input_i"]}:measured_TP=${m["input_tp"]}:" +
            "measured_LRA=${m["input_lra"]}:measured_thresh=${m["input_thresh"]}:offset=${m["target_offset"]},aresample=48000"
    } else {
        "alimiter=limit=0.89:level=disabled"
    }

// The lossless master: the frames and the normalized mix.
val master = File(workDir, "master.mkv")
run(
    "ffmpeg", "-v", "warning", "-stats", "-y", "-framerate", "$fps", "-i", "${sequence.path}/%05d.png", "-i", mix.path,
    "-filter_complex", "[0:v]format=yuv444p[v];[1:a]$loudness[a]", "-map", "[v]", "-map", "[a]",
    "-r", "$fps", "-c:v", "ffv1", "-level", "3", "-c:a", "flac", "-t", seconds(duration), master.path,
)

// The brief's deliverable settings (docs/explainer-video.md, Deliverables).
val webm = File(out, "explainer.webm")
val mp4 = File(out, "explainer.mp4")
run(
    "ffmpeg", "-v", "warning", "-stats", "-y", "-i", master.path,
    "-c:v", "libsvtav1", "-crf", "34", "-preset", av1Preset, "-pix_fmt", "yuv420p10le", "-g", "240",
    "-c:a", "libopus", "-b:a", "96k", webm.path,
)
run(
    "ffmpeg", "-v", "warning", "-stats", "-y", "-i", master.path,
    "-c:v", "libx264", "-profile:v", "high", "-level", "4.2", "-crf", "22", "-preset", "slow", "-pix_fmt", "yuv420p",
    "-g", "120", "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart", mp4.path,
)
run("ffmpeg", "-v", "error", "-y", "-ss", posterAt, "-i", master.path, "-frames:v", "1", File(out, "explainer-poster.png").path)

// For review: the H.264 cut with the English and Spanish captions as selectable tracks (no re-encode).
val captions = listOf("en" to "eng", "es" to "spa").map { (code, iso) -> iso to File(root, "web/static/video/explainer.$code.vtt") }
val review = mutableListOf("ffmpeg", "-v", "warning", "-y", "-i", mp4.path)
captions.forEach { review += listOf("-i", it.second.path) }
review += listOf("-map", "0:v", "-map", "0:a")
captions.indices.forEach { review += listOf("-map", "${it + 1}:s") }
review += listOf("-c:v", "copy", "-c:a", "copy", "-c:s", "mov_text")
captions.forEachIndexed { i, (iso, _) -> review += listOf("-metadata:s:s:$i", "language=$iso") }
review += listOf("-movflags", "+faststart", File(out, "explainer-review.mp4").path)
run(*review.toTypedArray())

println("explainer: done, see $out")
out.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.forEach { println("  ${it.name}  ${it.length() / 1024} kB") }
