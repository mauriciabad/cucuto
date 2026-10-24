package app.mauri.cucuto.ui.discover

import android.content.res.Resources
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mauri.cucuto.R
import app.mauri.cucuto.core.data.db.RepositoryAuthType
import app.mauri.cucuto.core.pack.ImportSource
import app.mauri.cucuto.navigation.CucutoDestination
import app.mauri.cucuto.navigation.OnEntryPopped
import app.mauri.cucuto.navigation.parentViewModel
import app.mauri.cucuto.packs.plainWords
import app.mauri.cucuto.packs.ui.ClipPlayer
import app.mauri.cucuto.packs.ui.ImportReviewRoute
import app.mauri.cucuto.packs.ui.installedText
import app.mauri.cucuto.packs.ui.rememberClipPlayer
import app.mauri.cucuto.packs.ui.sizeText
import app.mauri.cucuto.ui.components.ParticleBurst
import app.mauri.cucuto.ui.components.adaptiveMaxLines
import app.mauri.cucuto.ui.feedback.Feedback
import app.mauri.cucuto.ui.feedback.FeedbackBeat
import app.mauri.cucuto.ui.feedback.LocalFeedback
import app.mauri.cucuto.ui.motion.animateItemUnlessReduced
import okhttp3.HttpUrl
import java.io.File

/** What the screen does when the user acts. Every function is a [DiscoverViewModel] call in the app. */
data class DiscoverActions(
    val onRefresh: () -> Unit = {},
    val onRetry: (SavedRepository) -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onTag: (String) -> Unit = {},
    val onClearFilters: () -> Unit = {},
    val onAdd: () -> Unit = {},
    val onSignIn: (SavedRepository) -> Unit = {},
    val onSetEnabled: (SavedRepository, Boolean) -> Unit = { _, _ -> },
    val onRemove: (SavedRepository) -> Unit = {},
    val onInstall: (PackCard) -> Unit = {},
    val onConsumeMessage: () -> Unit = {},
    /** The demo packs' "Try again" (#179). */
    val onGetDemoPacks: () -> Unit = {},
    /** The demo packs' fallback: the file picker in Downloads. */
    val onPickFile: () -> Unit = {},
)

/**
 * The Discover screen (#36, ADR 0029): pack repositories with their packs as cards (tap a character to hear it, as on
 * the Packs screen), search and tag filters, install and update through the import review, and repositories added by
 * URL or "Add to app" link, with optional sign-in. Offline, the last copy of each index stays browsable. The import
 * review, the repository form and the saved repositories are screens of their own on this tab (ADR 0055), opened
 * through [onNavigate].
 *
 * @param onBack Shows a back arrow when Discover is not a top-level destination.
 */
@Composable
fun DiscoverScreen(
    modifier: Modifier = Modifier,
    onNavigate: (CucutoDestination) -> Unit = {},
    onBack: (() -> Unit)? = null,
    viewModel: DiscoverViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val showReview by viewModel.showImportReview.collectAsStateWithLifecycle()
    val showForm by viewModel.showForm.collectAsStateWithLifecycle()
    val previews = remember(viewModel) { PreviewLoader(viewModel::preview) }
    val resolver = LocalContext.current.applicationContext.contentResolver
    val picker =
        rememberLauncherForActivityResult(OpenPackFromDownloads()) { uri ->
            if (uri != null) viewModel.importFile(ImportSource.of(resolver, uri))
        }
    val actions =
        remember(viewModel, picker) {
            DiscoverActions(
                onRefresh = viewModel::refresh,
                onRetry = viewModel::retry,
                onQuery = viewModel::setQuery,
                onTag = viewModel::toggleTag,
                onClearFilters = viewModel::clearFilters,
                onAdd = viewModel::openAdd,
                onSignIn = viewModel::openSignIn,
                onSetEnabled = viewModel::setEnabled,
                onRemove = viewModel::remove,
                onInstall = viewModel::install,
                onConsumeMessage = viewModel::messageShown,
                onGetDemoPacks = viewModel::getDemoPacks,
                onPickFile = { picker.launch(PackPickerTypes) },
            )
        }

    DiscoverContent(
        state = state,
        previews = previews,
        actions = actions,
        onOpenRepositories = { onNavigate(CucutoDestination.Repositories) },
        onBack = onBack,
        modifier = modifier,
    )

    // An install, the demo packs or a pack link started an import; a tap, a sign-in or a link opened the form.
    val navigate by rememberUpdatedState(onNavigate)
    LaunchedEffect(showReview) {
        if (showReview) {
            navigate(CucutoDestination.ImportReview(CucutoDestination.ImportFrom.Discover))
            viewModel.importReviewShown()
        }
    }
    OpenRepositoryForm(showForm, onNavigate, onOpen = viewModel::formShown)
}

