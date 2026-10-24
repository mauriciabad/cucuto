#!/usr/bin/env kotlin

// Assembles the automated draft of the explainer video (issue #195, ADR 0060, docs/explainer-video.md).
//
// Input: the frames that `./gradlew :app:renderExplainerFrames` renders from the app's real screens
// (android/app/build/explainer/frames/: one folder per scene with frames.txt, scene.properties, cues.tsv and
// ripples.txt, plus the stage). Output, per cut, with the brief's encoding settings:
//   explainer.webm, explainer.mp4            English cut (AV1 + Opus, H.264 + AAC, 1920 × 1080, 60 fps)
//   explainer.es.webm, explainer.es.mp4      Spanish cut, when rendered
//   explainer-poster.png                     the frame at 00:32.8 of the English cut
//   explainer-review.<lang>.mp4              the H.264 cut with both caption tracks inside, for review
//   work/master-<lang>.mkv                   the lossless master (FFV1 + FLAC)
//
// The web files carry no caption track: the site serves web/static/video/explainer.{en,es}.vtt beside them, and
// the brief never burns captions into the web cuts. There is no voice-over yet (#194): pass --voice-over-en FILE
// (and --voice-over-es FILE), a recording on the brief's timeline, to mix it in and normalize to -16 LUFS.
//
// Usage (from the repository root; `./gradlew :app:explainerDraft` runs both steps):
//   kotlin tools/video/explainer.main.kts [--frames DIR] [--out DIR] [--languages en,es] [--av1-preset 4]
//       [--voice-over-en FILE] [--voice-over-es FILE]
// Needs ffmpeg with libsvtav1, libx264 and libopus.

import java.io.File
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

val durationMs = 58_000L
val fps = 60
val posterAt = "32.8"

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
val framesDir = File(options["frames"] ?: "$root/android/app/build/explainer/frames")
val outDir = File(options["out"] ?: "$root/android/app/build/explainer").apply { mkdirs() }
val workDir = File(outDir, "work").apply { mkdirs() }
val languages = (options["languages"] ?: "en").split(',').map { it.trim() }.filter { it.isNotEmpty() }
val av1Preset = options["av1-preset"] ?: "4"

fun run(vararg command: String) {
    println("$ ${command.joinToString(" ") { if (it.contains(' ') || it.contains(';')) "'$it'" else it }.take(400)}")
    val process = ProcessBuilder(*command).inheritIO().start()
    if (!process.waitFor(2, TimeUnit.HOURS) || process.exitValue() != 0) fail("${command.first()} failed")
}

fun properties(file: File) =
    Properties().apply {
        if (!file.isFile) fail("missing $file (run ./gradlew :app:renderExplainerFrames first)")
        file.inputStream().use { load(it) }
    }

class Scene(
    val dir: File,
) {
    private val props = properties(File(dir, "scene.properties"))
    val name: String = dir.name
    val kind: String = props.getProperty("kind")
    val start: Long = props.getProperty("start").toLong()
    val end: Long = props.getProperty("end").toLong()
    val frames: List<String> = File(dir, "frames.txt").readLines().filter { it.isNotBlank() }
    val cues: List<List<String>> = File(dir, "cues.tsv").readLines().filter { it.isNotBlank() }.map { it.split('\t') }
    val ripples: List<Long> = File(dir, "ripples.txt").readLines().filter { it.isNotBlank() }.map { it.toLong() }

    /** A numbered copy of the frames (hard links: holds cost no space) for ffmpeg's image sequence input. */
    fun sequence(): String {
        val seq = File(workDir, "seq/${dir.parentFile.name}-$name").apply {
            deleteRecursively()
            mkdirs()
        }
        frames.forEachIndexed { index, png ->
            val link = File(seq, "%05d.png".format(index)).toPath()
            val target = File(dir, "png/$png").toPath()
            runCatching { Files.createLink(link, target) }.getOrElse { Files.copy(target, link) }
        }
        return "${seq.path}/%05d.png"
    }
}

val stageDir = File(framesDir, "common/stage")
val layout = properties(File(stageDir, "layout.properties"))
val screenX = layout.getProperty("screenX").toInt()
val screenY = layout.getProperty("screenY").toInt()
val screenW = layout.getProperty("screenW").toInt()
val screenH = layout.getProperty("screenH").toInt()
val screenRadius = layout.getProperty("screenRadius").toInt()
val ripple = Scene(File(framesDir, "common/ripple"))

