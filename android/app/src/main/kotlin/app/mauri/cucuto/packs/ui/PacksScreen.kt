package app.mauri.cucuto.packs.ui

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Create
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mauri.cucuto.R
import app.mauri.cucuto.core.pack.ImportSource
import app.mauri.cucuto.packs.ImportUi
import app.mauri.cucuto.packs.LibraryPack
import app.mauri.cucuto.packs.PacksMessage
import app.mauri.cucuto.packs.PacksUiState
import app.mauri.cucuto.packs.PacksViewModel
import app.mauri.cucuto.ui.components.ParticleBurst
import app.mauri.cucuto.ui.feedback.Feedback
import app.mauri.cucuto.ui.feedback.FeedbackBeat
import app.mauri.cucuto.ui.feedback.LocalFeedback
import coil3.compose.AsyncImage

/** Free demo packs on the website, until Discover (#36) lists repositories in the app. */
const val PACKS_WEBSITE_URL = "https://cucuto.mauri.app/packs/"

/**
 * MIME types for the system file picker. Packs are zips (ADR 0007); `application/octet-stream` covers providers that
 * do not know the type (old `.soundpack` files, some cloud drives). The importer checks the contents either way.
 */
private val PickerTypes = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")

/**
 * The Packs screen (#27): installed packs with their characters (tap one to hear it), import from a file (system
 * picker, "Open with" and the share sheet), the import review, plain-words errors and removal with a confirmation.
 * Discover (#36) is one tap away in the top bar and at the end of the list.
 */
@Composable
fun PacksScreen(
    onBack: () -> Unit,
    onDiscover: () -> Unit,
    modifier: Modifier = Modifier,
    onCreate: (sourcePackId: String?) -> Unit = {},
    viewModel: PacksViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.applicationContext.contentResolver
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.importFrom(ImportSource.of(resolver, uri))
        }
    val openPicker = { picker.launch(PickerTypes) }

    PacksContent(
        state = state,
        onBack = onBack,
        onDiscover = onDiscover,
        onImport = openPicker,
        onRemove = viewModel::requestRemove,
        onConsumeMessage = viewModel::messageShown,
        modifier = modifier,
        onCreate = onCreate,
    )

    val import = state.import
    if (import != ImportUi.Idle) {
        ImportSheet(
            import = import,
            onSelect = viewModel::setSelected,
            onInstall = viewModel::install,
            onPickAnother = {
                viewModel.closeImport()
                openPicker()
            },
            onClose = viewModel::closeImport,
        )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PacksContent(
    state: PacksUiState,
    onBack: () -> Unit,
    onDiscover: () -> Unit,
    onImport: () -> Unit,
    onRemove: (LibraryPack) -> Unit,
    onConsumeMessage: () -> Unit,
    modifier: Modifier = Modifier,
    onCreate: (sourcePackId: String?) -> Unit = {},
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
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    val packs = state.packs
    val expandedFab by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    val currentOnConsumeMessage by rememberUpdatedState(onConsumeMessage)

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
        snackbar.showSnackbar(text)
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.screen_packs)) },
                subtitle = packs?.takeIf { it.isNotEmpty() }?.let { { Text(summaryText(it)) } },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { onCreate(null) }) {
                        Icon(Icons.Rounded.Create, contentDescription = stringResource(R.string.packs_create))
                    }
                    IconButton(onClick = onDiscover) {
                        Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.packs_discover))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            if (!packs.isNullOrEmpty()) {
                ExtendedFloatingActionButton(
                    text = { Text(stringResource(R.string.packs_import)) },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
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
                                LoadingIndicator(Modifier.size(56.dp))
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
                                onOpen = { detailId = pack.id },
                                modifier = Modifier.animateItem(),
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

    val detail = packs?.firstOrNull { it.id == detailId }
    if (detail != null) {
        PackDetailSheet(
            pack = detail,
            player = player,
            onRemove = {
                detailId = null
                onRemove(detail)
            },
            onDismiss = { detailId = null },
            onDuplicate = {
                detailId = null
                onCreate(detail.id)
            },
        )
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
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = packSubtitle(pack),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
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
            Text(stringResource(R.string.packs_more_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.packs_more_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

private fun installedText(
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
