package app.mauri.cucuto.home

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ripple
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mauri.cucuto.R
import app.mauri.cucuto.core.data.chats.Chat
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.chats.ChatVoice
import app.mauri.cucuto.core.data.voices.VoiceCharacter
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.ui.chat.chatSupportingText
import app.mauri.cucuto.ui.components.CuckooDoor
import app.mauri.cucuto.ui.components.VoiceAvatar
import app.mauri.cucuto.ui.feedback.Feedback
import app.mauri.cucuto.ui.feedback.LocalFeedback
import app.mauri.cucuto.ui.motion.SharedElement
import app.mauri.cucuto.ui.theme.CucutoTheme

/** Key of a chat's avatar in shared-element transitions (home list or strip → picker header). */
data class ChatAvatarKey(
    val chat: ChatKey,
)

/**
 * Home (brief, screen 2): every chat with its character's sprite as avatar, the "recent senders" strip of people who
 * messaged and have no voice yet, search, a pack filter and a FAB to assign. Tapping a chat with a voice opens its
 * contact detail ([onOpenDetail], #26); a chat without one goes straight to the character picker ([onOpenChat]).
 */
@Composable
fun HomeScreen(
    onOpenChat: (ChatKey) -> Unit,
    onOpenPacks: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenDetail: (ChatKey) -> Unit = onOpenChat,
    onOpenDiscover: () -> Unit = onOpenPacks,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HomeContent(
        state = state,
        onQueryChange = viewModel::setQuery,
        onTogglePack = viewModel::togglePackFilter,
        onDismissRecent = viewModel::dismissRecent,
        onOpenChat = onOpenChat,
        onOpenDetail = onOpenDetail,
        onOpenPacks = onOpenPacks,
        onBack = onBack,
        modifier = modifier,
        // Discover installs the demo bundle from the default repository, through the usual review (#179).
        onGetDemoPacks = {
            viewModel.requestDemoPacks()
            onOpenDiscover()
        },
    )
}

