#!/usr/bin/env kotlin
/*
 * Regenerates the golden fixtures in spec/fixtures/ (valid/, invalid/ and fixtures.json) and the golden ring file
 * plans (ring-files.json).
 *
 *   kotlin spec/fixtures/generate.main.kts        # from the repository root
 *
 * Needs ffmpeg (with libopus, libmp3lame, libsvtav1), cwebp and cjxl on PATH (Homebrew: ffmpeg, webp, jpeg-xl).
 * All media is synthesized here (sine tones, flat colors, hand-written SVG): no third-party content. Manifests are
 * written verbatim from this file, so the expected outcome of every fixture is defined next to its content.
 */

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.Deflater

val root = File("spec/fixtures")
require(File(root, "generate.main.kts").isFile) { "Run from the repository root" }
val work = File(System.getProperty("java.io.tmpdir"), "cucuto-fixture-media").apply { deleteRecursively(); mkdirs() }

// ------------------------------------------------------------------ process helpers

fun exec(vararg cmd: String) {
    val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    check(p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0) { "${cmd.joinToString(" ")} failed:\n$out" }
}

val bitexact = arrayOf("-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact", "-flags:v", "+bitexact")

fun ffmpeg(vararg args: String) = exec("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *args)

/** A sine tone with a short fade so clips do not click. */
fun tone(name: String, freq: Int, seconds: Double, vararg codec: String): File {
    val out = File(work, name)
    val fadeOut = (seconds - 0.05).coerceAtLeast(0.0)
    ffmpeg(
        "-f", "lavfi", "-i", "sine=frequency=$freq:duration=$seconds:sample_rate=48000",
        "-af", "afade=t=in:d=0.02,afade=t=out:st=$fadeOut:d=0.05",
        *codec, *bitexact, out.path,
    )
    return out
}

fun opus(name: String, freq: Int, seconds: Double) = tone(name, freq, seconds, "-ac", "1", "-c:a", "libopus", "-b:a", "8k")

fun color(name: String, hex: String, size: Int, vararg codec: String): File {
    val out = File(work, name)
    ffmpeg("-f", "lavfi", "-i", "color=c=0x$hex:s=${size}x$size", "-frames:v", "1", *codec, *bitexact, out.path)
    return out
}

// ------------------------------------------------------------------ media library

val clipOgg = opus("clip.ogg", 880, 0.5)
val clipShort = opus("clip-short.ogg", 880, 0.1)
val clipLong = opus("clip-long.ogg", 880, 31.0)
val clipLongest = opus("clip-longest.ogg", 880, 30.0)
val ringtoneOgg = run {
    val out = File(work, "ringtone.ogg")
    // Two alternating notes for 6 s.
    ffmpeg(
        "-f", "lavfi", "-i", "aevalsrc=0.4*sin(2*PI*if(lt(mod(t\\,1)\\,0.5)\\,523\\,659)*t):s=48000:d=6",
        "-ac", "1", "-c:a", "libopus", "-b:a", "8k", *bitexact, out.path,
    )
    out
}
val ringtoneShort = opus("ringtone-short.ogg", 523, 3.0)
val clipMp3 = tone("clip.mp3", 660, 0.6, "-ac", "1", "-ar", "22050", "-c:a", "libmp3lame", "-b:a", "32k", "-write_xing", "1")
val clipWav = tone("clip.wav", 440, 0.3, "-ac", "1", "-ar", "8000", "-c:a", "pcm_u8")
val clipFlac = tone("clip.flac", 520, 0.4, "-ac", "1", "-ar", "8000", "-c:a", "flac")
val clipM4a = tone("clip.m4a", 740, 0.5, "-ac", "1", "-ar", "22050", "-c:a", "aac", "-b:a", "24k")
val clipAac = tone("clip.aac", 760, 0.5, "-ac", "1", "-ar", "22050", "-c:a", "aac", "-b:a", "24k", "-f", "adts")
val clipVorbis = tone("clip-vorbis.ogg", 600, 0.5, "-ac", "2", "-ar", "22050", "-c:a", "vorbis", "-strict", "-2")

val spritePng = color("sprite.png", "4CAF50", 16)
val bigPng = color("big.png", "F44336", 600)
val spriteJpg = color("sprite.jpg", "FF9800", 16, "-q:v", "5")
val spriteAvif = color("sprite.avif", "2196F3", 64, "-c:v", "libsvtav1", "-pix_fmt", "yuv420p")
val animGif = File(work, "anim.gif").also {
    ffmpeg("-f", "lavfi", "-i", "color=c=0x9C27B0:s=16x16:r=2:d=1", "-vf", "drawbox=x='8*t':y=4:w=8:h=8:color=yellow:t=fill", *bitexact, it.path)
}
val spriteWebp = File(work, "sprite.webp").also { exec("cwebp", "-quiet", "-lossless", spritePng.path, "-o", it.path) }
val spriteJxl = File(work, "sprite.jxl").also { exec("cjxl", "--quiet", "-d", "0", spritePng.path, it.path) }

val svgSprite = """
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">
      <circle cx="32" cy="32" r="28" fill="#FFC107"/>
      <circle cx="23" cy="26" r="4" fill="#3E2723"/>
      <circle cx="41" cy="26" r="4" fill="#3E2723"/>
      <path d="M20 40 Q32 50 44 40" stroke="#3E2723" stroke-width="3" fill="none" stroke-linecap="round"/>
    </svg>
""".trimIndent() + "\n"

// ------------------------------------------------------------------ fixture DSL

data class Fixture(val path: String, val kind: String, val errors: List<String>, val warnings: List<String>, val description: String)

val fixtures = mutableListOf<Fixture>()

class Dir(val dir: File) {
    fun text(rel: String, content: String) = File(dir, rel).apply { parentFile.mkdirs() }.writeText(content)
    fun bytes(rel: String, content: ByteArray) = File(dir, rel).apply { parentFile.mkdirs() }.writeBytes(content)
    fun media(rel: String, source: File) = source.copyTo(File(dir, rel), overwrite = true)

    /** The standard one-character pack files: characters/beep/sprite.png and clips/beep.ogg. */
    fun beep() {
        media("characters/beep/sprite.png", spritePng)
        media("characters/beep/clips/beep.ogg", clipOgg)
    }
}

fun folder(path: String, description: String, errors: List<String> = emptyList(), warnings: List<String> = emptyList(), kind: String = "package", build: Dir.() -> Unit) {
    val dir = File(root, path).apply { deleteRecursively(); mkdirs() }
    Dir(dir).build()
    fixtures += Fixture(path, kind, errors, warnings, description)
}

fun document(path: String, kind: String, description: String, content: String, errors: List<String> = emptyList()) {
    File(root, path).apply { parentFile.mkdirs() }.writeText(content.trimIndent() + "\n")
    fixtures += Fixture(path, kind, errors, emptyList(), description)
}

/** A pack.json with one standard character. [character] and [extra] are spliced in as JSON members. */
fun pack(id: String, name: String = "Fixture", character: String = "", extra: String = "", characters: String? = null): String {
    val chars = characters ?: (
        "{\n" +
            "  \"id\": \"beep\",\n" +
            "  \"name\": \"Beep\",\n" +
            "  \"sprite\": \"characters/beep/sprite.png\",\n" +
            "  \"clips\": [{ \"file\": \"characters/beep/clips/beep.ogg\" }]" + character + "\n}"
        ).prependIndent("    ")
    return """
{
  "formatVersion": 1,
  "id": "app.mauri.fixtures.$id",
  "name": "$name",
  "version": "1.0.0",
  "author": "Cucuto fixtures",
  "license": "CC0-1.0",$extra
  "characters": [
$chars
  ]
}
""".trimStart()
}

// ------------------------------------------------------------------ deterministic zip writer

class ZipEntrySpec(
    val name: String,
    val data: ByteArray,
    val deflate: Boolean = false,
    val flags: Int = 0x0800,
    val method: Int? = null,
    val unixMode: Int = 0x81A4, // regular file, 0644
    val crcOverride: Long? = null,
)

fun zip(entries: List<ZipEntrySpec>): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val central = java.io.ByteArrayOutputStream()
    fun le(o: java.io.ByteArrayOutputStream, v: Long, n: Int) = repeat(n) { o.write(((v ushr (8 * it)) and 0xFF).toInt()) }
    val dosTime = 0L
    val dosDate = (0L shl 9) or (1L shl 5) or 1L // 1980-01-01
    for (e in entries) {
        val crc = e.crcOverride ?: CRC32().apply { update(e.data) }.value
        val body = if (e.deflate) {
            val d = Deflater(9, true)
            d.setInput(e.data); d.finish()
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
            d.end()
            out.toByteArray()
        } else e.data
        val method = e.method ?: if (e.deflate) 8 else 0
        val nameBytes = e.name.toByteArray()
        val offset = out.size().toLong()
        le(out, 0x04034b50, 4); le(out, 20, 2); le(out, e.flags.toLong(), 2); le(out, method.toLong(), 2)
        le(out, dosTime, 2); le(out, dosDate, 2); le(out, crc, 4); le(out, body.size.toLong(), 4); le(out, e.data.size.toLong(), 4)
        le(out, nameBytes.size.toLong(), 2); le(out, 0, 2); out.write(nameBytes); out.write(body)
        le(central, 0x02014b50, 4); le(central, (3L shl 8) or 20, 2); le(central, 20, 2); le(central, e.flags.toLong(), 2)
        le(central, method.toLong(), 2); le(central, dosTime, 2); le(central, dosDate, 2); le(central, crc, 4)
        le(central, body.size.toLong(), 4); le(central, e.data.size.toLong(), 4); le(central, nameBytes.size.toLong(), 2)
        le(central, 0, 2); le(central, 0, 2); le(central, 0, 2); le(central, 0, 2); le(central, e.unixMode.toLong() shl 16, 4)
        le(central, offset, 4); central.write(nameBytes)
    }
    val cdOffset = out.size().toLong()
    val cd = central.toByteArray()
    out.write(cd)
    le(out, 0x06054b50, 4); le(out, 0, 2); le(out, 0, 2); le(out, entries.size.toLong(), 2); le(out, entries.size.toLong(), 2)
    le(out, cd.size.toLong(), 4); le(out, cdOffset, 4); le(out, 0, 2)
    return out.toByteArray()
}

