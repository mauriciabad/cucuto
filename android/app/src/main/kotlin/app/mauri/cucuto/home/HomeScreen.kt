package app.mauri.cucuto.home

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mauri.cucuto.R
import app.mauri.cucuto.core.data.chats.Chat
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.chats.ChatRingtone
import app.mauri.cucuto.core.data.chats.ChatVoice
import app.mauri.cucuto.core.data.chats.LinkCandidate
import app.mauri.cucuto.core.data.voices.VoiceCharacter
import app.mauri.cucuto.core.pack.ImportSource
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.navigation.CucutoDestination
import app.mauri.cucuto.packs.ui.PackPickerTypes
import app.mauri.cucuto.ui.chat.chatSupportingText
import app.mauri.cucuto.ui.components.CuckooDoor
import app.mauri.cucuto.ui.components.VoiceAvatar
import app.mauri.cucuto.ui.components.adaptiveMaxLines
import app.mauri.cucuto.ui.contacts.PersonAddIcon
import app.mauri.cucuto.ui.feedback.Feedback
import app.mauri.cucuto.ui.feedback.LocalFeedback
import app.mauri.cucuto.ui.motion.SharedCompanion
import app.mauri.cucuto.ui.motion.SharedElement
import app.mauri.cucuto.ui.ringtone.RingtoneBadge
import app.mauri.cucuto.ui.ringtone.ringtoneDescription
import app.mauri.cucuto.ui.settings.restore.rememberBackupPicker
import app.mauri.cucuto.ui.status.SetupCardSection
import app.mauri.cucuto.ui.theme.CucutoTheme

/** Key of a chat's avatar in shared-element transitions (home list or strip → picker header). */
data class ChatAvatarKey(
    val chat: ChatKey,
)

/** Key of the ringtone badge riding on a chat's avatar ([ChatAvatarKey]), so the two move as one group (#339). */
data class ChatRingtoneBadgeKey(
    val chat: ChatKey,
)

/**
 * Home (brief, screen 2), the start tab: every chat with its character's sprite as avatar, the "recent senders" strip
 * of people who messaged and have no voice yet, search, a pack filter and a FAB to assign. Above them sit the first-run
 * journey ([homeJourney]: import the demo packs or restore a backup, give the first recent sender a voice, celebrate)
 * and the setup card (until setup is green once). Tapping a chat with a voice opens its contact detail (#26); a chat without one
 * goes straight to the character picker.
 */
