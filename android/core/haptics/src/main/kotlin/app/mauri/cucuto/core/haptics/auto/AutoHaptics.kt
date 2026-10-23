package app.mauri.cucuto.core.haptics.auto

import app.mauri.cucuto.core.haptics.HapticCapabilities
import app.mauri.cucuto.core.routing.Haptic
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
     * ([AutoHapticFile.write]). Clips that cannot be decoded are left out, so a later run can retry them.
     */
    suspend fun analyzePack(
        packDir: File,
        clipPaths: Collection<String>,
    ): AutoHapticSet =
        withContext(dispatcher) {
            val clips = LinkedHashMap<String, Haptic.Custom?>(clipPaths.size * 2)
            for (path in clipPaths.distinct()) {
                ensureActive()
                try {
                    clips[path] = analyze(File(packDir, path))
                } catch (_: IOException) {
                    // Skipped: the clip keeps WhatsApp's vibration in auto mode.
                }
            }
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