fun archive(path: String, description: String, errors: List<String> = emptyList(), warnings: List<String> = emptyList(), bytes: ByteArray) {
    File(root, path).apply { parentFile.mkdirs() }.writeBytes(bytes)
    fixtures += Fixture(path, "package", errors, warnings, description)
}

val minimalManifest = pack("minimal", "Minimal pack")

/** The files of the minimal pack as zip entries (manifest first, deflated like packer does). */
fun minimalEntries() = listOf(
    ZipEntrySpec("pack.json", minimalManifest.toByteArray(), deflate = true),
    ZipEntrySpec("characters/beep/clips/beep.ogg", clipOgg.readBytes()),
    ZipEntrySpec("characters/beep/sprite.png", spritePng.readBytes()),
)

File(root, "valid").deleteRecursively()
File(root, "invalid").deleteRecursively()

// ================================================================== valid

folder("valid/minimal-pack", "Smallest valid pack: one character, one PNG sprite, one Opus clip.") {
    text("pack.json", minimalManifest)
    beep()
}

folder("valid/longest-clip", "A 30 s clip, the longest a notification clip may be.") {
    text("pack.json", pack("longest"))
    media("characters/beep/sprite.png", spritePng)
    media("characters/beep/clips/beep.ogg", clipLongest)
}

folder("valid/full-pack", "Every pack.json feature: cover, text files, animated sprite, seed colors, tags, weights, custom haptics, ringtones with variants, when conditions on ringtones and clips, loop points and fade-in.") {
    text("pack.json", """
{
  "${'$'}schema": "https://cucuto.mauri.app/spec/v1/pack.schema.json",
  "formatVersion": 1,
  "type": "pack",
  "id": "app.mauri.fixtures.full",
  "name": "Full pack",
  "version": "2.1.0-beta.1",
  "author": "Cucuto fixtures",
  "license": "CC0-1.0 OR MIT",
  "homepage": "https://cucuto.mauri.app",
  "description": "Exercises every field of pack format v1.",
  "cover": "cover.svg",
  "tags": ["fixtures", "test-pack"],
  "x-generator": { "name": "generate.main.kts" },
  "characters": [
    {
      "id": "robin",
      "name": "Robin",
      "description": "A small bird with two calls.",
      "sprite": "characters/robin/sprite.svg",
      "spriteAnimated": "characters/robin/sprite-anim.gif",
      "seedColor": "#E65100",
      "tags": ["bird"],
      "clips": [
        { "file": "characters/robin/clips/chirp.ogg", "label": "Chirp", "weight": 2, "when": { "time": "day" } },
        { "file": "characters/robin/clips/tweet.mp3", "label": "Tweet", "weight": 0.5, "when": { "time": "night", "season": ["autumn", "winter"] } }
      ],
      "haptic": {
        "mode": "custom",
        "events": [
          { "at": 0, "preset": "CLICK", "scale": 0.8 },
          { "at": 120, "preset": "TICK", "scale": 0.5 },
          { "at": 220, "initialSharpness": 0.3, "envelope": [
            { "intensity": 1.0, "sharpness": 0.8, "ms": 120 },
            { "intensity": 0.0, "sharpness": 0.4, "ms": 180 }
          ] },
          { "at": 600, "preset": "LOW_TICK" }
        ]
      }
    },
    {
      "id": "song",
      "name": "Song",
      "sprite": "characters/song/album.jxl",
      "seedColor": "#1565C0",
      "clips": [{ "file": "characters/song/clips/hook.flac", "label": "Hook" }],
      "ringtones": [
        { "file": "characters/song/ringtones/day.ogg", "label": "Day", "tags": ["live"], "when": { "time": "day" }, "loop": { "startMs": 0, "endMs": 5000 }, "fadeInMs": 500 },
        { "file": "characters/song/ringtones/night.ogg", "label": "Night", "tags": ["radio"], "when": { "time": "night", "season": ["autumn", "winter"] }, "loop": false },
        { "file": "characters/song/ringtones/day.ogg", "label": "Weekend mornings", "when": { "weekday": ["sat", "sun"], "time": { "from": "09:00", "to": "12:30" } }, "loop": { "startMs": 1000 }, "weight": 3 },
        { "file": "characters/song/ringtones/night.ogg", "label": "Summer", "when": { "season": "summer" }, "loop": true }
      ],
      "haptic": { "mode": "none" }
    },
    {
      "id": "ringer",
      "name": "Ringer",
      "sprite": "characters/ringer/sprite.webp",
      "ringtones": [{ "file": "characters/ringer/ring.ogg" }],
      "haptic": { "mode": "auto" }
    }
  ]
}
""".trimStart())
    text("cover.svg", svgSprite)
    text("LICENSE", "CC0-1.0: generated test content, dedicated to the public domain.\n")
    text("README.md", "# Full pack\n\nGolden fixture for Cucuto pack format v1.\n")
    text("characters/robin/sprite.svg", svgSprite)
    media("characters/robin/sprite-anim.gif", animGif)
    media("characters/robin/clips/chirp.ogg", clipOgg)
    media("characters/robin/clips/tweet.mp3", clipMp3)
    media("characters/song/album.jxl", spriteJxl)
    media("characters/song/clips/hook.flac", clipFlac)
    media("characters/song/ringtones/day.ogg", ringtoneOgg)
    media("characters/song/ringtones/night.ogg", ringtoneOgg)
    media("characters/ringer/sprite.webp", spriteWebp)
    media("characters/ringer/ring.ogg", ringtoneOgg)
}

