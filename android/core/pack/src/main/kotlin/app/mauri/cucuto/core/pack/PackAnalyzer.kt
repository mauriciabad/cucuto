package app.mauri.cucuto.core.pack

import app.mauri.cucuto.packspec.PackManifest
import java.io.File

/**
 * Extra work on a verified pack right before it is installed, in its staging folder: whatever it writes there
 * (under `.cucuto/`, a name pack paths can never use) moves into place together with the version, in the same
 * rename. The app plugs in auto haptics here (`:core:haptics`, ADR 0012), so this module needs no audio analysis.
 *
 * [PackImporter.install] runs it on the packs it is about to install, before taking its install lock, and reports
 * [ImportProgress.Analyzing]. It never fails an import: any exception except cancellation is ignored, and the app
 * can redo the work later.
 */
fun interface PackAnalyzer {
    /**
     * Analyses the pack in [packDir] (staged, already validated and test-decoded) described by [manifest].
     *
     * @param progress Reports how many of `total` steps are done.
     */
    suspend fun analyze(
        packDir: File,
        manifest: PackManifest,
        progress: suspend (done: Int, total: Int) -> Unit,
    )
}
