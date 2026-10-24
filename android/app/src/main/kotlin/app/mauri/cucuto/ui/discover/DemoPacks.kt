package app.mauri.cucuto.ui.discover

import app.mauri.cucuto.core.repo.RepoError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import javax.inject.Inject
import javax.inject.Singleton

/** The bundle with every demo pack in the default repository (`packs/README.md`, built by CI). */
const val DEMO_BUNDLE_ID = "app.mauri.demo.all"

/**
 * "Get the demo packs" on Home (#179, ADR 0045). Home [offer]s a request and opens Discover; Discover [take]s it and
 * installs [DEMO_BUNDLE_ID] from the default repository through the usual review. The request only says "the user
 * tapped": nothing touches the network until Discover handles it, and nothing is bundled in the APK.
 */
@Singleton
class DemoPacksRequests
    @Inject
    constructor() {
        private val _pending = MutableStateFlow(false)

        /** True while a request waits for Discover. */
        val pending: StateFlow<Boolean> = _pending.asStateFlow()

        fun offer() {
            _pending.value = true
        }

        /** Hands the waiting request to exactly one caller: true once per [offer]. */
        fun take(): Boolean = _pending.getAndUpdate { false }
    }

/** Where Discover is with the demo packs Home asked for. */
sealed interface DemoPacksUi {
    /** Fetching the default repository's index to find the bundle. */
    data object Finding : DemoPacksUi

    /** The import review has the bundle (download, review, install); a problem there offers to pick the file. */
    data object Importing : DemoPacksUi

    /** The bundle could not be found: say why, offer to try again or to pick the file from Downloads. */
    data class Failed(
        val problem: DemoProblem,
    ) : DemoPacksUi
}

/** Why the demo packs could not be started. */
sealed interface DemoProblem {
    /** The default repository could not be fetched (offline, server down, …). */
    data class Unreachable(
        val error: RepoError,
    ) : DemoProblem

    /** The default repository is there, but the demo bundle is not in it. */
    data object NotListed : DemoProblem
}