/** Opens the repository form's screen when the view model asks for it ([DiscoverViewModel.showForm]). */
@Composable
private fun OpenRepositoryForm(
    showForm: Boolean,
    onNavigate: (CucutoDestination) -> Unit,
    onOpen: () -> Unit,
) {
    val navigate by rememberUpdatedState(onNavigate)
    val currentOnOpen by rememberUpdatedState(onOpen)
    LaunchedEffect(showForm) {
        if (showForm) {
            navigate(CucutoDestination.RepositoryForm)
            currentOnOpen()
        }
    }
}

/**
 * The import review of the Discover tab ([CucutoDestination.ImportReview] from Discover), on the Discover screen's view
 * model ([parentViewModel]). The demo packs fall back to the file picker; other Discover installs have no file to pick
 * again.
 */
@Composable
fun DiscoverImportReview(
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: DiscoverViewModel = parentViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.showImportReview.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.applicationContext.contentResolver
    val picker =
        rememberLauncherForActivityResult(OpenPackFromDownloads()) { uri ->
            if (uri != null) viewModel.importFile(ImportSource.of(resolver, uri))
        }
    ImportReviewRoute(
        import = state.import,
        reviewPending = pending,
        onShow = viewModel::importReviewShown,
        onSelect = viewModel::setSelected,
        onInstall = viewModel::confirmInstall,
        onPickAnother = { picker.launch(PackPickerTypes) }.takeIf { state.demo == DemoPacksUi.Importing },
        onClose = viewModel::closeImport,
        onBack = onBack,
        onDone = onDone,
    )
}

/**
 * Discover → add a repository, a pack link or sign in ([CucutoDestination.RepositoryForm]), on the Discover screen's
 * view model. Leaves by itself once the form is done (saved, signed in, or a pack link handed to the import) or gone
 * (after process death, the form and anything typed in it are not kept: passwords never go into saved state).
 */
@Composable
fun DiscoverRepositoryForm(
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: DiscoverViewModel = parentViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.showForm.collectAsStateWithLifecycle()
    LaunchedEffect(pending) { if (pending) viewModel.formShown() }
    OnEntryPopped(viewModel::closeForm)
    val form = state.form
    val currentOnDone by rememberUpdatedState(onDone)
    LaunchedEffect(form == null) { if (form == null) currentOnDone() }
    // While it slides out, the screen keeps showing the last form instead of going blank.
    var shown by remember { mutableStateOf(form) }
    if (form != null) shown = form
    shown?.let {
        RepositoryFormScreen(
            form = it,
            onChange = viewModel::updateForm,
            onSubmit = viewModel::submitForm,
            onBack = onBack,
        )
    }
}

/** Discover → Repositories ([CucutoDestination.Repositories]), on the Discover screen's view model. */
@Composable
fun DiscoverRepositories(
    onBack: () -> Unit,
    onNavigate: (CucutoDestination) -> Unit,
    viewModel: DiscoverViewModel = parentViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val showForm by viewModel.showForm.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val feedback = LocalFeedback.current
    val resources = LocalResources.current
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        message.feedback()?.let(feedback::fire)
        viewModel.messageShown()
        snackbar.showSnackbar(message.text(resources))
    }
    RepositoriesScreen(
        repositories = state.repositories,
        actions =
            DiscoverActions(
                onAdd = viewModel::openAdd,
                onSignIn = viewModel::openSignIn,
                onSetEnabled = viewModel::setEnabled,
                onRemove = viewModel::remove,
            ),
        onBack = onBack,
        snackbar = snackbar,
    )
    OpenRepositoryForm(showForm, onNavigate, onOpen = viewModel::formShown)
}

