package app.mauri.cucuto.packs.ui

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Create
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mauri.cucuto.R
import app.mauri.cucuto.core.pack.ImportSource
import app.mauri.cucuto.navigation.CucutoDestination
import app.mauri.cucuto.navigation.parentViewModel
import app.mauri.cucuto.packs.LibraryPack
import app.mauri.cucuto.packs.PacksMessage
import app.mauri.cucuto.packs.PacksUiState
import app.mauri.cucuto.packs.PacksViewModel
import app.mauri.cucuto.ui.components.CucutoTopBar
import app.mauri.cucuto.ui.components.ParticleBurst
import app.mauri.cucuto.ui.components.adaptiveMaxLines
import app.mauri.cucuto.ui.feedback.Feedback
import app.mauri.cucuto.ui.feedback.FeedbackBeat
import app.mauri.cucuto.ui.feedback.LocalFeedback
import app.mauri.cucuto.ui.motion.animateItemUnlessReduced
import coil3.compose.AsyncImage

/** Free demo packs on the website, until Discover (#36) lists repositories in the app. */
const val PACKS_WEBSITE_URL = "https://cucuto.mauri.app/packs/"

/**
 * MIME types for the system file picker. Packs are zips (ADR 0007); `application/octet-stream` covers providers that
 * do not know the type (old `.soundpack` files, some cloud drives). The importer checks the contents either way.
 */
internal val PackPickerTypes = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")

/**
 * The Packs screen (#27): installed packs with their characters (tap one to hear it), import from a file (system
 * picker, "Open with" and the share sheet) and removal with a confirmation. The import review and a pack's detail are
 * screens of their own on this tab ([PacksImportReview], [PackDetailScreen], ADR 0055).
 * It is a top-level tab (ADR 0027): no back arrow. Discover (#36) is linked at the end of the list, and after an
 * install the snackbar offers to go give the new characters to chats on Home.
 */
@Composable
fun PacksScreen(
    onNavigate: (CucutoDestination) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PacksViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val showReview by viewModel.showImportReview.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.applicationContext.contentResolver
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.importFrom(ImportSource.of(resolver, uri))
        }
    val openPicker = { picker.launch(PackPickerTypes) }

    PacksContent(
        state = state,
        onDiscover = { onNavigate(CucutoDestination.Discover) },
        onAssignVoices = { onNavigate(CucutoDestination.Home) },
        onImport = openPicker,
        onRemove = viewModel::requestRemove,
        onConsumeMessage = viewModel::messageShown,
        modifier = modifier,
        onCreate = { source -> onNavigate(CucutoDestination.Creator(source)) },
        onOpenPack = { id -> onNavigate(CucutoDestination.PackDetail(id)) },
    )

    // Every import (the picker, "Open with", the share sheet, Home's demo file) is reviewed on its own screen.
    val navigate by rememberUpdatedState(onNavigate)
    LaunchedEffect(showReview) {
        if (showReview) {
            navigate(CucutoDestination.ImportReview(CucutoDestination.ImportFrom.Packs))
            viewModel.importReviewShown()
        }
    }
    state.removal?.let { removal ->
        RemovePackDialog(
            removal = removal,
            onClearAssignmentsChange = viewModel::setClearAssignments,
            onConfirm = viewModel::confirmRemove,
            onDismiss = viewModel::cancelRemove,
        )
    }
}

/**
 * The import review of the Packs tab ([CucutoDestination.ImportReview] from Packs), on the Packs screen's view model
 * ([parentViewModel]). "Choose another file" opens the picker again and replaces the import.
 */
@Composable
fun PacksImportReview(
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: PacksViewModel = parentViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.showImportReview.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.applicationContext.contentResolver
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.importFrom(ImportSource.of(resolver, uri))
        }
    ImportReviewRoute(
        import = state.import,
        reviewPending = pending,
        onShow = viewModel::importReviewShown,
        onSelect = viewModel::setSelected,
        onInstall = viewModel::install,
        onPickAnother = { picker.launch(PackPickerTypes) },
        onClose = viewModel::closeImport,
        onBack = onBack,
        onDone = onDone,
    )
}