folder("valid/ringtone-song", "A song with only ringtones (no clips): an intro with a loop and an outro, an open loop ending 1 s before the end (the shortest allowed), and a play-once variant.") {
    text("pack.json", pack("ringtone-song", "Ringtone song", characters = """
    {
      "id": "song",
      "name": "Song",
      "sprite": "characters/song/cover.png",
      "ringtones": [
        { "file": "characters/song/song.ogg", "label": "Intro and loop", "loop": { "startMs": 1000, "endMs": 5000 }, "fadeInMs": 10000 },
        { "file": "characters/song/song.ogg", "label": "Last second", "loop": { "startMs": 5000 } },
        { "file": "characters/song/song.ogg", "label": "Once", "loop": false }
      ]
    }""".trimStart('\n')))
    media("characters/song/cover.png", spritePng)
    media("characters/song/song.ogg", ringtoneOgg)
}

folder("valid/all-formats", "One character per supported image format, each with a clip in a different audio format.") {
    val combos = listOf(
        Triple("png", "wav", spritePng to clipWav),
        Triple("jpeg", "m4a", spriteJpg to clipM4a),
        Triple("webp", "aac", spriteWebp to clipAac),
        Triple("gif", "flac", animGif to clipFlac),
        Triple("avif", "ogg", spriteAvif to clipVorbis),
        Triple("jxl", "mp3", spriteJxl to clipMp3),
        Triple("svg", "opus", null to clipOgg),
    )
    val chars = combos.joinToString(",\n") { (img, audio, _) ->
        val imgExt = if (img == "jpeg") "jpg" else img
        """
    {
      "id": "$img-$audio",
      "name": "${img.uppercase()} and ${audio.uppercase()}",
      "sprite": "characters/$img/sprite.$imgExt",
      "clips": [{ "file": "characters/$img/clip.$audio" }]
    }""".trimStart('\n')
    }
    text("pack.json", pack("all-formats", "All formats", characters = chars))
    for ((img, audio, files) in combos) {
        val imgExt = if (img == "jpeg") "jpg" else img
        if (files.first == null) text("characters/$img/sprite.$imgExt", svgSprite) else media("characters/$img/sprite.$imgExt", files.first!!)
        media("characters/$img/clip.$audio", files.second)
    }
}

folder("valid/bundle", "A bundle with a cover and two packs.") {
    text("bundle.json", """
{
  "formatVersion": 1,
  "type": "bundle",
  "id": "app.mauri.fixtures.bundle",
  "name": "Fixture bundle",
  "version": "1.0.0",
  "author": "Cucuto fixtures",
  "license": "CC0-1.0",
  "cover": "cover.png",
  "packs": ["packs/first", "packs/second"]
}
""".trimStart())
    media("cover.png", spritePng)
    for (name in listOf("first", "second")) {
        text("packs/$name/pack.json", pack("bundle.$name", "Bundle $name"))
        media("packs/$name/characters/beep/sprite.png", spritePng)
        media("packs/$name/characters/beep/clips/beep.ogg", clipOgg)
    }
}

folder(
    "valid/with-warnings",
    "Valid, with warnings: a free-text license, an unreferenced clip and a macOS __MACOSX artifact.",
    warnings = listOf("IGNORED_ENTRY", "LICENSE_NOT_SPDX", "UNREFERENCED_FILE"),
) {
    text("pack.json", pack("warnings", "Warnings").replace("\"CC0-1.0\"", "\"Personal use only\""))
    beep()
    media("characters/beep/clips/unused.ogg", clipOgg)
    text("__MACOSX/._pack.json", "resource fork")
}

archive("valid/minimal-pack.soundpack.zip", "The minimal pack as a zip archive, written the way packer writes archives.", bytes = zip(minimalEntries()))

archive(
    "valid/archive-with-directories.soundpack.zip",
    "Zip with explicit directory entries and a stored manifest (as made by common zip tools).",
    bytes = zip(
        listOf(
            ZipEntrySpec("characters/", ByteArray(0), unixMode = 0x41ED),
            ZipEntrySpec("characters/beep/", ByteArray(0), unixMode = 0x41ED),
            ZipEntrySpec("characters/beep/clips/", ByteArray(0), unixMode = 0x41ED),
        ) + minimalEntries().map { ZipEntrySpec(it.name, it.data) },
    ),
)