/** Stateless home screen, for previews and UI tests. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    state: HomeState,
    onQueryChange: (String) -> Unit,
    onTogglePack: (String) -> Unit,
    onDismissRecent: (ChatKey) -> Unit,
    onOpenChat: (ChatKey) -> Unit,
    onOpenPacks: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenDetail: (ChatKey) -> Unit = onOpenChat,
    onGetDemoPacks: () -> Unit = onOpenPacks,
) {
    var query by rememberSaveable { mutableStateOf(state.query) }
    val latestOnQueryChange by rememberUpdatedState(onQueryChange)
    LaunchedEffect(query) { latestOnQueryChange(query) }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    val feedback = LocalFeedback.current
    val open: (ChatKey) -> Unit = { key ->
        feedback.fire(Feedback.Tap)
        onOpenChat(key)
    }
    val openDetail: (ChatKey) -> Unit = { key ->
        feedback.fire(Feedback.Tap)
        onOpenDetail(key)
    }

    Scaffold(
        modifier = modifier,
        topBar = { HomeSearchBar(query, onQueryChange = { query = it }, onBack = onBack) },
        floatingActionButton = {
            if (state.totalChats > 0) {
                ExtendedFloatingActionButton(
                    onClick = {
                        feedback.fire(Feedback.Tap)
                        sheetOpen = true
                    },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.home_fab)) },
                    expanded = fabExpanded,
                )
            }
        },
    ) { innerPadding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                val label = stringResource(R.string.home_loading)
                LoadingIndicator(Modifier.semantics { contentDescription = label })
            }
            return@Scaffold
        }
        // A chat in the strip is also in the list; only one of the two avatars can carry its shared-element key.
        val stripKeys = if (state.showRecent) state.recent.mapTo(HashSet()) { it.key } else emptySet()
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + FAB_CLEARANCE,
                ),
        ) {
            if (state.packs.size > 1) {
                item(key = "filters") { PackFilters(state, onTogglePack) }
            }
            if (!state.hasPacks) {
                item(key = "no-packs") { NoPacksCard(onGetDemoPacks, onOpenPacks) }
            }
            if (state.showRecent) {
                item(key = "recent") { RecentSenders(state.recent, onOpen = open, onDismiss = onDismissRecent) }
            }
            when {
                state.chats.isNotEmpty() -> {
                    item(key = "chats-header") { SectionHeader(stringResource(R.string.home_chats_header)) }
                    items(state.chats, key = { "chat/${it.key.packageName}/${it.key.conversationKey}" }) { row ->
                        ChatListItem(
                            row = row,
                            shared = row.key !in stripKeys,
                            onClick = { if (row.chat.voice != null) openDetail(row.key) else open(row.key) },
                        )
                    }
                }

                state.totalChats == 0 -> {
                    item(key = "empty") { EmptyChats() }
                }

                else -> {
                    item(key = "no-matches") {
                        Text(
                            text = stringResource(R.string.home_no_matches),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                        )
                    }
                }
            }
        }
    }

    if (sheetOpen) {
        AssignSheet(
            unassigned = state.unassigned,
            onDismiss = { sheetOpen = false },
            onPick = { key ->
                sheetOpen = false
                open(key)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
) {
    val focus = LocalFocusManager.current
    Box(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(TopAppBarDefaults.windowInsets)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Surface(
            shape = SearchBarDefaults.inputFieldShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            SearchBarDefaults.InputField(
                query = query,
                onQueryChange = onQueryChange,
                onSearch = { focus.clearFocus() },
                expanded = false,
                onExpandedChange = {},
                placeholder = { Text(stringResource(R.string.home_search_hint)) },
                leadingIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.home_search_clear))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PackFilters(
    state: HomeState,
    onToggle: (String) -> Unit,
) {
    val feedback = LocalFeedback.current
    val label = stringResource(R.string.home_filter_header)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        items(state.packs, key = { it.packId }) { pack ->
            val selected = state.packFilter == pack.packId
            FilterChip(
                selected = selected,
                onClick = {
                    feedback.fire(if (selected) Feedback.ToggleOff else Feedback.ToggleOn)
                    onToggle(pack.packId)
                },
                label = { Text(pack.name) },
            )
        }
    }
}

@Composable
private fun NoPacksCard(
    onGetDemoPacks: () -> Unit,
    onOpenPacks: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.home_no_packs_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.home_no_packs_body), style = MaterialTheme.typography.bodyMedium)
            // Wraps when the labels are long (Spanish), instead of squeezing "Open Packs".
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onGetDemoPacks) { Text(stringResource(R.string.home_get_demo_packs)) }
                TextButton(onClick = onOpenPacks) { Text(stringResource(R.string.home_open_packs)) }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun RecentSenders(
    recent: List<ChatRow>,
    onOpen: (ChatKey) -> Unit,
    onDismiss: (ChatKey) -> Unit,
) {
    Column {
        SectionHeader(stringResource(R.string.home_recent_header))
        Text(
            text = stringResource(R.string.home_recent_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(recent, key = { "recent/${it.key.packageName}/${it.key.conversationKey}" }) { row ->
                RecentSender(row, onOpen = { onOpen(row.key) }, onDismiss = { onDismiss(row.key) })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentSender(
    row: ChatRow,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val feedback = LocalFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var menuOpen by remember { mutableStateOf(false) }
    val name = row.chat.name()
    Box {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier =
                Modifier
                    .width(RECENT_ITEM_WIDTH)
                    .clip(MaterialTheme.shapes.large)
                    .combinedClickable(
                        interactionSource = interaction,
                        indication = ripple(),
                        onClickLabel = stringResource(R.string.chat_open_label),
                        onLongClickLabel = stringResource(R.string.home_recent_hide_label),
                        onLongClick = {
                            feedback.fire(Feedback.LongPress)
                            menuOpen = true
                        },
                        onClick = onOpen,
                    ).padding(vertical = 8.dp, horizontal = 4.dp),
        ) {
            SharedElement(ChatAvatarKey(row.key)) {
                VoiceAvatar(
                    character = null,
                    name = row.chat.displayName,
                    contentDescription = null,
                    size = 64.dp,
                    speaking = pressed,
                )
            }
            Text(
                text = name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            row.chat.lastSeenAt?.let { seenAt ->
                Text(
                    text =
                        DateUtils
                            .getRelativeTimeSpanString(
                                seenAt,
                                System.currentTimeMillis(),
                                DateUtils.MINUTE_IN_MILLIS,
                            ).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.home_recent_hide)) },
                onClick = {
                    menuOpen = false
                    onDismiss()
                },
            )
        }
    }
}

@Composable
private fun ChatListItem(
    row: ChatRow,
    shared: Boolean,
    onClick: () -> Unit,
) {
    val supporting =
        when {
            row.character != null -> {
                stringResource(
                    R.string.home_chat_voice,
                    row.character.name,
                    row.packName.orEmpty(),
                )
            }

            row.voiceMissing -> {
                stringResource(R.string.home_chat_voice_missing)
            }

            else -> {
                stringResource(R.string.home_chat_no_voice)
            }
        }
    ListItem(
        headlineContent = { Text(row.chat.name(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                text = chatSupportingText(row.chat, supporting),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            SharedElement(if (shared) ChatAvatarKey(row.key) else null) {
                VoiceAvatar(character = row.character, name = row.chat.displayName, contentDescription = null)
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.chat_open_label), onClick = onClick),
    )
}

@Composable
private fun EmptyChats() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        CuckooDoor(
            beat = null,
            contentDescription = stringResource(R.string.home_empty_door),
            modifier = Modifier.size(160.dp),
        )
        Text(stringResource(R.string.home_empty_title), style = MaterialTheme.typography.titleLarge)
        Text(
            text = stringResource(R.string.home_empty_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssignSheet(
    unassigned: List<ChatRow>,
    onDismiss: () -> Unit,
    onPick: (ChatKey) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(R.string.home_sheet_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() },
        )
        if (unassigned.isEmpty()) {
            Text(
                text = stringResource(R.string.home_sheet_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(unassigned, key = { "sheet/${it.key.packageName}/${it.key.conversationKey}" }) { row ->
                    ChatListItem(row, shared = false, onClick = { onPick(row.key) })
                }
            }
        }
    }
}

/** The chat's name, or "Unnamed chat" / "Unnamed group". */
@Composable
internal fun Chat.name(): String =
    displayName?.takeIf { it.isNotBlank() }
        ?: stringResource(if (isGroup) R.string.chat_unnamed_group else R.string.chat_unnamed)

