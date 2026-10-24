package app.mauri.cucuto.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.cucuto.core.pack.ImportSource
import app.mauri.cucuto.core.repo.IndexCache
import app.mauri.cucuto.core.repo.LinkResult
import app.mauri.cucuto.core.repo.RepoError
import app.mauri.cucuto.core.repo.RepoResult
import app.mauri.cucuto.core.repo.RepositoryAccess
import app.mauri.cucuto.core.repo.RepositoryClient
import app.mauri.cucuto.core.repo.RepositoryCredentials
import app.mauri.cucuto.core.repo.RepositoryLink
import app.mauri.cucuto.core.repo.RepositoryLinks
import app.mauri.cucuto.core.repo.RepositorySnapshot
import app.mauri.cucuto.packs.ImportController
import app.mauri.cucuto.packs.ImportUi
import app.mauri.cucuto.packs.PackCatalog
import app.mauri.cucuto.packs.PackOrigin
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Discover (#36, ADR 0029): browse the saved pack repositories, preview characters, install through the import
 * review, and add repositories by URL or "Add to app" link, with credentials when needed.
 *
 * Network rule (minimum intrusion): requests happen only while this screen is open: the indexes when it opens or the
 * user pulls to refresh, previews when they are shown or tapped, a download when the user installs, and an index when
 * the user adds a repository. Nothing runs in the background and nothing is reported anywhere. Home's "Get the demo
 * packs" (#179, ADR 0045) opens this screen and starts the demo bundle's install, so that tap is the user's install.
 */
@HiltViewModel
class DiscoverViewModel
    @Inject
    constructor(
        private val store: DiscoverStore,
        private val client: RepositoryClient,
        private val indexCache: IndexCache,
        private val previews: PreviewStore,
        private val downloader: PackDownloader,
        private val catalog: PackCatalog,
        private val links: IncomingRepoLinks,
        private val demoRequests: DemoPacksRequests,
        /** The vibration of sample previews (ADR 0069), for the screen's character badges. */
        val sampleHaptics: SampleHaptics,
    ) : ViewModel() {
        /** Blocking cache calls (removing a repository's index copy). Replaced in tests. */
        internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

        private val imports = ImportController(catalog, viewModelScope)
        private val loads = MutableStateFlow<Map<Long, RepoLoad>>(emptyMap())
        private val filters = MutableStateFlow(Filters())
        private val form = MutableStateFlow<RepositoryForm?>(null)
        private val _showForm = MutableStateFlow(false)

        /**
         * True from opening the repository form (a tap, a sign-in, an "Add to app" link) until its screen is open
         * ([formShown]): an event kept as state, like [showImportReview].
         */
        val showForm: StateFlow<Boolean> = _showForm.asStateFlow()
        private val message = MutableStateFlow<DiscoverMessage?>(null)
        private val demo = MutableStateFlow<DemoPacksUi?>(null)
        private val accesses = ConcurrentHashMap<Long, RepositoryAccess>()
        private val refreshJobs = mutableMapOf<Long, Job>()
        private var formJob: Job? = null
        private var demoJob: Job? = null

        private data class Filters(
            val query: String = "",
            val tag: String? = null,
            val focus: String? = null,
        )

        private val content =
            combine(
                store.repositories.map<List<SavedRepository>, List<SavedRepository>?> { it }.onStart { emit(null) },
                store.installed.onStart { emit(emptyList()) },
                loads,
                filters,
            ) { repositories, installed, loads, filters ->
                DiscoverUiState(
                    sections =
                        repositories?.let { sections(it, loads, installed, filters.query, filters.tag, filters.focus) },
                    repositories = repositories.orEmpty(),
                    query = filters.query,
                    tag = filters.tag,
                    focus = filters.focus,
                    tags = repositories?.let { tagsOf(it, loads) }.orEmpty(),
                )
            }

        val state: StateFlow<DiscoverUiState> =
            combine(content, imports.state, form, message, demo) { content, import, form, message, demo ->
                content.copy(import = import, form = form, message = message, demo = demo)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DiscoverUiState())

        init {
            viewModelScope.launch {
                store.ensureDefault()
                refreshAll()
                // Home's "Get the demo packs": right away, or when tapped again while Discover stays open. After
                // refreshAll, so the default repository's fetch is already under way and is not started twice.
                demoRequests.pending.filter { it }.collect { if (demoRequests.take()) getDemoPacks() }
            }
            // "Add to app" links from websites and QR codes.
            viewModelScope.launch {
                links.pending.filterNotNull().collect { links.take()?.let(::openLink) }
            }
        }

        /** Fetches every enabled repository again (ETag: unchanged indexes cost one small request each). */
        fun refresh() {
            viewModelScope.launch { refreshAll() }
        }

        private suspend fun refreshAll() {
            store.repositories
                .first()
                .filter { it.enabled }
                .forEach(::refresh)
        }

        /** Fetches [repository] again. */
        fun retry(repository: SavedRepository) = refresh(repository)

        fun setQuery(query: String) = filters.update { it.copy(query = query) }

        /** Filters by [tag]; the same tag again clears the filter. */
        fun toggleTag(tag: String) = filters.update { it.copy(tag = if (it.tag == tag) null else tag) }

        fun clearFilters() {
            filters.value = Filters()
            if (demo.value is DemoPacksUi.Failed) demo.value = null
        }

        // region Adding repositories and signing in

        /** Opens the empty "add a repository" form. */
        fun openAdd() {
            formJob?.cancel()
            form.value = RepositoryForm()
            _showForm.value = true
        }

        /** Opens the sign-in form of [repository] (after a 401 or 403, or from the saved repositories). */
        fun openSignIn(repository: SavedRepository) {
            formJob?.cancel()
            form.value = RepositoryForm(repositoryId = repository.id, input = repository.url)
            _showForm.value = true
        }

        /** The repository form's screen is open. */
        fun formShown() {
            _showForm.value = false
        }

        fun updateForm(change: (RepositoryForm) -> RepositoryForm) {
            form.update { current ->
                if (current == null || current.checking) return@update current
                val next = change(current)
                // A fixed URL stays fixed while signing in to a saved repository.
                val input = if (current.repositoryId != null) current.input else next.input
                next.copy(input = input, error = null)
            }
        }

        fun closeForm() {
            formJob?.cancel()
            form.value = null
            _showForm.value = false
        }

        /**
         * Checks and saves the form: a direct pack link starts its download; a repository index is fetched (with the
         * credentials, if any) and saved only when that works, so a typo or a wrong password never ends up in the list.
         */
        fun submitForm() {
            val current = form.value ?: return
            if (current.checking) return
            val credentials =
                try {
                    current.credentials()
                } catch (e: IllegalArgumentException) {
                    form.value = current.copy(error = FormError.InvalidCredentials)
                    return
                }
            when (val parsed = current.parsed ?: return) {
                is LinkResult.Error -> {
                    form.value = current.copy(error = FormError.Link(parsed.error))
                }

                is LinkResult.Ok -> {
                    when (val link = parsed.link) {
                        is RepositoryLink.DirectPack -> downloadDirect(link, credentials)
                        is RepositoryLink.Index -> checkIndex(current, link, credentials)
                    }
                }
            }
        }

        private fun openLink(link: String) {
            when (val parsed = RepositoryLinks.parseDeepLink(link)) {
                is LinkResult.Ok -> {
                    formJob?.cancel()
                    form.value = RepositoryForm(input = parsed.link.url.toString(), fromLink = true)
                    _showForm.value = true
                }

                is LinkResult.Error -> {
                    message.value = DiscoverMessage.BadLink
                }
            }
        }

        private fun downloadDirect(
            link: RepositoryLink.DirectPack,
            credentials: RepositoryCredentials?,
        ) {
            form.value = null
            val name = link.fileName
            val access = credentials?.let { RepositoryAccess(link.url, it) }
            imports.start { downloader.download(name, link.url, null, access, PackOrigin.Url(link.url.toString())) }
        }

        private fun checkIndex(
            current: RepositoryForm,
            link: RepositoryLink.Index,
            credentials: RepositoryCredentials?,
        ) {
            val url = link.url.toString()
            form.value = current.copy(checking = true, error = null)
            formJob =
                viewModelScope.launch {
                    val saved = store.repositories.first()
                    val editing = current.repositoryId?.let { id -> saved.firstOrNull { it.id == id } }
                    if (editing == null) {
                        saved.firstOrNull { it.url == url }?.let { existing ->
                            // Already there: turn it back on instead of failing.
                            if (!existing.enabled) enable(existing)
                            message.value = DiscoverMessage.AlreadyAdded(existing.name ?: hostOf(existing.url))
                            form.value = null
                            return@launch
                        }
                    }
                    val access = RepositoryAccess(link.url, credentials)
                    when (val result = client.fetchIndex(access, indexCache)) {
                        is RepoResult.Failure -> {
                            form.update { it?.copy(checking = false, error = FormError.Fetch(result.error)) }
                        }

                        is RepoResult.Success -> {
                            val snapshot = result.value.snapshot
                            val name = snapshot.index.name
                            val id =
                                if (editing != null) {
                                    store.setCredentials(editing.id, credentials)
                                    store.setName(editing.id, name)
                                    editing.id
                                } else {
                                    store.add(url, name, credentials)
                                        ?: saved.firstOrNull { it.url == url }?.id
                                }
                            if (id != null) {
                                accesses[id] = access
                                refreshJobs.remove(id)?.cancel()
                                loads.update { it + (id to RepoLoad(snapshot = snapshot)) }
                            }
                            message.value =
                                if (editing != null) DiscoverMessage.SignedIn(name) else DiscoverMessage.Added(name)
                            form.value = null
                        }
                    }
                }
        }

        // endregion

        // region Managing repositories

        fun setEnabled(
            repository: SavedRepository,
            enabled: Boolean,
        ) {
            viewModelScope.launch {
                if (enabled) enable(repository) else store.setEnabled(repository.id, false)
            }
        }

        /** Turns [repository] on and fetches it unless its index is already here. */
        private suspend fun enable(repository: SavedRepository) {
            store.setEnabled(repository.id, true)
            if (loads.value[repository.id]?.snapshot == null) refresh(repository.copy(enabled = true))
        }

        /** Removes [repository] (never the default one), its credentials and its cached index. Packs stay installed. */
        fun remove(repository: SavedRepository) {
            if (repository.isDefault) return
            viewModelScope.launch {
                refreshJobs.remove(repository.id)?.cancel()
                store.remove(repository.id)
                accesses.remove(repository.id)
                loads.update { it - repository.id }
                withContext(ioDispatcher) { runCatching { indexCache.remove(repository.url) } }
                message.value = DiscoverMessage.Removed(repository.name ?: hostOf(repository.url))
            }
        }

        // endregion

        // region Installing

        /** An import started and its review screen should open ([ImportController.showReview]). */
        val showImportReview: StateFlow<Boolean> = imports.showReview

        /** The import review screen is open. */
        fun importReviewShown() = imports.reviewShown()

        /** Downloads [card]'s file and opens the import review (the same screen as importing a file). */
        fun install(card: PackCard) {
            viewModelScope.launch {
                val repository = store.repositories.first().firstOrNull { it.id == card.repositoryId } ?: return@launch
                install(card, repository)
            }
        }

        private suspend fun install(
            card: PackCard,
            repository: SavedRepository,
        ): Boolean {
            val access = access(repository) ?: return false
            imports.start {
                downloader.download(
                    name = card.name,
                    url = card.entry.downloadUrl,
                    expected = card.entry.expected,
                    access = access,
                    origin = PackOrigin.Repository(repository.url),
                )
            }
            return true
        }

        /**
         * The demo packs (#179): shows only the demo bundle of the default repository, fetches its index if this
         * visit has no fresh copy, and starts the bundle's download into the usual review, where the user still
         * confirms. Offline, or without the bundle in the index, it says why and offers to pick the file instead.
         * Also the failure card's "Try again".
         */
        fun getDemoPacks() {
            demoJob?.cancel()
            demoJob = viewModelScope.launch { findDemoPacks() }
        }

        private suspend fun findDemoPacks() {
            filters.value = Filters(focus = DEMO_BUNDLE_ID)
            demo.value = DemoPacksUi.Finding
            store.ensureDefault()
            var repository = store.repositories.first().firstOrNull { it.isDefault } ?: return run { demo.value = null }
            if (!repository.enabled) {
                // The user asked for this repository's packs, so a default repository they turned off comes back on.
                store.setEnabled(repository.id, true)
                repository = repository.copy(enabled = true)
            }
            val current = loads.value[repository.id]
            val fresh = current?.snapshot != null && current.error == null
            if (current?.loading != true && !fresh) refresh(repository)
            val load = loads.first { it[repository.id]?.loading == false }.getValue(repository.id)
            load.error?.let { error ->
                // Offline, or the server is down: a stale copy would only fail at the download.
                demo.value = DemoPacksUi.Failed(DemoProblem.Unreachable(error))
                return
            }
            val snapshot = load.snapshot ?: return run { demo.value = null }
            val card = cards(repository.id, snapshot, store.installed.first()).firstOrNull { it.id == DEMO_BUNDLE_ID }
            demo.value =
                when {
                    card == null -> DemoPacksUi.Failed(DemoProblem.NotListed)

                    // The card says "Needs a newer Cucuto"; there is nothing to start.
                    !card.entry.supported -> null

                    install(card, repository) -> DemoPacksUi.Importing

                    else -> DemoPacksUi.Failed(DemoProblem.Unreachable(RepoError.InsecureUrl(hostOf(repository.url))))
                }
        }

        /** The fallback when the demo packs cannot be downloaded: a pack file the user picked (from Downloads). */
        fun importFile(source: ImportSource) {
            demoJob?.cancel()
            demo.value = DemoPacksUi.Importing
            imports.start { catalog.prepare(source) }
        }

        fun setSelected(
            packId: String,
            selected: Boolean,
        ) = imports.setSelected(packId, selected)

        /** Installs what the review shows (the review's install button). */
        fun confirmInstall() =
            imports.install {
                message.value = DiscoverMessage.Installed(it)
                if (demo.value == DemoPacksUi.Importing) demo.value = null
            }

        fun closeImport() {
            imports.close()
            if (demo.value == DemoPacksUi.Importing && imports.state.value == ImportUi.Idle) demo.value = null
        }

        // endregion

        /**
         * The preview at [url] as a local file, fetched with the credentials of repository [repositoryId] if it lives
         * on the repository's origin.
         */
        suspend fun preview(
            url: HttpUrl,
            repositoryId: Long,
        ): File? = previews.file(url, accesses[repositoryId])

        fun messageShown() {
            message.value = null
        }

        override fun onCleared() = imports.clear()

        private fun refresh(repository: SavedRepository) {
            refreshJobs.remove(repository.id)?.cancel()
            loads.update { it + (repository.id to (it[repository.id] ?: RepoLoad()).copy(loading = true)) }
            refreshJobs[repository.id] =
                viewModelScope.launch {
                    val access = access(repository)
                    if (access == null) {
                        loads.update {
                            it +
                                (repository.id to RepoLoad(error = RepoError.InsecureUrl(hostOf(repository.url))))
                        }
                        return@launch
                    }
                    when (val result = client.fetchIndex(access, indexCache)) {
                        is RepoResult.Success -> {
                            val snapshot = result.value.snapshot
                            loads.update { it + (repository.id to RepoLoad(snapshot = snapshot)) }
                            store.setName(repository.id, snapshot.index.name)
                        }

                        is RepoResult.Failure -> {
                            // Offline or failing: keep showing the last copy that worked, marked as such.
                            val last = loads.value[repository.id]?.snapshot ?: cached(access)
                            loads.update { it + (repository.id to RepoLoad(snapshot = last, error = result.error)) }
                        }
                    }
                }
        }

        private suspend fun cached(access: RepositoryAccess): RepositorySnapshot? =
            client.cachedIndex(access, indexCache)

        /** The repository's URL with its stored credentials, remembered for previews and downloads. */
        private suspend fun access(repository: SavedRepository): RepositoryAccess? {
            accesses[repository.id]?.let { return it }
            val url = repository.url.toHttpUrlOrNull()?.takeIf { it.isHttps } ?: return null
            return RepositoryAccess(url, store.credentials(repository.url)).also { accesses[repository.id] = it }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