document("valid/repository.json", "repository", "Repository index with a pack and a bundle, relative and absolute URLs, without haptic previews (as indexes written before x-haptic).", """
{
  "formatVersion": 1,
  "name": "Fixture repository",
  "description": "Golden repository index.",
  "homepage": "https://cucuto.mauri.app",
  "generatedAt": "2026-10-23T12:00:00Z",
  "packs": [
    {
      "id": "app.mauri.fixtures.minimal",
      "type": "pack",
      "packFormatVersion": 1,
      "name": "Minimal pack",
      "version": "1.0.0",
      "author": "Cucuto fixtures",
      "license": "CC0-1.0",
      "tags": ["fixtures"],
      "cover": "previews/app.mauri.fixtures.minimal/cover.png",
      "characters": [
        { "id": "beep", "name": "Beep", "sprite": "previews/app.mauri.fixtures.minimal/beep.png", "sample": "previews/app.mauri.fixtures.minimal/beep.ogg", "seedColor": "#4CAF50" }
      ],
      "sizeUnpacked": 2048,
      "download": {
        "url": "packs/app.mauri.fixtures.minimal-1.0.0.soundpack.zip",
        "size": 1834,
        "sha256": "0000000000000000000000000000000000000000000000000000000000000000"
      }
    },
    {
      "id": "app.mauri.fixtures.bundle",
      "type": "bundle",
      "name": "Fixture bundle",
      "version": "1.0.0",
      "packs": [
        { "id": "app.mauri.fixtures.bundle.first", "name": "Bundle first", "version": "1.0.0", "characters": [{ "id": "beep", "name": "Beep" }] },
        { "id": "app.mauri.fixtures.bundle.second", "name": "Bundle second", "version": "1.0.0" }
      ],
      "download": {
        "url": "https://github.com/mauriciabad/cucuto/releases/download/packs-v1/app.mauri.fixtures.bundle-1.0.0.soundpack.zip",
        "size": 4096,
        "sha256": "1111111111111111111111111111111111111111111111111111111111111111"
      }
    }
  ]
}
""")
document("valid/repository-haptics.json", "repository", "Haptic previews (x-haptic): a custom pattern, none and auto in a pack, auto inside a bundle, and a character without one (older indexes).", """
{
  "formatVersion": 1,
  "name": "Fixture repository with haptic previews",
  "packs": [
    {
      "id": "app.mauri.fixtures.full",
      "type": "pack",
      "name": "Full pack",
      "version": "1.0.0",
      "characters": [
        { "id": "robin", "name": "Robin", "sample": "previews/app.mauri.fixtures.full/robin-sample.ogg",
          "x-haptic": { "mode": "custom", "events": [
            { "at": 0, "preset": "CLICK", "scale": 0.8 },
            { "at": 220, "initialSharpness": 0.3, "envelope": [{ "intensity": 1.0, "sharpness": 0.8, "ms": 120 }, { "intensity": 0.0, "sharpness": 0.4, "ms": 180 }] }
          ] } },
        { "id": "song", "name": "Song", "sample": "previews/app.mauri.fixtures.full/song-sample.flac", "x-haptic": { "mode": "none" } },
        { "id": "beep", "name": "Beep", "sample": "previews/app.mauri.fixtures.full/beep-sample.ogg", "x-haptic": { "mode": "auto" } },
        { "id": "old", "name": "Old", "sample": "previews/app.mauri.fixtures.full/old-sample.ogg" }
      ],
      "download": { "url": "packs/full.soundpack.zip", "size": 4096, "sha256": "0000000000000000000000000000000000000000000000000000000000000000" }
    },
    {
      "id": "app.mauri.fixtures.bundle",
      "type": "bundle",
      "name": "Fixture bundle",
      "version": "1.0.0",
      "packs": [
        { "id": "app.mauri.fixtures.bundle.first", "name": "Bundle first", "version": "1.0.0", "characters": [{ "id": "beep", "name": "Beep", "sample": "previews/b/beep.ogg", "x-haptic": { "mode": "auto" } }] }
      ],
      "download": { "url": "packs/bundle.soundpack.zip", "size": 4096, "sha256": "1111111111111111111111111111111111111111111111111111111111111111" }
    }
  ]
}
""")

val backupJson = """
{
  "formatVersion": 1,
  "createdAt": "2026-10-23T18:30:00Z",
  "app": { "id": "app.mauri.cucuto", "versionName": "1.0.0", "versionCode": 1 },
  "settings": { "enabled": true, "uiSounds": false },
  "repositories": [
    { "url": "https://cucuto.mauri.app/repo/repository.json", "name": "Official packs", "isDefault": true, "auth": { "type": "none" } },
    { "url": "https://packs.example.com/private/", "auth": { "type": "basic" } }
  ],
  "packs": [
    { "id": "app.mauri.demo.chiptune-critters", "type": "pack", "version": "1.0.0", "sha256": "2222222222222222222222222222222222222222222222222222222222222222", "size": 40000, "source": { "type": "repository", "repositoryUrl": "https://cucuto.mauri.app/repo/repository.json" } },
    { "id": "com.example.private.songs", "type": "bundle", "version": "3.0.0", "packs": ["com.example.private.songs.day"], "sha256": "3333333333333333333333333333333333333333333333333333333333333333", "source": { "type": "local", "fileName": "songs.soundpack.zip" } }
  ],
  "contacts": [
    {
      "id": "c1",
      "displayName": "Ada",
      "phoneNumbers": ["+34600111222"],
      "keys": [{ "package": "com.whatsapp", "conversationKey": "34600111222@s.whatsapp.net" }],
      "androidContact": { "lookupKey": "0r1-ABC", "displayName": "Ada Lovelace" },
      "message": { "packId": "app.mauri.demo.chiptune-critters", "characterId": "frog", "disabledClips": ["characters/frog/clips/croak.ogg"], "haptic": { "mode": "custom", "events": [{ "at": 0, "preset": "CLICK" }] } },
      "ringtone": { "packId": "com.example.private.songs.day", "characterId": "bubblegum", "rule": { "mode": "fixed", "variant": "characters/bubblegum/ringtones/live.ogg" } }
    },
    {
      "id": "c2",
      "displayName": "Bruno",
      "phoneNumbers": ["+34600333444"],
      "keys": [{ "package": "com.whatsapp", "conversationKey": "200000000000002@lid" }],
      "androidContact": { "lookupKey": "0r2-DEF", "displayName": "Bruno" },
      "message": { "packId": "app.mauri.demo.chiptune-critters", "characterId": "frog" },
      "ringtone": { "packId": "app.mauri.demo.chiptune-critters", "characterId": "frog", "followsVoice": true, "rule": { "mode": "time" }, "undo": { "originalRingtone": "content://media/internal/audio/media/31", "writtenUri": "content://media/external/audio/media/1042" } }
    },
    {
      "id": "c3",
      "displayName": "Carla",
      "phoneNumbers": ["+34600555666"],
      "keys": [{ "package": "com.whatsapp", "conversationKey": "300000000000003@lid" }],
      "androidContact": { "lookupKey": "0r3-GHI", "displayName": "Carla" },
      "message": { "packId": "app.mauri.demo.chiptune-critters", "characterId": "bird" },
      "ringtone": { "packId": "com.example.private.songs.day", "characterId": "bubblegum", "followsVoice": true, "rule": { "mode": "pack" } }
    },
    {
      "id": "c4",
      "displayName": "Dani",
      "phoneNumbers": ["+34600777888"],
      "keys": [{ "package": "com.whatsapp", "conversationKey": "34600777888@s.whatsapp.net" }],
      "androidContact": { "lookupKey": "0r4-JKL", "displayName": "Dani" },
      "ringtone": { "packId": "com.example.private.songs.day", "characterId": "bubblegum", "rule": { "mode": "random" } }
    },
    {
      "id": "g1",
      "displayName": "Family",
      "isGroup": true,
      "groupPolicy": "per-sender",
      "keys": [{ "package": "com.whatsapp.w4b", "conversationKey": "120363000000000000@g.us" }]
    }
  ]
}
"""

document("valid/backup.json", "backup", "Backup with repositories, pack references, contacts and phone ringtones (one follows the voice and keeps its undo record, one follows a voice without ringtones and keeps the song that rang, one rings with no message voice).", backupJson)

document("valid/envelope.json", "backup-envelope", "Encrypted backup envelope with Argon2id parameters.", """
{
  "formatVersion": 1,
  "type": "encrypted-backup",
  "cipher": "AES-256-GCM",
  "kdf": { "name": "argon2id", "memoryKiB": 65536, "iterations": 3, "parallelism": 1, "salt": "c2FsdHNhbHRzYWx0c2FsdA==" },
  "nonce": "bm9uY2Vub25jZW5v",
  "payload": "payload.bin"
}
""")

// ================================================================== invalid: archive

