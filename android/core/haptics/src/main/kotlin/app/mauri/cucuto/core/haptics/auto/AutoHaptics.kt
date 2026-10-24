package app.mauri.cucuto.core.haptics.auto

import app.mauri.cucuto.core.haptics.HapticCapabilities
import app.mauri.cucuto.core.routing.Haptic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Auto haptics for the importer: decode each clip, analyse it ([AutoHapticAnalyzer]) and store the envelopes next to
 * the pack ([AutoHapticFile]). Meant to run once per clip at import time, never on the notification path. Everything
 * runs on [dispatcher], off the main thread.
 *
 * @param capabilities The device's vibrator, for the control point budget ([AutoHapticAnalyzer.Options.forDevice]).
 * @param dispatcher Where clips are decoded and analysed. IO by default: the platform decoders mostly wait for the
 *   codec process (ADR 0061).
 * @param parallelism How many clips [analyzePack] decodes at once.
 */
class AutoHaptics(
    capabilities: HapticCapabilities,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val decoder: PcmDecoder = ClipPcmDecoder(),
    private val parallelism: Int = DEFAULT_PARALLELISM,
) {
    init {
        require(parallelism >= 1) { "parallelism must be at least 1" }
    }

    val options: AutoHapticAnalyzer.Options = AutoHapticAnalyzer.Options.forDevice(capabilities)

    /** The envelope of the clip in [file], or null when it is silent. Throws [IOException] if it cannot be decoded. */
    suspend fun analyze(file: File): Haptic.Custom? = withContext(dispatcher) { analyzeBlocking(file) }

    private fun analyzeBlocking(file: File): Haptic.Custom? =
        analyzePcm(decoder.decode(file, AutoHapticAnalyzer.MAX_DURATION_MS))

    private fun analyzePcm(pcm: MonoPcm): Haptic.Custom? =
        AutoHapticAnalyzer.analyze(pcm.samples, pcm.sampleRate, options)

    /**
     * Analyses [clipPaths] (paths from `pack.json`, relative to [packDir]) and writes the result to [packDir]
     * ([AutoHapticFile.write]), in the order of [clipPaths]. Clips that cannot be decoded or analysed are left out, so
     * a later run can retry them. Only the first [AutoHapticAnalyzer.MAX_DURATION_MS] of each clip is decoded, however
     * long it is. Up to [parallelism] clips are decoded at once, each worker with one [PcmDecoder.session] for all its
     * clips (ADR 0077); the result is the same as one at a time (#267).
     *
     * @param progress Called from the calling coroutine with the number of clips done so far and the total: 0 first,
     *   then once each time a clip is done, up to the total.
     */
    suspend fun analyzePack(
        packDir: File,
        clipPaths: Collection<String>,
        progress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): AutoHapticSet =
        withContext(dispatcher) {
            val paths = clipPaths.distinct()
            progress(0, paths.size)
            val results = arrayOfNulls<ClipResult>(paths.size)
            coroutineScope {
                val next = AtomicInteger()
                val finished = Channel<Unit>(Channel.UNLIMITED)
                repeat(minOf(parallelism, paths.size)) {
                    launch {
                        // One decoder per worker, kept between its clips (ADR 0077); closed even on cancellation.
                        decoder.session().use { session ->
                            while (true) {
                                val i = next.getAndIncrement()
                                if (i >= paths.size) break
                                ensureActive()
                                results[i] =
                                    try {
                                        val pcm =
                                            session.decode(
                                                File(packDir, paths[i]),
                                                AutoHapticAnalyzer.MAX_DURATION_MS,
                                            )
                                        ClipResult(analyzePcm(pcm))
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        // Skipped (IOException from the decoder, or an analyser bug): auto keeps
                                        // WhatsApp's vibration.
                                        null
                                    }
                                finished.send(Unit)
                            }
                        }
                    }
                }
                // Progress comes from this coroutine, as before: the importer's flow sends it on (ADR 0020).
                for (done in 1..paths.size) {
                    finished.receive()
                    progress(done, paths.size)
                }
            }
            val clips = LinkedHashMap<String, Haptic.Custom?>(paths.size * 2)
            paths.forEachIndexed { i, path -> results[i]?.let { clips[path] = it.haptic } }
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

    /** An analysed clip; [haptic] is null when it is silent. */
    private class ClipResult(
        val haptic: Haptic.Custom?,
    )

    companion object {
        /**
         * One clip per CPU core, at least 2 and at most 8: the same bound as the importer's test-decodes
         * (`PackImporter.DEFAULT_DECODE_PARALLELISM`, ADR 0061), which this module cannot see. The work is mostly
         * waiting for the codec process, so 8 helps even on 4 cores.
         */
        val DEFAULT_PARALLELISM: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
    }
}