private val FAB_CLEARANCE = 96.dp
private val RECENT_ITEM_WIDTH = 88.dp

@Preview(showBackground = true)
@Composable
private fun HomePreview() {
    CucutoTheme(dynamicColor = false) {
        HomeContent(
            state = previewHomeState(),
            onQueryChange = {},
            onTogglePack = {},
            onDismissRecent = {},
            onOpenChat = {},
            onOpenPacks = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeEmptyPreview() {
    CucutoTheme(dynamicColor = false) {
        HomeContent(
            state = HomeState(loading = false),
            onQueryChange = {},
            onTogglePack = {},
            onDismissRecent = {},
            onOpenChat = {},
            onOpenPacks = {},
            onBack = {},
        )
    }
}

/** Made-up chats for previews and tests. */
internal fun previewHomeState(now: Long = PREVIEW_NOW): HomeState {
    val frog =
        VoiceCharacter(
            packId = "app.mauri.demo.critters",
            id = "frog",
            name = "Bleep the frog",
            sprite = null,
            spriteAnimated = null,
            seedColor = null,
            clips = emptyList(),
            haptic = Haptic.Keep,
        )
    val pack =
        app.mauri.cucuto.core.data.voices
            .VoicePack("app.mauri.demo.critters", "Chiptune critters", listOf(frog))
    val chats =
        listOf(
            Chat(ChatKey(WA, "100000000000001@lid"), null, "Ada", false, null, now - 60_000, 2),
            Chat(ChatKey(WA, "120300000000001@g.us"), null, "Book club", true, null, now - 3_600_000, 5),
            Chat(
                ChatKey(WA, "100000000000002@lid"),
                1,
                "Bea",
                false,
                ChatVoice(frog.packId, frog.id),
                now - 86_400_000,
                9,
            ),
        )
    return buildHomeState(chats, listOf(pack), query = "", packFilter = null, now = now)
}

private const val WA = "com.whatsapp"
private const val PREVIEW_NOW = 1_761_000_000_000L
