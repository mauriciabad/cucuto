package app.mauri.cucuto.core.pack

import app.mauri.cucuto.core.pack.media.AudioProbe
import app.mauri.cucuto.core.pack.media.AudioProbeResult
import app.mauri.cucuto.core.pack.media.ImageProbe
import app.mauri.cucuto.core.pack.media.ImageProbeResult
import app.mauri.cucuto.core.pack.media.MediaVerifier
import app.mauri.cucuto.packspec.FileFormat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import app.mauri.cucuto.packspec.media.AudioProbe as HeaderAudioProbe
import app.mauri.cucuto.packspec.media.ImageProbe as HeaderImageProbe

/** spec/ at the repository root (set by the Gradle test task). */
val specDir: File = File(System.getProperty("cucuto.specDir") ?: error("cucuto.specDir is not set"))

val fixturesDir: File = File(specDir, "fixtures")

/**
 * Stand-ins for the platform decoders on the JVM: they "decode" with the header parsers of pack-spec, so the whole
 * import pipeline runs in unit tests. The real decoders are covered by the instrumented tests.
 */
val headerAudioProbe =
    AudioProbe { file, format, _ ->
        HeaderAudioProbe.durationMs(format, file.readBytes())?.let { AudioProbeResult.Decoded(it) }
            ?: AudioProbeResult.Undecodable("Unreadable header")
    }

val headerImageProbe =
    ImageProbe { file, format ->
        if (format == FileFormat.SVG) {
            ImageProbeResult.NotChecked("SVG")
        } else {
            HeaderImageProbe.size(format, file.readBytes())?.let { ImageProbeResult.Decoded(it.width, it.height) }
                ?: ImageProbeResult.Undecodable("Unreadable header")
        }
    }

fun testImporter(
    root: File,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    audio: AudioProbe = headerAudioProbe,
    image: ImageProbe = headerImageProbe,
    analyzer: PackAnalyzer? = null,
): PackImporter =
    PackImporter(
        store = PackStore(File(root, "files/packs")),
        workDir = File(root, "cache/pack-import"),
        verifier = MediaVerifier(audio, image),
        ioDispatcher = dispatcher,
        analyzer = analyzer,
    )

/** Zips a fixture folder (every regular file, sorted, deflated) the way a user's zip tool would. */
fun zipFolder(
    folder: File,
    target: File,
    transform: (String, ByteArray) -> ByteArray = { _, b -> b },
): File {
    ZipOutputStream(target.outputStream()).use { zip ->
        val files = folder.walkTopDown().filter { it.isFile }.map { it.relativeTo(folder).invariantSeparatorsPath }
        for (path in files.sorted()) {
            zip.putNextEntry(ZipEntry(path))
            zip.write(transform(path, File(folder, path).readBytes()))
            zip.closeEntry()
        }
    }
    return target
}

/**
 * The path of an archive fixture by its name without extension ("valid/minimal-pack"), whatever its extension
 * (`.soundpack.zip`).
 */
fun archiveFixture(stem: String): String {
    val dir = File(fixturesDir, stem.substringBeforeLast('/'))
    val name = stem.substringAfterLast('/')
    val file =
        dir
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith("$name.") }
            .minBy { it.name.length }
    return file.relativeTo(fixturesDir).invariantSeparatorsPath
}

/** A fixture as an archive: `.soundpack.zip` files as they are, folders zipped into [tmp]. */
fun fixtureArchive(
    path: String,
    tmp: File,
): File {
    val file = File(fixturesDir, path)
    return if (file.isDirectory) zipFolder(file, File(tmp, path.replace('/', '_') + ".zip")) else file
}