/** Loads a preview file (cover, sprite, sample) of a repository entry; null when it cannot be shown. */
fun interface PreviewLoader {
    suspend fun load(
        url: HttpUrl,
        repositoryId: Long,
    ): File?
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiscoverContent(
    state: DiscoverUiState,
    previews: PreviewLoader,
    actions: DiscoverActions,
    onOpenRepositories: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbar = remember { SnackbarHostState() }
    val player = rememberClipPlayer()
    val feedback = LocalFeedback.current
    val resources = LocalResources.current
    var celebration by remember { mutableStateOf<FeedbackBeat?>(null) }
    val currentOnConsume by rememberUpdatedState(actions.onConsumeMessage)

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        message.feedback()?.let { kind ->
            val beat = feedback.fire(kind)
            if (message is DiscoverMessage.Installed) celebration = beat
        }
        currentOnConsume()
        snackbar.showSnackbar(message.text(resources))
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.tab_discover), Modifier.semantics { heading() }) },
                subtitle = { Text(stringResource(R.string.discover_subtitle)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.navigate_back),
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = actions.onAdd) {
                        Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.discover_add))
                    }
                    IconButton(onClick = onOpenRepositories) {
                        Icon(
                            Icons.AutoMirrored.Rounded.List,
                            contentDescription = stringResource(R.string.discover_repositories),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = actions.onRefresh,
            modifier = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding()),
        ) {
            LazyColumn(
                state = rememberLazyListState(),
                modifier = Modifier.fillMaxSize().consumeWindowInsets(innerPadding),
                contentPadding =
                    PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = innerPadding.calculateBottomPadding() + 32.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "search") { SearchField(state.query, actions.onQuery) }
                if (state.focus == DEMO_BUNDLE_ID) {
                    item(key = "demo-focus") { DemoFocusChip(onClear = actions.onClearFilters) }
                }
                val demo = state.demo
                if (demo == DemoPacksUi.Finding || demo is DemoPacksUi.Failed) {
                    item(key = "demo") {
                        DemoPacksCard(demo, onRetry = actions.onGetDemoPacks, onPickFile = actions.onPickFile)
                    }
                }
                if (state.tags.isNotEmpty()) {
                    item(key = "tags") { TagFilters(state.tags, state.tag, actions.onTag) }
                }
                discoverItems(state, previews, player, actions)
                item(key = "add") { AddRepositoryCard(onAdd = actions.onAdd) }
            }
            ParticleBurst(beat = celebration, modifier = Modifier.align(Alignment.Center).size(360.dp))
        }
    }
}

/** The snackbar text of a message. */
internal fun DiscoverMessage.text(resources: Resources): String =
    when (this) {
        is DiscoverMessage.Installed -> installedText(resources, installed)
        is DiscoverMessage.Added -> resources.getString(R.string.discover_added, name)
        is DiscoverMessage.SignedIn -> resources.getString(R.string.discover_signed_in, name)
        is DiscoverMessage.Removed -> resources.getString(R.string.discover_removed, name)
        is DiscoverMessage.AlreadyAdded -> resources.getString(R.string.discover_already_added, name)
        DiscoverMessage.BadLink -> resources.getString(R.string.discover_bad_link)
    }

/** The feedback a message fires with it, if any. */
internal fun DiscoverMessage.feedback(): Feedback? =
    when (this) {
        is DiscoverMessage.Installed -> Feedback.Success
        is DiscoverMessage.Added, is DiscoverMessage.SignedIn, is DiscoverMessage.Removed -> Feedback.Confirm
        is DiscoverMessage.AlreadyAdded -> null
        DiscoverMessage.BadLink -> Feedback.Error
    }

