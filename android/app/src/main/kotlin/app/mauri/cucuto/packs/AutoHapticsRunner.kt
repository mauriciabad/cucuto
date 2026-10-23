package app.mauri.cucuto.packs

import android.content.Context
import android.os.VibratorManager
import android.util.Log
import app.mauri.cucuto.core.data.db.InstalledPackEntity
import app.mauri.cucuto.core.data.repository.InstalledPackRepository
import app.mauri.cucuto.core.data.routing.PackManifestCatalog
import app.mauri.cucuto.core.haptics.HapticCapabilities
import app.mauri.cucuto.core.haptics.auto.AutoHapticFile
import app.mauri.cucuto.core.haptics.auto.AutoHapticSet
import app.mauri.cucuto.core.haptics.auto.AutoHaptics
import app.mauri.cucuto.core.haptics.hapticCapabilities
import app.mauri.cucuto.core.pack.PackAnalyzer
import app.mauri.cucuto.core.pack.PackStore
import app.mauri.cucuto.packspec.PackManifest
import app.mauri.cucuto.packspec.SpecJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Auto haptics ("haptics follow the voice") for installed packs (ADR 0012 and 0020):
 *
 * - [packAnalyzer] is what the importer runs on a pack before installing it, so its envelopes are committed with it.
 * - [start] redoes, once per process and in the background, the analyses that are missing or stale: a new analyser
 *   version, a restore on a phone with a smaller control point budget, a pack installed by an older app, or an
 *   analysis that failed at import. The routing table picks the new envelopes up through [PackManifestCatalog].
 *
 * Every clip is analysed whatever its character's pack haptic, because a per-contact `auto` override can apply to
 * any character. Only the first 2 s of each clip are decoded, so long clips cost no more than short ones.
 */
@Singleton
class AutoHapticsRunner
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val installedPacks: InstalledPackRepository,
        private val catalog: PackManifestCatalog,
    ) {
        private val autoHaptics: AutoHaptics by lazy {
            val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            AutoHaptics(vibrator?.hapticCapabilities() ?: HapticCapabilities())
        }

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val started = AtomicBoolean(false)

        val packAnalyzer: PackAnalyzer =
            PackAnalyzer { packDir, manifest, progress ->
                autoHaptics.analyzePack(packDir, manifest.clipFiles(), progress)
            }

        fun start() {
            if (!started.compareAndSet(false, true)) return
            scope.launch {
                refreshStaleAutoHaptics(
                    packs = installedPacks.observeAll().first(),
                    isStale = autoHaptics::isStale,
                    analyze = { dir, clips -> autoHaptics.analyzePack(dir, clips) },
                    changed = catalog::invalidate,
                )
            }
        }

        /** A pack version was installed in [path]: drop anything cached about an earlier install in the same folder. */
        fun installed(path: String) = catalog.invalidate(path)
    }

/** Every notification clip of the pack, once, in manifest order (characters may share a file). */
internal fun PackManifest.clipFiles(): List<String> =
    characters
        .flatMap { character -> character.clips.map { it.file } }
        .distinct()

/**
 * Analyses again each of [packs] whose stored auto haptics [isStale], then reports its path to [changed]. One pack
 * failing (deleted files, a broken manifest) never stops the others. Returns the paths that were analysed.
 */
internal suspend fun refreshStaleAutoHaptics(
    packs: List<InstalledPackEntity>,
    isStale: (AutoHapticSet?) -> Boolean,
    analyze: suspend (packDir: File, clips: List<String>) -> Unit,
    changed: (path: String) -> Unit,
): List<String> {
    val refreshed = mutableListOf<String>()
    for (pack in packs) {
        try {
            val dir = File(pack.path)
            val manifest =
                withContext(Dispatchers.IO) {
                    if (!isStale(AutoHapticFile.read(dir))) return@withContext null
                    SpecJson.decodeFromString<PackManifest>(File(dir, PackStore.MANIFEST).readText())
                } ?: continue
            analyze(dir, manifest.clipFiles())
            changed(pack.path)
            refreshed += pack.path
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("cucuto", "Auto haptics for ${pack.packId} ${pack.version} failed", e)
        }
    }
    return refreshed
}
