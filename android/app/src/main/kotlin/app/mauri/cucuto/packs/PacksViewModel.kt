package app.mauri.cucuto.packs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.cucuto.core.pack.ImportProgress
import app.mauri.cucuto.core.pack.ImportSource
import app.mauri.cucuto.core.pack.PackSelection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Everything the Packs screen shows. */
data class PacksUiState(
    /** Null while the installed packs load. */
    val packs: List<LibraryPack>? = null,
    val import: ImportUi = ImportUi.Idle,
    val removal: RemovalUi? = null,
    val message: PacksMessage? = null,
)

/** Where an import is. */
sealed interface ImportUi {
    data object Idle : ImportUi

    /**
     * Downloading ([download], repositories only), then reading, checking and unpacking the file. [progress] is null
     * until the importer's first step reports.
     */
    data class Working(
        val progress: ImportProgress?,
        val download: Download? = null,
    ) : ImportUi

    /** A download in progress: [bytes] of [total] (null when unknown) of the pack called [name]. */
    data class Download(
        val name: String,
        val bytes: Long,
        val total: Long?,
    )

    /**
     * The file is valid: the user picks packs (bundles) and confirms. [selection] has the chosen packs; choosing a
     * pack that needs a confirmation (same version or a downgrade) confirms it. While [installing], [progress] runs.
     */
    data class Review(
        val review: ImportReview,
        val selection: PackSelection,
        val installing: Boolean = false,
        val progress: ImportProgress? = null,
    ) : ImportUi {
        /** True when the install button does something. */
        val canInstall: Boolean get() = !installing && selection.selected.isNotEmpty()
    }

    /** The file was rejected or could not be read. */
    data class Problem(
        val problem: ImportProblem,
    ) : ImportUi
}

/** The pack the user asked to remove, waiting for the confirmation. */
data class RemovalUi(
    val pack: LibraryPack,
    /** Clear the assignments of the contacts that use the pack (otherwise they keep them). */
    val clearAssignments: Boolean = false,
    val removing: Boolean = false,
)

/** One-off results for a snackbar and the matching feedback. Cleared with [PacksViewModel.messageShown]. */
sealed interface PacksMessage {
    /** [names] were installed; [characters] became assignable; [skipped] packs still needed a confirmation. */
    data class Installed(
        val names: List<String>,
        val characters: Int,
        val skipped: Int,
    ) : PacksMessage

    data class Removed(
        val name: String,
    ) : PacksMessage

    data class RemoveFailed(
        val name: String,
    ) : PacksMessage
}

@HiltViewModel
class PacksViewModel
    @Inject
    constructor(
        private val catalog: PackCatalog,
        private val incoming: IncomingImports,
    ) : ViewModel() {
        private val imports = ImportController(catalog, viewModelScope)
        private val removal = MutableStateFlow<RemovalUi?>(null)
        private val message = MutableStateFlow<PacksMessage?>(null)

        val state: StateFlow<PacksUiState> =
            combine(
                catalog.installed.map<List<LibraryPack>, List<LibraryPack>?> { it }.onStart { emit(null) },
                imports.state,
                removal,
                message,
            ) { packs, import, removal, message -> PacksUiState(packs, import, removal, message) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PacksUiState())

        init {
            // Files from "Open with" and the share sheet.
            viewModelScope.launch {
                incoming.pending.filterNotNull().collect { incoming.take()?.let(::importFrom) }
            }
        }

        /** Starts importing [source] (the file picker, "Open with" or the share sheet), replacing any open import. */
        fun importFrom(source: ImportSource) = imports.start { catalog.prepare(source) }

        /** Selects or deselects one pack of a bundle. Selecting a pack that replaces an installed copy confirms it. */
        fun setSelected(
            packId: String,
            selected: Boolean,
        ) = imports.setSelected(packId, selected)

        /** Installs the chosen packs. For a single pack, the install button itself is the confirmation. */
        fun install() = imports.install { message.value = it }

        /** Closes the import: stops reading, drops a prepared import or dismisses a problem. Never stops an install. */
        fun closeImport() = imports.close()

        /** Asks for confirmation before removing [pack]. */
        fun requestRemove(pack: LibraryPack) {
            removal.value = RemovalUi(pack)
        }

        fun setClearAssignments(clear: Boolean) {
            removal.update { it?.takeUnless { r -> r.removing }?.copy(clearAssignments = clear) ?: it }
        }

        fun cancelRemove() {
            removal.update { if (it?.removing == true) it else null }
        }

        /** Removes the pack the user confirmed. */
        fun confirmRemove() {
            val request = removal.value ?: return
            if (request.removing) return
            removal.value = request.copy(removing = true)
            viewModelScope.launch {
                val ok =
                    try {
                        catalog.remove(request.pack.id, request.clearAssignments)
                        true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        false
                    }
                removal.value = null
                message.value =
                    if (ok) PacksMessage.Removed(request.pack.name) else PacksMessage.RemoveFailed(request.pack.name)
            }
        }

        fun messageShown() {
            message.value = null
        }

        override fun onCleared() = imports.clear()

        companion object {
            private const val STOP_TIMEOUT_MS = 5_000L

            /** See [ImportController.defaultSelection]. */
            fun defaultSelection(review: ImportReview): PackSelection = ImportController.defaultSelection(review)
        }
    }