archive("invalid/archive-not-zip.soundpack.zip", "Plain text renamed to .soundpack.zip.", listOf("ARCHIVE_UNREADABLE"), bytes = "This is not a zip archive.\n".toByteArray())
archive("invalid/archive-truncated.soundpack.zip", "A valid archive cut in half (no central directory).", listOf("ARCHIVE_UNREADABLE"), bytes = zip(minimalEntries()).let { it.copyOf(it.size / 2) })
archive(
    "invalid/archive-encrypted.soundpack.zip", "An entry with the encryption flag set.", listOf("ARCHIVE_UNSUPPORTED"),
    bytes = zip(minimalEntries().map { if (it.name.endsWith(".ogg")) ZipEntrySpec(it.name, it.data, flags = 0x0801) else it }),
)
archive(
    "invalid/archive-bzip2.soundpack.zip", "An entry declaring bzip2 compression (method 12).", listOf("ARCHIVE_UNSUPPORTED"),
    bytes = zip(minimalEntries().map { if (it.name.endsWith(".png")) ZipEntrySpec(it.name, it.data, method = 12) else it }),
)
archive(
    "invalid/archive-zip-bomb.soundpack.zip", "8 MiB of zeros compressed about 1000:1 (decompression ratio cap).", listOf("COMPRESSION_RATIO_EXCEEDED"),
    bytes = zip(minimalEntries() + ZipEntrySpec("README.txt", ByteArray(8 * 1024 * 1024), deflate = true)),
)
archive(
    "invalid/archive-crc-mismatch.soundpack.zip", "pack.json with a wrong CRC-32.", listOf("ARCHIVE_UNREADABLE"),
    bytes = zip(minimalEntries().map { if (it.name == "pack.json") ZipEntrySpec(it.name, it.data, deflate = true, crcOverride = 0x12345678) else it }),
)
archive("invalid/zip-slip.soundpack.zip", "An entry escaping the target folder with '..'.", listOf("UNSAFE_PATH"), bytes = zip(minimalEntries() + ZipEntrySpec("../evil.txt", "owned\n".toByteArray())))
archive("invalid/zip-absolute-path.soundpack.zip", "An entry with an absolute path.", listOf("UNSAFE_PATH"), bytes = zip(minimalEntries() + ZipEntrySpec("/tmp/evil.txt", "owned\n".toByteArray())))
archive("invalid/zip-backslash.soundpack.zip", "An entry using Windows separators to escape.", listOf("UNSAFE_PATH"), bytes = zip(minimalEntries() + ZipEntrySpec("..\\evil.txt", "owned\n".toByteArray())))
archive(
    "invalid/zip-symlink.soundpack.zip", "A Unix symbolic link entry pointing outside the pack.", listOf("UNSAFE_ENTRY"),
    bytes = zip(minimalEntries() + ZipEntrySpec("characters/beep/clips/link.ogg", "/etc/passwd".toByteArray(), unixMode = 0xA1FF)),
)
archive(
    "invalid/zip-duplicate-entry.soundpack.zip", "Two entries whose paths differ only in case.", listOf("DUPLICATE_ENTRY"),
    bytes = zip(minimalEntries() + ZipEntrySpec("characters/beep/clips/BEEP.ogg", clipOgg.readBytes())),
)

// ================================================================== invalid: layout

folder("invalid/manifest-missing", "No pack.json or bundle.json at the root.", listOf("MANIFEST_MISSING")) {
    beep()
    text("nested/pack.json", minimalManifest)
}
folder("invalid/manifest-ambiguous", "Both pack.json and bundle.json at the root.", listOf("MANIFEST_AMBIGUOUS")) {
    text("pack.json", minimalManifest)
    text("bundle.json", "{}\n")
    beep()
}
folder("invalid/executable-extension", "A shell script inside the pack.", listOf("EXECUTABLE_FILE")) {
    text("pack.json", pack("exec-ext"))
    beep()
    text("characters/beep/run.sh", "#!/bin/sh\necho owned\n")
}
folder("invalid/executable-disguised", "An ELF binary renamed to .ogg.", listOf("EXECUTABLE_FILE")) {
    text("pack.json", pack("exec-magic"))
    media("characters/beep/sprite.png", spritePng)
    bytes("characters/beep/clips/beep.ogg", byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 2, 1, 1, 0) + ByteArray(56))
}
folder("invalid/unknown-file-type", "A file type that packs may not contain.", listOf("UNKNOWN_FILE_TYPE")) {
    text("pack.json", pack("unknown-type"))
    beep()
    text("characters/beep/notes.docx", "not really a document\n")
}
folder("invalid/stray-json", "A JSON file that is not a manifest.", listOf("UNKNOWN_FILE_TYPE")) {
    text("pack.json", pack("stray-json"))
    beep()
    text("characters/beep/data.json", "{}\n")
}
folder("invalid/invalid-file-name", "A file name with a space.", listOf("INVALID_FILE_NAME")) {
    text("pack.json", pack("file-name"))
    beep()
    media("characters/beep/clips/my beep.ogg", clipOgg)
}

// ================================================================== invalid: manifest

folder("invalid/json-syntax", "pack.json with a trailing comma.", listOf("MANIFEST_INVALID_JSON")) {
    text("pack.json", pack("json-syntax").replace("\"version\": \"1.0.0\",", "\"version\": \"1.0.0\",,"))
    beep()
}
folder("invalid/json-bom", "pack.json starting with a UTF-8 byte order mark.", listOf("MANIFEST_INVALID_JSON")) {
    bytes("pack.json", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + pack("json-bom").toByteArray())
    beep()
}
folder("invalid/format-version-2", "A pack from a future format version.", listOf("FORMAT_VERSION_UNSUPPORTED")) {
    text("pack.json", pack("future").replace("\"formatVersion\": 1", "\"formatVersion\": 2"))
    beep()
}
folder("invalid/schema-missing-name", "pack.json without the required name.", listOf("SCHEMA_VIOLATION")) {
    text("pack.json", pack("no-name").replace("  \"name\": \"Fixture\",\n", ""))
    beep()
}
folder("invalid/schema-bad-id", "A pack ID that is not lowercase reverse-domain.", listOf("SCHEMA_VIOLATION")) {
    text("pack.json", pack("x").replace("app.mauri.fixtures.x", "My Pack"))
    beep()
}
folder("invalid/schema-unknown-property", "An unknown property without the x- prefix.", listOf("SCHEMA_VIOLATION")) {
    text("pack.json", pack("unknown-prop", extra = "\n  \"colour\": \"red\","))
    beep()
}
folder("invalid/schema-path-traversal", "A manifest path that climbs out of the pack.", listOf("SCHEMA_VIOLATION")) {
    text("pack.json", pack("traversal").replace("characters/beep/sprite.png", "../sprite.png"))
    beep()
}
folder("invalid/schema-no-clips", "A character with neither clips nor ringtones.", listOf("SCHEMA_VIOLATION")) {
    text("pack.json", pack("no-clips").replace("\"clips\": [{ \"file\": \"characters/beep/clips/beep.ogg\" }]", "\"clips\": []"))
    media("characters/beep/sprite.png", spritePng)
}
folder("invalid/schema-unknown-preset", "A haptic preset that VibrationEffect.Builder does not have.", listOf("SCHEMA_VIOLATION")) {
    text("pack.json", pack("preset", character = ",\n  \"haptic\": { \"mode\": \"custom\", \"events\": [{ \"at\": 0, \"preset\": \"BUZZ\" }] }"))
    beep()
}
folder("invalid/schema-bad-color", "A seed color that is not #RRGGBB.", listOf("SCHEMA_VIOLATION")) {
    text("pack.json", pack("color", character = ",\n  \"seedColor\": \"green\""))
    beep()
}