/** Stateless Packs screen, for previews and the screenshot tests. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PacksContent(
    state: PacksUiState,
    onDiscover: () -> Unit,
    onAssignVoices: () -> Unit,
    onImport: () -> Unit,
    onRemove: (LibraryPack) -> Unit,
    onConsumeMessage: () -> Unit,
    modifier: Modifier = Modifier,
    onCreate: (sourcePackId: String?) -> Unit = {},
    onOpenPack: (packId: String) -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val player = rememberClipPlayer()
    val feedback = LocalFeedback.current
    val resources = LocalResources.current
    val uriHandler = LocalUriHandler.current
    val openWebsite = { uriHandler.openUri(PACKS_WEBSITE_URL) }
    var celebration by remember { mutableStateOf<FeedbackBeat?>(null) }
    val packs = state.packs
    val expandedFab by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    val currentOnConsumeMessage by rememberUpdatedState(onConsumeMessage)
    val currentOnAssignVoices by rememberUpdatedState(onAssignVoices)

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        val text =
            when (message) {
                is PacksMessage.Installed -> {
                    celebration = feedback.fire(Feedback.Success)
                    installedText(resources, message)
                }

                is PacksMessage.Removed -> {
                    feedback.fire(Feedback.Confirm)
                    resources.getString(R.string.packs_removed, message.name)
                }

                is PacksMessage.RemoveFailed -> {
                    feedback.fire(Feedback.Error)
                    resources.getString(R.string.packs_remove_failed, message.name)
                }
            }
        currentOnConsumeMessage()
        // After an install, the next step of the journey is on Home: giving the new characters to chats.
        val action =
            resources.getString(R.string.packs_assign_voices).takeIf {
                message is PacksMessage.Installed && message.names.isNotEmpty()
            }
        val result = snackbar.showSnackbar(text, actionLabel = action, duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) currentOnAssignVoices()
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            CucutoTopBar(
                title = stringResource(R.string.tab_packs),
                subtitle = packs?.takeIf { it.isNotEmpty() }?.let { summaryText(it) },
                actions = {
                    IconButton(onClick = { onCreate(null) }) {
                        Icon(Icons.Rounded.Create, contentDescription = stringResource(R.string.packs_create))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            if (!packs.isNullOrEmpty()) {
                val importLabel = stringResource(R.string.packs_import)
                ExtendedFloatingActionButton(
                    text = { Text(importLabel) },
                    // Material hides the text from TalkBack: the icon carries the label, expanded or not.
                    icon = { Icon(Icons.Rounded.Add, contentDescription = importLabel) },
                    onClick = onImport,
                    expanded = expandedFab,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().consumeWindowInsets(innerPadding),
                contentPadding =
                    PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = innerPadding.calculateTopPadding() + 8.dp,
                        bottom = innerPadding.calculateBottomPadding() + 96.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    packs == null -> {
                        item(key = "loading") {
                            Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                                val loading = stringResource(R.string.a11y_loading)
                                LoadingIndicator(Modifier.size(56.dp).semantics { contentDescription = loading })
                            }
                        }
                    }

                    packs.isEmpty() -> {
                        item(key = "empty") {
                            EmptyPacks(onImport = onImport, onWebsite = openWebsite, onCreate = { onCreate(null) })
                        }
                    }

                    else -> {
                        items(packs, key = { it.id }) { pack ->
                            PackCard(
                                pack = pack,
                                player = player,
                                onOpen = { onOpenPack(pack.id) },
                                modifier = animateItemUnlessReduced(),
                            )
                        }
                        item(key = "more") { FindMorePacks(onDiscover = onDiscover, onWebsite = openWebsite) }
                    }
                }
            }
            ParticleBurst(
                beat = celebration,
                modifier = Modifier.align(Alignment.Center).size(360.dp),
            )
        }
    }
}

/** One installed pack: cover, name, author, and its characters in a row you can tap to hear. */
@Composable
private fun PackCard(
    pack: LibraryPack,
    player: ClipPlayer,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onOpen,
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
                PackCover(pack, Modifier.size(56.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        pack.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = adaptiveMaxLines(2),
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = packSubtitle(pack),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = adaptiveMaxLines(2),
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (pack.filesMissing) {
                Text(
                    text = stringResource(R.string.packs_files_missing),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(pack.characters, key = { it.id }) { character ->
                        CharacterBadge(character, playerKey = "${pack.id}/${character.id}", player = player)
                    }
                }
            }
        }
    }
}

