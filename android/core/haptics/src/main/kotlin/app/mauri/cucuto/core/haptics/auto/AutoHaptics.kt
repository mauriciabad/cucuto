package app.mauri.cucuto.core.haptics.auto

import app.mauri.cucuto.core.haptics.HapticCapabilities
import app.mauri.cucuto.core.routing.Haptic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Auto haptics for the importer: decode each clip, analyse it ([AutoHapticAnalyzer]) and store the envelopes next to
 * the pack ([AutoHapticFile]). Meant to run once per clip at import time (tens of milliseconds each), never on the
 * notification path. Everything runs on [dispatcher], off the main thread.
 *
 * @param capabilities The device's vibrator, for the control point budget ([AutoHapticAnalyzer.Options.forDevice]).
 */
class AutoHaptics(
    capabilities: HapticCapabilities,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val decoder: ClipPcmDecoder = ClipPcmDecoder(),
) {
    val options: AutoHapticAnalyzer.Options = AutoHapticAnalyzer.Options.forDevice(capabilities)

    /** The envelope of the clip in [file], or null when it is silent. Throws [IOException] if it cannot be decoded. */
    suspend fun analyze(file: File): Haptic.Custom? =
        withContext(dispatcher) {
            val pcm = decoder.decode(file, AutoHapticAnalyzer.MAX_DURATION_MS)
            AutoHapticAnalyzer.analyze(pcm.samples, pcm.sampleRate, options)
        }

    /**
     * Analyses [clipPaths] (paths from `pack.json`, relative to [packDir]) and writes the result to [packDir]
     * ([AutoHapticFile.write]). Clips that cannot be decoded or analysed are left out, so a later run can retry
     * them. Only the first [AutoHapticAnalyzer.MAX_DURATION_MS] of each clip is decoded, however long it is.
     *
     * @param progress Called with the number of clips done so far and the total, before each clip and at the end.
     */
    suspend fun analyzePack(
        packDir: File,
        clipPaths: Collection<String>,
        progress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): AutoHapticSet =
        withContext(dispatcher) {
            val paths = clipPaths.distinct()
            val clips = LinkedHashMap<String, Haptic.Custom?>(paths.size * 2)
            paths.forEachIndexed { i, path ->
                ensureActive()
                progress(i, paths.size)
                try {
                    clips[path] = analyze(File(packDir, path))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Skipped (IOException from the decoder, or an analyser bug): auto keeps WhatsApp's vibration.
                }
            }
            progress(paths.size, paths.size)
            val set =
                AutoHapticSet(AutoHapticAnalyzer.VERSION, options.maxControlPoints, options.minControlPointMs, clips)
            AutoHapticFile.write(packDir, set)
            set
        }

    /** True when [set] was made by an older analyser or with a larger point budget than this device takes. */
    fun isStale(set: AutoHapticSet?): Boolean =
        set == null ||
            set.analyzerVersion != AutoHapticAnalyzer.VERSION ||
            set.maxControlPoints > options.maxControlPoints ||
            set.minControlPointMs < options.minControlPointMs
}