// ================================================================== invalid: references

folder("invalid/duplicate-character-id", "Two characters with the same ID.", listOf("DUPLICATE_ID")) {
    val c = """
    {
      "id": "beep",
      "name": "Beep",
      "sprite": "characters/beep/sprite.png",
      "clips": [{ "file": "characters/beep/clips/beep.ogg" }]
    }""".trimStart('\n')
    text("pack.json", pack("dup-char", characters = "$c,\n$c"))
    beep()
}
folder("invalid/file-missing", "A clip that is not in the pack.", listOf("FILE_MISSING")) {
    text("pack.json", pack("missing").replace("clips/beep.ogg", "clips/gone.ogg"))
    media("characters/beep/sprite.png", spritePng)
}
folder("invalid/file-type-mismatch", "An image used as a clip.", listOf("FILE_TYPE_MISMATCH")) {
    text("pack.json", pack("mismatch").replace("characters/beep/clips/beep.ogg", "characters/beep/sprite.png"))
    media("characters/beep/sprite.png", spritePng)
}
folder("invalid/animated-sprite-png", "A PNG as spriteAnimated (only GIF, WebP and JPEG XL can animate).", listOf("FILE_TYPE_MISMATCH")) {
    text("pack.json", pack("anim-png", character = ",\n  \"spriteAnimated\": \"characters/beep/anim.png\""))
    beep()
    media("characters/beep/anim.png", spritePng)
}
folder("invalid/haptic-envelope-not-zero", "An envelope that does not end at intensity 0.", listOf("HAPTIC_INVALID")) {
    text("pack.json", pack("env-zero", character = ",\n  \"haptic\": { \"mode\": \"custom\", \"events\": [{ \"at\": 0, \"envelope\": [{ \"intensity\": 1, \"sharpness\": 0.5, \"ms\": 200 }] }] }"))
    beep()
}
folder("invalid/haptic-unsorted", "Haptic events out of time order.", listOf("HAPTIC_INVALID")) {
    text("pack.json", pack("unsorted", character = ",\n  \"haptic\": { \"mode\": \"custom\", \"events\": [{ \"at\": 300, \"preset\": \"CLICK\" }, { \"at\": 100, \"preset\": \"TICK\" }] }"))
    beep()
}
folder("invalid/haptic-overlap", "A preset that starts while an envelope is still playing.", listOf("HAPTIC_INVALID")) {
    text("pack.json", pack("overlap", character = ",\n  \"haptic\": { \"mode\": \"custom\", \"events\": [{ \"at\": 0, \"envelope\": [{ \"intensity\": 1, \"sharpness\": 0.5, \"ms\": 200 }, { \"intensity\": 0, \"sharpness\": 0.5, \"ms\": 100 }] }, { \"at\": 100, \"preset\": \"TICK\" }] }"))
    beep()
}
folder("invalid/haptic-too-long", "A custom haptic longer than 5 s.", listOf("HAPTIC_INVALID")) {
    text("pack.json", pack("long-haptic", character = ",\n  \"haptic\": { \"mode\": \"custom\", \"events\": [{ \"at\": 4000, \"envelope\": [{ \"intensity\": 1, \"sharpness\": 0.5, \"ms\": 1000 }, { \"intensity\": 0, \"sharpness\": 0.5, \"ms\": 500 }] }] }"))
    beep()
}
folder("invalid/when-empty-range", "A time range that starts and ends at the same minute.", listOf("WHEN_INVALID")) {
    text("pack.json", pack("when", character = ",\n  \"ringtones\": [{ \"file\": \"characters/beep/ring.ogg\", \"when\": { \"time\": { \"from\": \"08:00\", \"to\": \"08:00\" } } }]"))
    beep()
    media("characters/beep/ring.ogg", ringtoneOgg)
}
folder("invalid/clip-when-empty-range", "A clip whose time range starts and ends at the same minute.", listOf("WHEN_INVALID")) {
    text("pack.json", pack("clip-when", characters = """    {
      "id": "beep",
      "name": "Beep",
      "sprite": "characters/beep/sprite.png",
      "clips": [{ "file": "characters/beep/clips/beep.ogg", "when": { "time": { "from": "21:30", "to": "21:30" } } }]
    }"""))
    beep()
}
folder("invalid/loop-reversed", "Loop points with the end before the start.", listOf("LOOP_POINTS_INVALID")) {
    text("pack.json", pack("loop-rev", character = ",\n  \"ringtones\": [{ \"file\": \"characters/beep/ring.ogg\", \"loop\": { \"startMs\": 4000, \"endMs\": 1000 } }]"))
    beep()
    media("characters/beep/ring.ogg", ringtoneOgg)
}
folder("invalid/loop-too-short", "A loop section of 500 ms (the minimum is 1 s).", listOf("LOOP_POINTS_INVALID")) {
    text("pack.json", pack("loop-short", character = ",\n  \"ringtones\": [{ \"file\": \"characters/beep/ring.ogg\", \"loop\": { \"startMs\": 2000, \"endMs\": 2500 } }]"))
    beep()
    media("characters/beep/ring.ogg", ringtoneOgg)
}
folder("invalid/bundle-pack-missing", "A bundle listing a folder without pack.json.", listOf("BUNDLE_PACK_MISSING")) {
    text("bundle.json", """
{
  "formatVersion": 1,
  "type": "bundle",
  "id": "app.mauri.fixtures.bundle-missing",
  "name": "Missing pack",
  "version": "1.0.0",
  "packs": ["packs/first", "packs/ghost"]
}
""".trimStart())
    text("packs/first/pack.json", pack("bundle-missing.first"))
    media("packs/first/characters/beep/sprite.png", spritePng)
    media("packs/first/characters/beep/clips/beep.ogg", clipOgg)
}
folder("invalid/bundle-duplicate-pack-id", "Two packs in a bundle with the same ID.", listOf("DUPLICATE_ID")) {
    text("bundle.json", """
{
  "formatVersion": 1,
  "type": "bundle",
  "id": "app.mauri.fixtures.bundle-dup",
  "name": "Duplicate packs",
  "version": "1.0.0",
  "packs": ["packs/a", "packs/b"]
}
""".trimStart())
    for (name in listOf("a", "b")) {
        text("packs/$name/pack.json", pack("same"))
        media("packs/$name/characters/beep/sprite.png", spritePng)
        media("packs/$name/characters/beep/clips/beep.ogg", clipOgg)
    }
}
folder("invalid/bundle-nested", "A bundle whose pack folder contains another bundle.json.", listOf("BUNDLE_LAYOUT_INVALID")) {
    text("bundle.json", """
{
  "formatVersion": 1,
  "type": "bundle",
  "id": "app.mauri.fixtures.bundle-nested",
  "name": "Nested bundle",
  "version": "1.0.0",
  "packs": ["packs/inner"]
}
""".trimStart())
    text("packs/inner/pack.json", pack("bundle-nested.inner"))
    text("packs/inner/bundle.json", "{}\n")
    media("packs/inner/characters/beep/sprite.png", spritePng)
    media("packs/inner/characters/beep/clips/beep.ogg", clipOgg)
}