/** The pack's cover, or its initial on a cookie when it has none. */
@Composable
fun PackCover(
    pack: LibraryPack,
    modifier: Modifier = Modifier,
) {
    if (pack.cover != null) {
        AsyncImage(
            model = pack.cover,
            contentDescription = null,
            modifier = modifier.clip(RoundedCornerShape(16.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        Surface(
            modifier = modifier,
            shape = MaterialShapes.Cookie4Sided.toShape(),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = pack.name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    // Decorative: the pack's name is right next to it.
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
    }
}

/** No packs yet: the one thing to do is import one, and the website has free demo packs. */
@Composable
private fun EmptyPacks(
    onImport: () -> Unit,
    onWebsite: () -> Unit,
    onCreate: () -> Unit,
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
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(48.dp))
            }
        }
        Text(
            stringResource(R.string.packs_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.packs_empty_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onImport, shapes = ButtonDefaults.shapes()) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Text(stringResource(R.string.packs_import_file), Modifier.padding(start = ButtonDefaults.IconSpacing))
        }
        OutlinedButton(onClick = onWebsite, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.packs_get_free))
        }
        OutlinedButton(onClick = onCreate, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.packs_create))
        }
    }
}

/** The slot for Discover (#36) and the website until repositories are browsable in the app. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FindMorePacks(
    onDiscover: () -> Unit,
    onWebsite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.packs_more_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.packs_more_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Wraps with large fonts and longer translations instead of pushing the second button off the card.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onDiscover, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.packs_discover))
                }
                OutlinedButton(onClick = onWebsite, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.packs_website))
                }
            }
        }
    }
}

@Composable
private fun summaryText(packs: List<LibraryPack>): String {
    val characters = packs.sumOf { it.characters.size }
    return stringResource(
        R.string.packs_summary,
        pluralStringResource(R.plurals.packs_count, packs.size, packs.size),
        pluralStringResource(R.plurals.packs_characters, characters, characters),
    )
}

@Composable
private fun packSubtitle(pack: LibraryPack): String {
    val characters = pluralStringResource(R.plurals.packs_characters, pack.characters.size, pack.characters.size)
    val users = pack.contacts.size
    return if (users == 0) {
        stringResource(R.string.packs_card_subtitle, characters, pack.version)
    } else {
        stringResource(
            R.string.packs_card_subtitle_used,
            characters,
            pack.version,
            pluralStringResource(R.plurals.packs_used_by, users, users),
        )
    }
}

@Composable
internal fun sizeText(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

internal fun installedText(
    resources: android.content.res.Resources,
    message: PacksMessage.Installed,
): String {
    val base =
        when (message.names.size) {
            0 -> {
                resources.getString(R.string.packs_installed_none)
            }

            1 -> {
                resources.getQuantityString(
                    R.plurals.packs_installed_one,
                    message.characters,
                    message.names.single(),
                    message.characters,
                )
            }

            else -> {
                resources.getQuantityString(R.plurals.packs_installed_many, message.names.size, message.names.size)
            }
        }
    if (message.skipped == 0) return base
    return base + " " + resources.getQuantityString(R.plurals.packs_installed_skipped, message.skipped, message.skipped)
}