// The phone screen's rounded corners, as an alpha mask (antialiased by distance to the corner's arc).
val mask = File(workDir, "screen-mask.png")
run(
    "ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "color=black:s=${screenW}x$screenH:d=1", "-frames:v", "1",
    "-vf",
    "format=gray,geq=lum='255*clip($screenRadius+0.5-hypot(max($screenRadius-X,0)+max(X-(W-1-$screenRadius),0)," +
        "max($screenRadius-Y,0)+max(Y-(H-1-$screenRadius),0)),0,1)'",
    mask.path,
)

fun seconds(ms: Long) = "%.3f".format(java.util.Locale.ROOT, ms / 1000.0)

fun master(language: String): File {
    val scenes =
        (File(framesDir, "common").listFiles().orEmpty().toList() + File(framesDir, language).listFiles().orEmpty().toList())
            .filter { File(it, "scene.properties").isFile }
            .map { Scene(it) }
            .filter { it.kind == "phone" || it.kind == "full" }
            .sortedBy { it.start }
    // The scenes must tile the timeline: 0 to 58 s, no gap, no overlap.
    var cursor = 0L
    for (scene in scenes) {
        if (scene.start != cursor) fail("$language: ${scene.name} starts at ${scene.start} ms, expected $cursor ms")
        if (scene.frames.size.toLong() != (scene.end - scene.start) * fps / 1000) fail("${scene.name}: wrong frame count")
        cursor = scene.end
    }
    if (cursor != durationMs) fail("$language: the scenes end at $cursor ms, expected $durationMs ms")

    val inputs = mutableListOf<String>()
    val graph = mutableListOf<String>()
    var count = 0

    fun input(vararg args: String): Int {
        inputs += args
        return count++
    }
    val still = listOf("-loop", "1", "-framerate", "$fps", "-t", seconds(durationMs))
    val background = input(*still.toTypedArray(), "-i", File(stageDir, "background.png").path)
    val maskInput = input(*still.toTypedArray(), "-i", mask.path)
    var base = "b0"
    graph += "[$background:v]format=rgb24[$base]"

    fun over(
        layer: String,
        x: Int = 0,
        y: Int = 0,
    ) {
        val next = "b${graph.size}"
        graph += "[$base][$layer]overlay=x=$x:y=$y:eof_action=pass:format=rgb[$next]"
        base = next
    }

    // Vibration ripples: the stage with ripples, laid over the still stage at each ripple time.
    val ripples = scenes.flatMap { it.ripples }
    val rippleSequence = if (ripples.isEmpty()) "" else ripple.sequence()
    ripples.forEachIndexed { i, at ->
        val n = input("-framerate", "$fps", "-i", rippleSequence)
        graph += "[$n:v]setpts=PTS-STARTPTS+${seconds(at)}/TB,format=rgb24[r$i]"
        over("r$i")
    }
    val phones = scenes.filter { it.kind == "phone" }
    graph += "[$maskInput:v]format=gray,split=${phones.size}" + phones.indices.joinToString("") { "[m$it]" }
    phones.forEachIndexed { i, scene ->
        val n = input("-framerate", "$fps", "-i", scene.sequence())
        graph += "[$n:v]setpts=PTS-STARTPTS+${seconds(scene.start)}/TB,scale=$screenW:$screenH:flags=lanczos,format=rgba[p$i]"
        graph += "[m$i]setpts=PTS-STARTPTS[mm$i]"
        graph += "[p$i][mm$i]alphamerge[pm$i]"
        over("pm$i", screenX, screenY)
    }
    scenes.filter { it.kind == "full" }.forEachIndexed { i, scene ->
        val n = input("-framerate", "$fps", "-i", scene.sequence())
        graph += "[$n:v]setpts=PTS-STARTPTS+${seconds(scene.start)}/TB,format=rgb24[f$i]"
        over("f$i")
    }
    graph += "[$base]trim=duration=${seconds(durationMs)},setpts=PTS-STARTPTS,format=yuv444p[v]"

    // Sound: the scenes' cues (CC0 demo-pack clips and brand sounds) at their times; no music, no WhatsApp tone.
    val cues = scenes.flatMap { it.cues }
    val mixed = mutableListOf<String>()
    cues.forEachIndexed { i, cue ->
        val (at, path, gain) = cue
        val effect = cue.getOrElse(3) { "" }
        val file = File(root, path).takeIf { it.isFile } ?: fail("missing sound $path")
        val n = input("-i", file.path)
        // The plain ping of shot 1: tap.ogg an octave down (the brief's sound design).
        val pitch = if (effect == "octave-down") "asetrate=24000,aresample=48000," else ""
        graph += "[$n:a]aresample=48000,aformat=channel_layouts=mono,${pitch}volume=${gain}dB,adelay=$at:all=1[c$i]"
        mixed += "[c$i]"
    }
    graph += mixed.joinToString("") + "amix=inputs=${mixed.size}:normalize=0:duration=longest," +
        "apad=whole_dur=${seconds(durationMs)},atrim=duration=${seconds(durationMs)},pan=stereo|c0=c0|c1=c0[fx]"
    val voiceOver = options["voice-over-$language"]?.let(::File)
    if (voiceOver != null) {
        // The voice on top, then the brief's loudness: -16 LUFS integrated, -1 dBTP.
        val n = input("-i", voiceOver.path)
        graph += "[$n:a]aresample=48000,aformat=channel_layouts=stereo,apad=whole_dur=${seconds(durationMs)}[vo]"
        graph += "[fx][vo]amix=inputs=2:normalize=0:duration=first,loudnorm=I=-16:TP=-1:LRA=11,aresample=48000[a]"
    } else {
        // Voice-over placeholder: none yet (#194). Without a voice, normalizing the sparse clips to -16 LUFS would
        // make them shout, so they keep their levels (clips -3 dB, UI sounds 6 dB under) under a -1 dBFS limiter.
        graph += "[fx]alimiter=limit=0.89:level=disabled[a]"
    }

    val master = File(workDir, "master-$language.mkv")
    // Inline (ffmpeg 6 reads graph files with -filter_complex_script, ffmpeg 8 only with -/filter_complex); a copy
    // goes to work/ for debugging.
    val filter = graph.joinToString(";")
    File(workDir, "graph-$language.txt").writeText(graph.joinToString(";\n"))
    run(
        "ffmpeg", "-v", "warning", "-stats", "-y", *inputs.toTypedArray(),
        "-filter_complex", filter, "-map", "[v]", "-map", "[a]",
        "-r", "$fps", "-c:v", "ffv1", "-level", "3", "-c:a", "flac", "-t", seconds(durationMs), master.path,
    )
    return master
}