// ================================================================== invalid: media

folder("invalid/clip-too-short", "A 0.1 s clip.", listOf("CLIP_DURATION_OUT_OF_RANGE")) {
    text("pack.json", pack("short"))
    media("characters/beep/sprite.png", spritePng)
    media("characters/beep/clips/beep.ogg", clipShort)
}
folder("invalid/clip-too-long", "A 31 s clip (the maximum is 30 s).", listOf("CLIP_DURATION_OUT_OF_RANGE")) {
    text("pack.json", pack("long"))
    media("characters/beep/sprite.png", spritePng)
    media("characters/beep/clips/beep.ogg", clipLong)
}
folder("invalid/ringtone-too-short", "A 3 s ringtone.", listOf("RINGTONE_DURATION_OUT_OF_RANGE")) {
    text("pack.json", pack("ring-short", character = ",\n  \"ringtones\": [{ \"file\": \"characters/beep/ring.ogg\" }]"))
    beep()
    media("characters/beep/ring.ogg", ringtoneShort)
}
folder("invalid/loop-beyond-end", "Loop end after the end of a 6 s ringtone.", listOf("LOOP_POINTS_INVALID")) {
    text("pack.json", pack("loop-end", character = ",\n  \"ringtones\": [{ \"file\": \"characters/beep/ring.ogg\", \"loop\": { \"startMs\": 1000, \"endMs\": 9000 } }]"))
    beep()
    media("characters/beep/ring.ogg", ringtoneOgg)
}
folder("invalid/loop-start-beyond-end", "An open loop (no endMs) starting 500 ms before the end of a 6 s ringtone: shorter than 1 s.", listOf("LOOP_POINTS_INVALID")) {
    text("pack.json", pack("loop-start", character = ",\n  \"ringtones\": [{ \"file\": \"characters/beep/ring.ogg\", \"loop\": { \"startMs\": 5500 } }]"))
    beep()
    media("characters/beep/ring.ogg", ringtoneOgg)
}
folder("invalid/audio-undecodable", "Text saved as .ogg.", listOf("MEDIA_UNDECODABLE")) {
    text("pack.json", pack("bad-audio"))
    media("characters/beep/sprite.png", spritePng)
    text("characters/beep/clips/beep.ogg", "not audio\n")
}
folder("invalid/image-undecodable", "A truncated PNG.", listOf("MEDIA_UNDECODABLE")) {
    text("pack.json", pack("bad-image"))
    bytes("characters/beep/sprite.png", spritePng.readBytes().copyOf(12))
    media("characters/beep/clips/beep.ogg", clipOgg)
}
folder("invalid/image-too-large", "A 600x600 px sprite (limit 512 px per side).", listOf("IMAGE_TOO_LARGE")) {
    text("pack.json", pack("big-image"))
    media("characters/beep/sprite.png", bigPng)
    media("characters/beep/clips/beep.ogg", clipOgg)
}

fun svgFixture(name: String, description: String, svg: String, code: String = "SVG_UNSAFE") =
    folder("invalid/$name", description, listOf(code)) {
        text("pack.json", pack(name).replace("characters/beep/sprite.png", "characters/beep/sprite.svg"))
        text("characters/beep/sprite.svg", svg.trimIndent() + "\n")
        media("characters/beep/clips/beep.ogg", clipOgg)
    }

svgFixture("svg-script", "An SVG with a script element.", """
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10">
      <script>alert(1)</script>
      <rect width="10" height="10" fill="#000"/>
    </svg>
""")
svgFixture("svg-event-handler", "An SVG with an onload handler.", """
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10" onload="alert(1)">
      <rect width="10" height="10" fill="#000"/>
    </svg>
""")
svgFixture("svg-external-image", "An SVG that loads an image from the network.", """
    <svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 10 10">
      <image xlink:href="https://example.com/track.png" width="10" height="10"/>
    </svg>
""")
svgFixture("svg-entity", "An SVG with a DOCTYPE declaring an external entity.", """
    <?xml version="1.0"?>
    <!DOCTYPE svg [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><text>&xxe;</text></svg>
""")
svgFixture("svg-css-import", "An SVG whose stylesheet imports a remote file.", """
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10">
      <style>@import url("https://example.com/x.css"); rect { fill: red; }</style>
      <rect width="10" height="10"/>
    </svg>
""")
svgFixture("svg-encoded-javascript", "An SVG link hiding javascript: behind character references.", """
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10">
      <a href="&#106;avascript:alert(1)"><rect width="10" height="10"/></a>
    </svg>
""")
svgFixture("svg-not-svg", "An HTML document saved as .svg.", """
    <html><body><p>not an svg</p></body></html>
""", code = "MEDIA_UNDECODABLE")

// ================================================================== invalid: documents

fun repo(entries: String, extra: String = "") = """
{
  "formatVersion": 1,
  "name": "Fixture repository",$extra
  "packs": [
$entries
  ]
}
"""

val repoEntry = """
    {
      "id": "app.mauri.fixtures.minimal",
      "type": "pack",
      "name": "Minimal pack",
      "version": "1.0.0",
      "download": { "url": "packs/minimal.soundpack.zip", "size": 1834, "sha256": "0000000000000000000000000000000000000000000000000000000000000000" }
    }"""

