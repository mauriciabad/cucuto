package app.mauri.cucuto.ui.discover

import android.content.Intent
import app.mauri.cucuto.core.repo.RepositoryLinks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Add to app" links (`cucuto://repo/add?url=…`) from websites and QR codes. The activity [offer]s each link
 * ([repoLinkOf]); Discover [take]s it and opens the add sheet with the host and a warning. Nothing is fetched until
 * the user confirms: a link only fills in the form.
 */
@Singleton
class IncomingRepoLinks
    @Inject
    constructor() {
        private val _pending = MutableStateFlow<String?>(null)

        /** The link waiting for Discover, if any. */
        val pending: StateFlow<String?> = _pending.asStateFlow()

        /** Queues [link], replacing one that was not taken yet. */
        fun offer(link: String) {
            _pending.value = link
        }

        /** Hands the waiting link over to exactly one caller. */
        fun take(): String? = _pending.getAndUpdate { null }
    }

/** The `cucuto://repo/add` link of a VIEW [intent], or null. The URL inside is checked later, by Discover. */
fun repoLinkOf(intent: Intent?): String? {
    if (intent?.action != Intent.ACTION_VIEW) return null
    val data = intent.data ?: return null
    if (!data.scheme.equals(RepositoryLinks.DEEP_LINK_SCHEME, ignoreCase = true)) return null
    if (!data.host.equals(RepositoryLinks.DEEP_LINK_HOST, ignoreCase = true)) return null
    return intent.dataString
}