private fun LazyListScope.discoverItems(
    state: DiscoverUiState,
    previews: PreviewLoader,
    player: ClipPlayer,
    actions: DiscoverActions,
) {
    val sections = state.sections
    when {
        sections == null -> {
            item(key = "loading") { CenteredLoading() }
        }

        sections.isEmpty() -> {
            item(key = "empty") { NoRepositories(onAdd = actions.onAdd) }
        }

        else -> {
            // The demo packs' card already says what went wrong (and the section's own problem would repeat it).
            val demoFailed = state.demo is DemoPacksUi.Failed
            val noMatches = !demoFailed && state.filtering && sections.all { it.loaded && it.cards.isEmpty() }
            if (noMatches) {
                item(key = "no-matches") { NoMatches(onClear = actions.onClearFilters) }
            }
            sections.forEach { section ->
                // While filtering, sections without matches step aside (their errors still show).
                if (state.filtering && section.loaded && section.cards.isEmpty() && section.error == null) {
                    return@forEach
                }
                val id = section.repository.id
                item(key = "repo-$id") { SectionHeader(section) }
                when {
                    !section.loaded && section.error == null -> {
                        item(key = "repo-$id-loading") { CenteredLoading() }
                    }

                    !section.loaded -> {
                        if (!(demoFailed && section.repository.isDefault)) {
                            item(key = "repo-$id-error") { SectionProblem(section, actions) }
                        }
                    }

                    else -> {
                        if (section.stale && !(demoFailed && section.repository.isDefault)) {
                            item(key = "repo-$id-stale") { SectionProblem(section, actions) }
                        }
                        if (section.totalCards == 0) {
                            item(key = "repo-$id-empty") { EmptyRepository() }
                        }
                        items(section.cards, key = { it.key }) { card ->
                            RemotePackCard(
                                card = card,
                                previews = previews,
                                player = player,
                                onInstall = { actions.onInstall(card) },
                                modifier = animateItemUnlessReduced(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(R.string.discover_search)) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) {
                    Icon(Icons.Rounded.Clear, contentDescription = stringResource(R.string.discover_search_clear))
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    )
}

@Composable
private fun TagFilters(
    tags: List<String>,
    selected: String?,
    onTag: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(tags, key = { it }) { tag ->
            FilterChip(
                selected = tag == selected,
                onClick = { onTag(tag) },
                label = { Text(tag) },
            )
        }
    }
}

/** The repository's name and host (always visible: users must see where packs come from). */
@Composable
private fun SectionHeader(
    section: RepositorySection,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 12.dp, start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                section.title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
                maxLines = adaptiveMaxLines(2),
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (section.repository.authType != RepositoryAuthType.NONE) {
                    Icon(
                        Icons.Rounded.Lock,
                        contentDescription = stringResource(R.string.discover_signed_in_icon),
                        modifier = Modifier.size(14.dp).padding(end = 2.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    section.host,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (section.loading && section.loaded) {
            val refreshing = stringResource(R.string.a11y_refreshing)
            LoadingIndicator(Modifier.size(32.dp).semantics { contentDescription = refreshing })
        }
    }
}

/** Why a repository could not be fetched, in plain words, with what to do about it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SectionProblem(
    section: RepositorySection,
    actions: DiscoverActions,
    modifier: Modifier = Modifier,
) {
    val error = section.error ?: return
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (section.stale) {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
            ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (section.stale) {
                        Text(stringResource(R.string.discover_stale), style = MaterialTheme.typography.titleSmall)
                    }
                    Text(stringResource(plainWords(error)), style = MaterialTheme.typography.bodyMedium)
                }
            }
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (section.needsSignIn) {
                    TextButton(onClick = { actions.onSignIn(section.repository) }) {
                        Text(stringResource(R.string.discover_sign_in))
                    }
                }
                TextButton(onClick = { actions.onRetry(section.repository) }, enabled = !section.loading) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Text(stringResource(R.string.discover_retry), Modifier.padding(start = ButtonDefaults.IconSpacing))
                }
            }
        }
    }
}

/** One repository entry: cover, name, author, characters to tap and hear, and what installing does. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemotePackCard(
    card: PackCard,
    previews: PreviewLoader,
    player: ClipPlayer,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entry = card.entry.entry
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                RemoteCover(card, previews, Modifier.size(56.dp))
                // Name and "by author · version · size" are one stop for TalkBack.
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                    Text(
                        card.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = adaptiveMaxLines(2),
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = cardSubtitle(card),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = adaptiveMaxLines(2),
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            entry.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = adaptiveMaxLines(3),
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (card.isBundle) {
                Text(
                    pluralStringResource(
                        R.plurals.discover_bundle_packs,
                        card.bundlePacks.size,
                        card.bundlePacks.size,
                        card.bundlePacks.joinToString(", "),
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (card.characters.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(card.characters, key = { it.key }) { character ->
                        RemoteCharacterBadge(character, card.repositoryId, previews, player)
                    }
                }
            }
            // The button wraps below the badge with large fonts and longer translations.
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StateBadge(card)
                Spacer(Modifier.weight(1f))
                InstallButton(card, onInstall)
            }
        }
    }
}

@Composable
private fun cardSubtitle(card: PackCard): String {
    val entry = card.entry.entry
    val size = sizeText(card.entry.expected.size)
    val version = stringResource(R.string.discover_version, entry.version)
    return listOfNotNull(entry.author?.let { stringResource(R.string.packs_by_author, it) }, version, size)
        .joinToString(" · ")
}

@Composable
private fun StateBadge(
    card: PackCard,
    modifier: Modifier = Modifier,
) {
    val (text, color) =
        when {
            !card.entry.supported -> {
                stringResource(R.string.discover_needs_newer) to MaterialTheme.colorScheme.errorContainer
            }

            card.state == InstallState.UPDATE -> {
                stringResource(R.string.discover_update_available) to MaterialTheme.colorScheme.primaryContainer
            }

            card.state == InstallState.INSTALLED -> {
                stringResource(R.string.discover_installed) to MaterialTheme.colorScheme.tertiaryContainer
            }

            else -> {
                return
            }
        }
    Surface(modifier = modifier, shape = RoundedCornerShape(8.dp), color = color) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (card.state == InstallState.INSTALLED && card.entry.supported) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun InstallButton(
    card: PackCard,
    onInstall: () -> Unit,
) {
    val enabled = card.entry.supported
    when (card.state) {
        InstallState.NEW -> {
            Button(onClick = onInstall, enabled = enabled, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.discover_install))
            }
        }

        InstallState.UPDATE -> {
            Button(onClick = onInstall, enabled = enabled, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.discover_update))
            }
        }

        InstallState.INSTALLED -> {
            OutlinedButton(onClick = onInstall, enabled = enabled, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.discover_install_again))
            }
        }
    }
}

@Composable
private fun CenteredLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        val loading = stringResource(R.string.a11y_loading)
        LoadingIndicator(Modifier.size(56.dp).semantics { contentDescription = loading })
    }
}

@Composable
private fun EmptyRepository(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.discover_repository_empty),
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun NoMatches(
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.discover_no_matches),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        OutlinedButton(onClick = onClear, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.discover_clear_filters))
        }
    }
}

/** Every repository is turned off (the default one cannot be removed, only turned off). */
@Composable
private fun NoRepositories(
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(
            shape = MaterialShapes.Cookie9Sided.toShape(),
            color = MaterialTheme.colorScheme.tertiaryContainer,
            modifier = Modifier.size(120.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(48.dp))
            }
        }
        Text(
            stringResource(R.string.discover_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.discover_empty_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onAdd, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.discover_add))
        }
    }
}

@Composable
private fun AddRepositoryCard(
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(top = 12.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.discover_add_card_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.discover_add_card_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                FilledTonalButton(onClick = onAdd, shapes = ButtonDefaults.shapes()) {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.discover_add))
                }
            }
        }
    }
}