document("invalid/repository-http-url.json", "repository", "A download over plain http.", repo(repoEntry.replace("packs/minimal.soundpack.zip", "http://example.com/minimal.soundpack.zip")), listOf("SCHEMA_VIOLATION"))
document("invalid/repository-duplicate-id.json", "repository", "The same pack ID listed twice.", repo("$repoEntry,$repoEntry"), listOf("DUPLICATE_ID"))
document(
    "invalid/repository-bundle-without-packs.json", "repository", "A bundle entry without its packs list.",
    repo(repoEntry.replace("\"type\": \"pack\"", "\"type\": \"bundle\"")), listOf("SCHEMA_VIOLATION"),
)
document("invalid/repository-format-version-2.json", "repository", "An index from a future format version.", repo(repoEntry).replace("\"formatVersion\": 1", "\"formatVersion\": 2"), listOf("FORMAT_VERSION_UNSUPPORTED"))
val repoHapticEntry = repoEntry.replace(
    "\"version\": \"1.0.0\",",
    "\"version\": \"1.0.0\",\n      \"characters\": [{ \"id\": \"beep\", \"name\": \"Beep\", \"sample\": \"beep.ogg\", HAPTIC }],",
)
document(
    "invalid/repository-haptic-without-events.json", "repository", "A custom x-haptic without its events.",
    repo(repoHapticEntry.replace("HAPTIC", "\"x-haptic\": { \"mode\": \"custom\" }")), listOf("SCHEMA_VIOLATION"),
)
document(
    "invalid/repository-haptic-plain-key.json", "repository", "A character preview with \"haptic\" instead of \"x-haptic\" (apps released before haptic previews would reject it).",
    repo(repoHapticEntry.replace("HAPTIC", "\"haptic\": { \"mode\": \"auto\" }")), listOf("SCHEMA_VIOLATION"),
)
document(
    "invalid/backup-missing-contacts.json", "backup", "A backup without the contacts list.",
    backupJson.substringBefore("  \"contacts\": [").trimEnd().removeSuffix(",") + "\n}\n", listOf("SCHEMA_VIOLATION"),
)
document("invalid/backup-duplicate-contact.json", "backup", "Two contacts with the same ID.", backupJson.replace("\"id\": \"g1\"", "\"id\": \"c1\""), listOf("DUPLICATE_ID"))
document(
    "invalid/backup-ringtone-fixed-without-variant.json", "backup", "A fixed phone ringtone rule without its variant.",
    backupJson.replace("\"rule\": { \"mode\": \"fixed\", \"variant\": \"characters/bubblegum/ringtones/live.ogg\" }", "\"rule\": { \"mode\": \"fixed\" }"),
    listOf("SCHEMA_VIOLATION"),
)
document(
    "invalid/backup-ringtone-undo-without-uri.json", "backup", "A phone ringtone's undo record without the URI Cucuto wrote.",
    backupJson.replace(", \"writtenUri\": \"content://media/external/audio/media/1042\"", ""),
    listOf("SCHEMA_VIOLATION"),
)
document("invalid/envelope-weak-kdf.json", "backup-envelope", "Argon2id memory below the 19 MiB minimum.", """
{
  "formatVersion": 1,
  "type": "encrypted-backup",
  "cipher": "AES-256-GCM",
  "kdf": { "name": "argon2id", "memoryKiB": 1024, "iterations": 1, "parallelism": 1, "salt": "c2FsdHNhbHRzYWx0c2FsdA==" },
  "nonce": "bm9uY2Vub25jZW5v",
  "payload": "payload.bin"
}
""", listOf("SCHEMA_VIOLATION"))

// ================================================================== manifest

fun jsonString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
fun jsonList(l: List<String>) = l.joinToString(", ", "[", "]") { jsonString(it) }

val manifest = buildString {
    appendLine("{")
    appendLine("  \"\$comment\": \"Generated by generate.main.kts. Each fixture lists the exact sets of error and warning codes a validator must report (docs/pack-format.md#validation-stages).\",")
    appendLine("  \"formatVersion\": 1,")
    appendLine("  \"fixtures\": [")
    appendLine(
        fixtures.sortedBy { it.path }.joinToString(",\n") { f ->
            "    { \"path\": ${jsonString(f.path)}, \"kind\": ${jsonString(f.kind)}, \"valid\": ${f.errors.isEmpty()}, " +
                "\"errors\": ${jsonList(f.errors.sorted())}, \"warnings\": ${jsonList(f.warnings.sorted())},\n      \"description\": ${jsonString(f.description)} }"
        },
    )
    appendLine("  ]")
    appendLine("}")
}
File(root, "fixtures.json").writeText(manifest)

// ================================================================== ring files

// Golden ring file plans (docs/pack-format.md#ring-files): how a ringtone's loop settings become the exported file.
// Every renderer (packer, the app) must produce this layout; shared/pack-spec's RingFiles is tested against it.
data class RingCase(
    val description: String,
    val sourceMs: Long,
    val loop: String?,
    val fadeInMs: Long,
    val loopStartMs: Long,
    val loopEndMs: Long,
    val loopCount: Int,
    val silenceMs: Long,
    val totalMs: Long,
    val passThrough: Boolean,
)

val ringCases = listOf(
    RingCase("Whole-file loop (the default) of a 6 s ringtone: repeated to 90 s.", 6000, null, 0, 0, 6000, 15, 0, 90000, false),
    RingCase("loop: true is the same as no loop key.", 6000, "true", 0, 0, 6000, 15, 0, 90000, false),
    RingCase("Loop section from the start, the 1 s after it is an outro that never plays; the fade-in happens once.", 6000, "{ \"startMs\": 0, \"endMs\": 5000 }", 500, 0, 5000, 18, 0, 90000, false),
    RingCase("Intro of 1 s, then the open loop to the end, repeated until the file reaches 90 s.", 6000, "{ \"startMs\": 1000 }", 0, 1000, 6000, 18, 0, 91000, false),
    RingCase("Intro, loop and outro, as in the kitchen orchestra demo ringtone.", 14000, "{ \"startMs\": 4000, \"endMs\": 12000 }", 500, 4000, 12000, 11, 0, 92000, false),
    RingCase("A loop end within the 50 ms tolerance past the end of the audio is clamped to the end.", 6000, "{ \"endMs\": 6030 }", 0, 0, 6000, 15, 0, 90000, false),
    RingCase("Play once, padded with silence to 90 s so it does not restart right away.", 6000, "false", 0, 0, 6000, 1, 84000, 90000, false),
    RingCase("Play once, longer than 90 s and without fade-in: the source file is the ring file.", 120000, "false", 0, 0, 120000, 1, 0, 120000, true),
    RingCase("Whole-file loop longer than 90 s: the source file is the ring file.", 120000, null, 0, 0, 120000, 1, 0, 120000, true),
    RingCase("The same with a fade-in: rendered once, as the fade must not repeat.", 120000, null, 1500, 0, 120000, 1, 0, 120000, false),
    RingCase("A long loop that already passes 90 s plays once; the outro is cut.", 360000, "{ \"startMs\": 60000, \"endMs\": 300000 }", 0, 60000, 300000, 1, 0, 300000, false),
    RingCase("An intro that almost reaches 90 s, then the 1.5 s loop once.", 100000, "{ \"startMs\": 89500, \"endMs\": 91000 }", 0, 89500, 91000, 1, 0, 91000, false),
)

val ringJson = buildString {
    appendLine("{")
    appendLine("  \"\$comment\": \"Generated by generate.main.kts. Golden ring file plans (docs/pack-format.md#ring-files): a ringtone measuring sourceMs with these loop and fadeInMs values renders as the intro [0, loopStartMs) once, the loop section [loopStartMs, loopEndMs) loopCount times, then silenceMs of silence, with a fade-in over the first fadeInMs. passThrough means the source file can be used as is. A missing loop is the default (true).\",")
    appendLine("  \"ringFileMinMs\": 90000,")
    appendLine("  \"cases\": [")
    appendLine(
        ringCases.joinToString(",\n") { c ->
            "    { \"description\": ${jsonString(c.description)},\n      \"sourceMs\": ${c.sourceMs}, " +
                (c.loop?.let { "\"loop\": $it, " } ?: "") + "\"fadeInMs\": ${c.fadeInMs},\n" +
                "      \"expected\": { \"loopStartMs\": ${c.loopStartMs}, \"loopEndMs\": ${c.loopEndMs}, \"loopCount\": ${c.loopCount}, " +
                "\"silenceMs\": ${c.silenceMs}, \"fadeInMs\": ${c.fadeInMs}, \"totalMs\": ${c.totalMs}, \"passThrough\": ${c.passThrough} } }"
        },
    )
    appendLine("  ]")
    appendLine("}")
}
File(root, "ring-files.json").writeText(ringJson)
work.deleteRecursively()
println("Wrote ${fixtures.size} fixtures to $root")