@Composable
fun HomeScreen(
    onNavigate: (CucutoDestination) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
    journeyViewModel: HomeJourneyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val firstVoiceCelebrated by journeyViewModel.firstVoiceCelebrated.collectAsStateWithLifecycle()
    val celebrating by journeyViewModel.celebrating.collectAsStateWithLifecycle()
    val journey = homeJourney(state, firstVoiceCelebrated, celebrating)
    LaunchedEffect(journey) {
        if (journey is JourneyStep.FirstVoice) journeyViewModel.celebrateFirstVoice(journey.chat.key)
    }
    val resolver = LocalContext.current.applicationContext.contentResolver
    val demoPicker =
        rememberLauncherForActivityResult(OpenPackFromDownloads()) { uri ->
            if (uri != null) journeyViewModel.importDemoPacks(ImportSource.of(resolver, uri))
        }
    val pickBackup = rememberBackupPicker(onNavigate)
    HomeContent(
        state = state,
        onQueryChange = viewModel::setQuery,
        onTogglePack = viewModel::togglePackFilter,
        onDismissRecent = viewModel::dismissRecent,
        // Assigning from Home: the message sound, then the ringtone as an optional step 2 (ADR 0073).
        onOpenChat = { chat -> onNavigate(CucutoDestination.Picker(chat, ringtoneNext = true)) },
        onOpenPacks = { onNavigate(CucutoDestination.Packs) },
        modifier = modifier,
        onOpenDetail = { chat -> onNavigate(CucutoDestination.ContactDetail(chat)) },
        onAssign = { onNavigate(CucutoDestination.AssignVoice) },
        journey = journey,
        // Discover installs the demo bundle from the default repository, through the usual review (#179, #214).
        onGetDemoPacks = {
            viewModel.requestDemoPacks()
            onNavigate(CucutoDestination.Discover)
        },
        // Offline fallback: a demo packs file the user already has in Downloads.
        onImportDemoPacks = { demoPicker.launch(PackPickerTypes) },
        // Someone moving to a new phone looks here first, not in Settings (ADR 0050).
        onRestoreBackup = pickBackup,
        onPreviewCharacters = { onNavigate(CucutoDestination.Picker()) },
        onDismissCelebration = journeyViewModel::dismissCelebration,
        onAddFromContacts = { onNavigate(CucutoDestination.AddFromContacts) },
        onOpenQuestion = { chat -> onNavigate(CucutoDestination.ConfirmLink(chat)) },
        setupCard = { SetupCardSection(onNavigate = onNavigate) },
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
    modifier: Modifier = Modifier,
    onOpenDetail: (ChatKey) -> Unit = onOpenChat,
    onAssign: () -> Unit = {},
    journey: JourneyStep = JourneyStep.None,
    onGetDemoPacks: () -> Unit = {},
    onImportDemoPacks: () -> Unit = {},
    onRestoreBackup: () -> Unit = {},
    onPreviewCharacters: () -> Unit = {},
    onDismissCelebration: () -> Unit = {},
    onAddFromContacts: () -> Unit = {},
    onOpenQuestion: (ChatKey) -> Unit = {},
    setupCard: @Composable () -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf(state.query) }
    val latestOnQueryChange by rememberUpdatedState(onQueryChange)
    LaunchedEffect(query) { latestOnQueryChange(query) }
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
        // Chat rows carry a resource ID, so the Baseline Profile generator can open one (#226).
        modifier = modifier.semantics { testTagsAsResourceId = true },
        topBar = { HomeSearchBar(query, onQueryChange = { query = it }) },
        floatingActionButton = {
            if (state.totalChats > 0 && state.hasPacks) {
                val fabLabel = stringResource(R.string.home_fab)
                ExtendedFloatingActionButton(
                    onClick = {
                        feedback.fire(Feedback.Tap)
                        onAssign()
                    },
                    // Material hides the text from TalkBack: the icon carries the label, expanded or not.
                    icon = { Icon(Icons.Rounded.Add, contentDescription = fabLabel) },
                    text = { Text(fabLabel) },
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
            // The journey's next step comes first, so its main button is on screen without scrolling on a phone;
            // the setup card follows (ADR 0050).
            if (journey != JourneyStep.None && !state.filtering) {
                item(key = "journey", contentType = "journey") {
                    JourneyCard(
                        step = journey,
                        onGetDemoPacks = onGetDemoPacks,
                        onImportDemoPacks = onImportDemoPacks,
                        onRestoreBackup = onRestoreBackup,
                        onOpenChat = open,
                        onPreviewCharacters = onPreviewCharacters,
                        onDismissCelebration = onDismissCelebration,
                        onAddFromContacts = onAddFromContacts,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            item(key = "setup", contentType = "setup") { setupCard() }
            if (state.packs.size > 1) {
                item(key = "filters") { PackFilters(state, onTogglePack) }
            }
            if (state.showQuestions) {
                item(key = "questions-header") { SectionHeader(stringResource(R.string.home_questions_header)) }
                items(
                    state.questions,
                    key = { "question/${it.key.packageName}/${it.key.conversationKey}" },
                    contentType = { "question" },
                ) { question ->
                    QuestionItem(question) {
                        feedback.fire(Feedback.Tap)
                        onOpenQuestion(question.key)
                    }
                }
            }
            if (state.showRecent) {
                item(key = "recent") { RecentSenders(state.recent, onOpen = open, onDismiss = onDismissRecent) }
            }
            if (state.waiting.isNotEmpty()) {
                item(key = "waiting-header") {
                    Column {
                        SectionHeader(stringResource(R.string.home_waiting_header))
                        Text(
                            text = stringResource(R.string.home_waiting_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
                // Waiting chats open their contact detail: the voice and the phone ringtone are both one tap away.
                items(
                    state.waiting,
                    key = { "waiting/${it.key.packageName}/${it.key.conversationKey}" },
                    contentType = { "chat" },
                ) { row ->
                    ChatListItem(row = row, shared = row.key !in stripKeys, onClick = { openDetail(row.key) })
                }
            }
            when {
                state.chats.isNotEmpty() -> {
                    item(key = "chats-header") { SectionHeader(stringResource(R.string.home_chats_header)) }
                    items(
                        state.chats,
                        key = { "chat/${it.key.packageName}/${it.key.conversationKey}" },
                        contentType = { "chat" },
                    ) { row ->
                        ChatListItem(
                            row = row,
                            shared = row.key !in stripKeys,
                            onClick = { if (row.chat.voice != null) openDetail(row.key) else open(row.key) },
                        )
                    }
                }

                state.totalChats == 0 -> {
                    // The journey card already says what to do while there are no chats.
                    if (journey == JourneyStep.None) item(key = "empty") { EmptyChats(onAddFromContacts) }
                }

                state.waiting.isNotEmpty() -> {}

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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
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
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
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
            Box {
                SharedElement(ChatAvatarKey(row.key)) {
                    VoiceAvatar(
                        character = null,
                        name = row.chat.displayName,
                        contentDescription = null,
                        size = 64.dp,
                        speaking = pressed,
                    )
                }
                // No badge here: a ringtone badge from contact detail shrinks into the avatar's corner (#339).
                SharedCompanion(ChatRingtoneBadgeKey(row.key), Modifier.align(Alignment.BottomEnd))
            }
            Text(
                text = name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = adaptiveMaxLines(1),
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
                    maxLines = adaptiveMaxLines(1),
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
internal fun ChatListItem(
    row: ChatRow,
    shared: Boolean,
    onClick: () -> Unit,
) {
    val voice =
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
                null
            }
        }
    val supporting =
        when {
            row.chat.awaitingLink && voice != null -> stringResource(R.string.home_chat_voice_not_linked, voice)
            row.chat.awaitingLink -> stringResource(R.string.home_chat_not_linked)
            else -> voice ?: stringResource(R.string.home_chat_no_voice)
        }
    ListItem(
        headlineContent = { Text(row.chat.name(), maxLines = adaptiveMaxLines(1), overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                text = chatSupportingText(row.chat, supporting),
                maxLines = adaptiveMaxLines(1),
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            // The ringtone's cover tucked against the avatar, like contact detail's header (ADR 0073), so the text
            // keeps its width. TalkBack reads it with the row.
            // The badge is its own shared element drawn above the avatar's, so the pair flies as one group (#339).
            Box {
                SharedElement(if (shared) ChatAvatarKey(row.key) else null) {
                    VoiceAvatar(character = row.character, name = row.chat.displayName, contentDescription = null)
                }
                val ringtone = row.ringtone
                val badgeKey = if (shared) ChatRingtoneBadgeKey(row.key) else null
                if (ringtone != null) {
                    val description = ringtoneDescription(ringtone)
                    SharedCompanion(
                        badgeKey,
                        Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 6.dp, y = 4.dp)
                            .semantics { contentDescription = description },
                    ) { RingtoneBadge(ringtone, RINGTONE_BADGE_SIZE) }
                } else {
                    SharedCompanion(badgeKey, Modifier.align(Alignment.BottomEnd))
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier =
            Modifier
                .testTag(CHAT_ROW_TAG)
                .clickable(onClickLabel = stringResource(R.string.chat_open_label), onClick = onClick),
    )
}

/** A recent sender that may be someone added from contacts (ADR 0065): a tap opens "Who is this?". */
@Composable
private fun QuestionItem(
    question: LinkQuestion,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(question.chat.chat.name(), maxLines = adaptiveMaxLines(1), overflow = TextOverflow.Ellipsis)
        },
        supportingContent = { Text(stringResource(R.string.home_question_body)) },
        leadingContent = {
            VoiceAvatar(character = null, name = question.chat.chat.displayName, contentDescription = null)
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.confirm_link_title), onClick = onClick),
    )
}

/** "Add a chat from contacts" (ADR 0065), offered where someone looks for a person who is not there yet. */
@Composable
internal fun AddFromContactsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val feedback = LocalFeedback.current
    OutlinedButton(
        onClick = {
            feedback.fire(Feedback.Tap)
            onClick()
        },
        shapes = ButtonDefaults.shapes(),
        modifier = modifier,
    ) {
        Icon(PersonAddIcon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(stringResource(R.string.contacts_add))
    }
}

@Composable
private fun EmptyChats(onAddFromContacts: () -> Unit) {
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
        Text(
            stringResource(R.string.home_empty_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.home_empty_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        AddFromContactsButton(onAddFromContacts)
    }
}

/** The chat's name, or "Unnamed chat" / "Unnamed group". */
@Composable
internal fun Chat.name(): String =
    displayName?.takeIf { it.isNotBlank() }
        ?: stringResource(if (isGroup) R.string.chat_unnamed_group else R.string.chat_unnamed)

private val FAB_CLEARANCE = 96.dp
private val RINGTONE_BADGE_SIZE = 24.dp
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
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeEmptyPreview() {
    CucutoTheme(dynamicColor = false) {
        HomeContent(
            state = HomeState(loading = false),
            journey = JourneyStep.ImportPacks,
            onQueryChange = {},
            onTogglePack = {},
            onDismissRecent = {},
            onOpenChat = {},
            onOpenPacks = {},
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
    val song =
        VoiceCharacter(
            packId = "app.mauri.demo.songs",
            id = "bubblegum",
            name = "Bubblegum K.K.",
            sprite = null,
            spriteAnimated = null,
            seedColor = null,
            clips = emptyList(),
            haptic = Haptic.Keep,
            ringtoneCount = 2,
        )
    val songs =
        app.mauri.cucuto.core.data.voices
            .VoicePack(song.packId, "Songs", listOf(song))
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
                ringtone = ChatRingtone.Character(song.voice, followsVoice = false),
            ),
        )
    return buildHomeState(chats, listOf(pack, songs), query = "", packFilter = null, now = now)
}

/**
 * Made-up chats with someone added from contacts waiting for a first message, and a recent sender whose name matches
 * two people added from contacts ("Who is this?").
 */
internal fun previewHomeStateWithQuestion(now: Long = PREVIEW_NOW): HomeState {
    fun candidate(
        id: Long,
        number: String,
    ) = LinkCandidate(id, ChatKey(WA, "${number.removePrefix("+")}@s.whatsapp.net"), "Leo", listOf(number), null)
    val leo1 = candidate(8, "+34600000008")
    val leo2 = candidate(9, "+34600000009")
    val chats =
        listOf(
            Chat(ChatKey(WA, "100000000000001@lid"), null, "Ada", false, null, now - 60_000, 2),
            Chat(
                ChatKey(WA, "100000000000005@lid"),
                null,
                "Leo",
                false,
                null,
                now - 120_000,
                1,
                linkCandidates = listOf(leo1, leo2),
            ),
            Chat(
                ChatKey(WA, "34600000007@s.whatsapp.net"),
                7,
                "Marta",
                false,
                null,
                null,
                0,
                awaitingLink = true,
                phoneNumbers = listOf("+34600000007"),
            ),
        ) +
            listOf(leo1, leo2).map {
                Chat(
                    it.key,
                    it.contactId,
                    "Leo",
                    false,
                    null,
                    null,
                    0,
                    awaitingLink = true,
                    phoneNumbers = it.phoneNumbers,
                )
            }
    return buildHomeState(chats, packs = emptyList(), query = "", packFilter = null, now = now)
}

private const val WA = "com.whatsapp"
private const val PREVIEW_NOW = 1_761_000_000_000L

/** Test tag (and, on Home, resource ID) of each chat row; `baselineprofile/` opens one by it. */
const val CHAT_ROW_TAG = "chat_row"
