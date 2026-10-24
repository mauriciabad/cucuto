package app.mauri.cucuto.packs

import app.mauri.cucuto.core.pack.FailureReason
import app.mauri.cucuto.core.pack.PackSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The import, start to end, as one state machine shared by the Packs and Discover screens: a file (or a download)
 * is prepared, reviewed and installed, or ends in a problem in plain words. [state] drives [ImportSheet]. Lives in
 * a view model's [scope]; call [clear] from `onCleared`.
 *
 * [ImportSheet]: app.mauri.cucuto.packs.ui.ImportSheet
 */
class ImportController(
    private val catalog: PackCatalog,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ImportUi>(ImportUi.Idle)
    val state: StateFlow<ImportUi> = _state.asStateFlow()

    private var job: Job? = null

    /**
     * Starts an import from [steps] (usually [PackCatalog.prepare], preceded by download steps for repositories),
     * replacing any open import. Does nothing (and never calls [steps]) while packs are being installed.
     */
    fun start(steps: () -> Flow<ImportStep>) {
        val current = _state.value
        if (current is ImportUi.Review && current.installing) return
        job?.cancel()
        if (current is ImportUi.Review) catalog.discard(current.review)
        _state.value = ImportUi.Working(progress = null)
        val flow = steps()
        job =
            scope.launch {
                try {
                    flow.collect { step ->
                        _state.value =
                            when (step) {
                                is ImportStep.Downloading -> {
                                    ImportUi.Working(null, ImportUi.Download(step.name, step.bytes, step.total))
                                }

                                is ImportStep.Working -> {
                                    ImportUi.Working(step.progress)
                                }

                                is ImportStep.Ready -> {
                                    ImportUi.Review(step.review, defaultSelection(step.review))
                                }

                                is ImportStep.Invalid -> {
                                    ImportUi.Problem(problemFor(step.report, step.detected))
                                }

                                is ImportStep.Failed -> {
                                    ImportUi.Problem(problemFor(step.reason, step.message))
                                }

                                is ImportStep.DownloadFailed -> {
                                    ImportUi.Problem(problemFor(step.error))
                                }

                                is ImportStep.Installed -> {
                                    ImportUi.Idle
                                }
                            }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.value = ImportUi.Problem(unexpected(e))
                }
            }
    }

    /** Selects or deselects one pack of a bundle. Selecting a pack that replaces an installed copy confirms it. */
    fun setSelected(
        packId: String,
        selected: Boolean,
    ) {
        _state.update { state ->
            if (state !is ImportUi.Review || state.installing) return@update state
            val selection =
                state.selection.with(packId, selected).let {
                    if (selected) it.confirm(packId) else it.copy(confirmed = it.confirmed - packId)
                }
            state.copy(selection = selection)
        }
    }

    /**
     * Installs the chosen packs and calls [onInstalled] when they are in. For a single pack, the install button
     * itself is the confirmation.
     */
    fun install(onInstalled: (PacksMessage.Installed) -> Unit) {
        val state = _state.value as? ImportUi.Review ?: return
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
        _state.value = state.copy(installing = true)
        job =
            scope.launch {
                try {
                    catalog.install(state.review, selection).collect { step ->
                        when (step) {
                            is ImportStep.Working -> {
                                _state.update { (it as? ImportUi.Review)?.copy(progress = step.progress) ?: it }
                            }

                            is ImportStep.Installed -> {
                                _state.value = ImportUi.Idle
                                onInstalled(installedMessage(state.review, step))
                            }

                            is ImportStep.Failed -> {
                                _state.value = ImportUi.Problem(problemFor(step.reason, step.message))
                            }

                            is ImportStep.Invalid -> {
                                _state.value = ImportUi.Problem(problemFor(step.report, step.detected))
                            }

                            is ImportStep.Ready, is ImportStep.Downloading, is ImportStep.DownloadFailed -> {
                                // install() never downloads or asks for another review.
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.value = ImportUi.Problem(unexpected(e))
                }
            }
    }

    /** Closes the import: stops reading, drops a prepared import or dismisses a problem. Never stops an install. */
    fun close() {
        when (val state = _state.value) {
            is ImportUi.Review -> {
                if (state.installing) return
                catalog.discard(state.review)
            }

            is ImportUi.Working -> {
                job?.cancel()
            }

            ImportUi.Idle, is ImportUi.Problem -> {
                // Nothing is running.
            }
        }
        _state.value = ImportUi.Idle
    }

    /** Drops a prepared import that was never installed. For the owner's `onCleared`. */
    fun clear() {
        (_state.value as? ImportUi.Review)?.takeUnless { it.installing }?.let { catalog.discard(it.review) }
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
        /**
         * Every pack is selected (docs/pack-format.md#bundle), except bundle packs that would replace the same or a
         * newer installed version: the user selects those on purpose, which is the confirmation. A single pack is
         * always selected; its install button says what it does and confirms it.
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