val captions = listOf("en" to "eng", "es" to "spa").map { (code, iso) -> Triple(code, iso, File(root, "web/static/video/explainer.$code.vtt")) }

for (language in languages) {
    val master = master(language)
    val suffix = if (language == "en") "" else ".$language"
    val webm = File(outDir, "explainer$suffix.webm")
    val mp4 = File(outDir, "explainer$suffix.mp4")
    // The brief's deliverable settings (docs/explainer-video.md, Deliverables).
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
    // For review: the same H.264 cut with the English and Spanish captions as selectable tracks (no re-encode).
    val review = mutableListOf("ffmpeg", "-v", "warning", "-y", "-i", mp4.path)
    captions.forEach { review += listOf("-i", it.third.path) }
    review += listOf("-map", "0:v", "-map", "0:a")
    captions.indices.forEach { review += listOf("-map", "${it + 1}:s") }
    review += listOf("-c:v", "copy", "-c:a", "copy", "-c:s", "mov_text")
    captions.forEachIndexed { i, (_, iso) -> review += listOf("-metadata:s:s:$i", "language=$iso") }
    review += listOf("-movflags", "+faststart", File(outDir, "explainer-review.$language.mp4").path)
    run(*review.toTypedArray())
    if (language == "en") {
        run(
            "ffmpeg", "-v", "error", "-y", "-ss", posterAt, "-i", master.path, "-frames:v", "1",
            File(outDir, "explainer-poster.png").path,
        )
    }
}

println("explainer: done, see $outDir")
outDir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.forEach { println("  ${it.name}  ${it.length() / 1024} kB") }
