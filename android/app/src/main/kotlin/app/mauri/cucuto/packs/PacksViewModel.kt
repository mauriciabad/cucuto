package app.mauri.cucuto.packs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.cucuto.core.pack.FailureReason
import app.mauri.cucuto.core.pack.ImportProgress
import app.mauri.cucuto.core.pack.ImportSource
import app.mauri.cucuto.core.pack.PackSelection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

    /** Reading, checking and unpacking the file. [progress] is null until the first step reports. */
    data class Working(
        val progress: ImportProgress?,
    ) : ImportUi

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
        private val importState = MutableStateFlow<ImportUi>(ImportUi.Idle)
        private val removal = MutableStateFlow<RemovalUi?>(null)
        private val message = MutableStateFlow<PacksMessage?>(null)
        private var importJob: Job? = null

        val state: StateFlow<PacksUiState> =
            combine(
                catalog.installed.map<List<LibraryPack>, List<LibraryPack>?> { it }.onStart { emit(null) },
                importState,
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
        fun importFrom(source: ImportSource) {
            val current = importState.value
            if (current is ImportUi.Review && current.installing) return
            importJob?.cancel()
            if (current is ImportUi.Review) catalog.discard(current.review)
            importState.value = ImportUi.Working(progress = null)
            importJob =
                viewModelScope.launch {
                    try {
                        catalog.prepare(source).collect { step ->
                            importState.value =
                                when (step) {
                                    is ImportStep.Working -> ImportUi.Working(step.progress)
                                    is ImportStep.Ready -> ImportUi.Review(step.review, defaultSelection(step.review))
                                    is ImportStep.Invalid -> ImportUi.Problem(problemFor(step.report, step.detected))
                                    is ImportStep.Failed -> ImportUi.Problem(problemFor(step.reason, step.message))
                                    is ImportStep.Installed -> ImportUi.Idle
                                }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        importState.value = ImportUi.Problem(unexpected(e))
                    }
                }
        }

        /** Selects or deselects one pack of a bundle. Selecting a pack that replaces an installed copy confirms it. */
        fun setSelected(
            packId: String,
            selected: Boolean,
        ) {
            importState.update { state ->
                if (state !is ImportUi.Review || state.installing) return@update state
                val selection =
                    state.selection.with(packId, selected).let {
                        if (selected) it.confirm(packId) else it.copy(confirmed = it.confirmed - packId)
                    }
                state.copy(selection = selection)
            }
        }

        /** Installs the chosen packs. For a single pack, the install button itself is the confirmation. */
        fun install() {
            val state = importState.value as? ImportUi.Review ?: return
            if (!state.canInstall) return
            val selection =
                if (state.review.isBundle) {
                    state.selection
                } else {
                    state.review.packs
                        .map { it.id }
                        .toSet()
                        .let { PackSelection(it, it) }
                }
            importState.value = state.copy(installing = true)
            importJob =
                viewModelScope.launch {
                    try {
                        catalog.install(state.review, selection).collect { step ->
                            when (step) {
                                is ImportStep.Working -> {
                                    importState.update {
                                        (it as? ImportUi.Review)?.copy(progress = step.progress) ?: it
                                    }
                                }

                                is ImportStep.Installed -> {
                                    importState.value = ImportUi.Idle
                                    message.value = installedMessage(state.review, step)
                                }

                                is ImportStep.Failed -> {
                                    importState.value = ImportUi.Problem(problemFor(step.reason, step.message))
                                }

                                is ImportStep.Invalid -> {
                                    importState.value = ImportUi.Problem(problemFor(step.report, step.detected))
                                }

                                is ImportStep.Ready -> {
                                    // install() never asks for another review.
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        importState.value = ImportUi.Problem(unexpected(e))
                    }
                }
        }

        /** Closes the import: stops reading, drops a prepared import or dismisses a problem. Never stops an install. */
        fun closeImport() {
            when (val state = importState.value) {
                is ImportUi.Review -> {
                    if (state.installing) return
                    catalog.discard(state.review)
                }

                is ImportUi.Working -> {
                    importJob?.cancel()
                }

                ImportUi.Idle, is ImportUi.Problem -> {
                    // Nothing is running.
                }
            }
            importState.value = ImportUi.Idle
        }

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

        override fun onCleared() {
            (importState.value as? ImportUi.Review)?.takeUnless { it.installing }?.let { catalog.discard(it.review) }
        }

        private fun installedMessage(
            review: ImportReview,
            step: ImportStep.Installed,
        ): PacksMessage.Installed {
            val installed = step.packs.map { it.id }.toSet()
            val packs = review.packs.filter { it.id in installed }
            return PacksMessage.Installed(
                names = packs.map { it.name },
                characters = packs.sumOf { it.characters.size },
                skipped = step.skipped.size,
            )
        }

        private fun unexpected(e: Exception) = problemFor(FailureReason.UNEXPECTED, e.toString())

        companion object {
            private const val STOP_TIMEOUT_MS = 5_000L

            /**
             * Every pack is selected (docs/pack-format.md#bundle), except bundle packs that would replace the same or
             * a newer installed version: the user selects those on purpose, which is the confirmation. A single pack
             * is always selected; its install button says what it does and confirms it.
             */
            fun defaultSelection(review: ImportReview): PackSelection =
                PackSelection(
                    review.packs
                        .filter { !review.isBundle || !it.candidate.action.needsConfirmation }
                        .map { it.id }
                        .toSet(),
                )
        }
    }
