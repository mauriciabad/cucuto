package app.mauri.cucuto.core.haptics.auto

import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.RoutingCodec
import app.mauri.cucuto.core.routing.RoutingFormatException
import java.io.File
import java.io.IOException

/**
 * The auto haptics of one installed pack version: every analysed clip and its envelope (null for a silent clip).
 *
 * @property analyzerVersion [AutoHapticAnalyzer.VERSION] that produced the envelopes. Older analyses are redone.
 * @property maxControlPoints The point budget used, so a restore on a device with a smaller budget can redo them.
 * @property minControlPointMs The shortest segment used.
 * @property clips Clip path from `pack.json` → envelope (a one-event [Haptic.Custom]) or null when silent.
 */
data class AutoHapticSet(
    val analyzerVersion: Int,
    val maxControlPoints: Int,
    val minControlPointMs: Int,
    val clips: Map<String, Haptic.Custom?>,
)

/**
 * Storage of an [AutoHapticSet] next to the pack it belongs to, at [RELATIVE_PATH] inside the installed version
 * folder (`files/packs/<pack id>/<version>/.cucuto/auto-haptics.txt`). Pack paths never start with a dot, so the
 * file cannot clash with pack content, and it moves, stays and goes with its version folder.
 *
 * Plain text, tab separated, in the routing table's haptic syntax so the app copies envelopes into the table
 * without converting them:
 *
 * ```
 * cucuto-auto-haptics⇥1⇥<analyzerVersion>⇥<maxControlPoints>⇥<minControlPointMs>⇥<clip count>
 * <clip path>⇥<c… haptic, or - for a silent clip>
 * ```
 *
 * Readers reject a newer format version and ignore unknown trailing fields.
 */
object AutoHapticFile {
    const val MAGIC: String = "cucuto-auto-haptics"
    const val FORMAT_VERSION: Int = 1
    const val RELATIVE_PATH: String = ".cucuto/auto-haptics.txt"

    private const val SILENT = "-"

    fun encode(set: AutoHapticSet): String {
        val out = StringBuilder(64 + set.clips.size * 160)
        out
            .append(MAGIC)
            .append('\t')
            .append(FORMAT_VERSION)
            .append('\t')
            .append(set.analyzerVersion)
            .append('\t')
            .append(set.maxControlPoints)
            .append('\t')
            .append(set.minControlPointMs)
            .append('\t')
            .append(set.clips.size)
            .append('\n')
        for ((path, haptic) in set.clips) {
            require(path.isNotEmpty() && path.none { it == '\t' || it == '\n' || it == '\r' }) {
                "Invalid clip path '$path'"
            }
            out.append(path).append('\t')
            out.append(if (haptic == null) SILENT else RoutingCodec.encodeHaptic(haptic)).append('\n')
        }
        return out.toString()
    }

    /** Decodes [encode]'s output. Throws [IllegalArgumentException] when it is malformed or from a newer format. */
    fun decode(text: String): AutoHapticSet {
        val lines = text.split('\n').filter { it.isNotEmpty() }
        require(lines.isNotEmpty()) { "Empty auto haptics file" }
        val header = lines[0].split('\t')
        require(header.size >= 6 && header[0] == MAGIC) { "Not an auto haptics file" }
        val version = header[1].toInt()
        require(version in 1..FORMAT_VERSION) { "Auto haptics format $version is not supported" }
        val count = header[5].toInt()
        require(lines.size - 1 == count) { "Expected $count clips, found ${lines.size - 1}" }
        val clips = LinkedHashMap<String, Haptic.Custom?>(count * 2)
        for (line in lines.drop(1)) {
            val fields = line.split('\t')
            require(fields.size >= 2 && fields[0].isNotEmpty()) { "Malformed line '$line'" }
            clips[fields[0]] =
                if (fields[1] == SILENT) {
                    null
                } else {
                    try {
                        RoutingCodec.decodeHaptic(fields[1]) as? Haptic.Custom
                    } catch (e: RoutingFormatException) {
                        throw IllegalArgumentException(e.message, e)
                    } ?: throw IllegalArgumentException("Clip haptic must be custom: '$line'")
                }
        }
        return AutoHapticSet(header[2].toInt(), header[3].toInt(), header[4].toInt(), clips)
    }

    /** The stored set of the pack version in [packDir], or null when there is none or it is unreadable. */
    fun read(packDir: File): AutoHapticSet? {
        val file = File(packDir, RELATIVE_PATH)
        if (!file.isFile) return null
        return try {
            decode(file.readText())
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Writes [set] for the pack version in [packDir], atomically (temporary file, then rename). Blocking IO. */
    fun write(
        packDir: File,
        set: AutoHapticSet,
    ) {
        val file = File(packDir, RELATIVE_PATH)
        val dir = file.parentFile ?: throw IOException("No parent for $file")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(encode(set))
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Cannot write $file")
        }
    }
}
