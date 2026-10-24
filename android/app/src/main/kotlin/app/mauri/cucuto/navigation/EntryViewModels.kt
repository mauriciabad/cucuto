@file:Suppress("ktlint:compose:compositionlocal-allowlist")

package app.mauri.cucuto.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.savedstate.compose.LocalSavedStateRegistryOwner

/**
 * View models of the shell's entries (ADR 0055). Every entry gets its own `ViewModelStore`, kept while the entry is
 * in its back stack (like `rememberViewModelStoreNavEntryDecorator`). A screen that is a step of its parent's work,
 * such as the import review over Packs or the backup options over Settings, also sees its parent's store: it declares
 * the parent with [childOf] and reads the parent's view models with [parentViewModel], so the flow keeps one state
 * and the parent's tests keep covering it.
 */
private const val PARENT_KEY = "app.mauri.cucuto.parentKey"

/** Set by the shell: the parent's content key, prefixed with the tab like every content key. */
internal const val PARENT_CONTENT_KEY = "app.mauri.cucuto.parentContentKey"

/** Entry metadata for a screen that works on [parent]'s view models. [parent] must sit below it in the same tab. */
fun childOf(parent: NavKey): Map<String, Any> = mapOf(PARENT_KEY to parent)

/** The parent declared with [childOf], if any. */
internal fun parentOf(metadata: Map<String, Any>): NavKey? = metadata[PARENT_KEY] as? NavKey

/**
 * The view model store of the entry's parent ([childOf]), or null for an entry without one. Provided by the shell's
 * entry decorator next to `LocalViewModelStoreOwner`, which is a CompositionLocal for the same reason.
 */
val LocalParentViewModelStoreOwner = staticCompositionLocalOf<ViewModelStoreOwner?> { null }

/**
 * The [VM] of the parent entry ([childOf]), the same instance the parent screen uses. Outside the shell (previews,
 * tests that host one screen) it falls back to the screen's own store.
 */
@Composable
inline fun <reified VM : ViewModel> parentViewModel(): VM =
    hiltViewModel(
        viewModelStoreOwner =
            LocalParentViewModelStoreOwner.current
                ?: checkNotNull(LocalViewModelStoreOwner.current) { "No ViewModelStoreOwner" },
    )

/**
 * Runs [onPop] once, when this entry leaves its back stack for good: back, the back arrow, a tab reselected to its
 * root, or the screen closing itself. Not on configuration changes or tab switches. Child screens use it to close
 * their part of the parent's state (an open import, a repository form), so the parent does not reopen them.
 */
@Composable
fun OnEntryPopped(onPop: () -> Unit) {
    val observer = viewModel(key = PopObserver.KEY) { PopObserver() }
    SideEffect { observer.onPopped = onPop }
}

/** Lives in the entry's own store, which the shell clears when the entry is popped. */
class PopObserver : ViewModel() {
    var onPopped: () -> Unit = {}

    override fun onCleared() = onPopped()

    companion object {
        const val KEY = "app.mauri.cucuto.popObserver"
    }
}

/**
 * One store per entry, cleared when the entry is popped, plus [LocalParentViewModelStoreOwner] for entries with a
 * parent. The parent's store is looked up by its content key, so it exists even when the parent was never composed
 * in this process (a child restored on top after process death).
 */
@Composable
internal fun rememberEntryViewModelStoreDecorator(): NavEntryDecorator<NavKey> {
    val provider = rememberViewModelStoreProvider()
    return remember(provider) {
        NavEntryDecorator(onPop = { contentKey -> provider.clearKey(contentKey) }) { entry ->
            val registry = LocalSavedStateRegistryOwner.current
            val owner = rememberViewModelStoreOwner(entry.contentKey, provider, registry)
            val parentKey = entry.metadata[PARENT_CONTENT_KEY]
            val parent = if (parentKey != null) rememberViewModelStoreOwner(parentKey, provider, registry) else null
            CompositionLocalProvider(
                LocalViewModelStoreOwner provides owner,
                LocalParentViewModelStoreOwner provides parent,
            ) {
                entry.Content()
            }
        }
    }
}
