package app.mauri.cucuto.picker

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mauri.cucuto.R
import app.mauri.cucuto.core.data.chats.Chat
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.chats.ChatVoice
import app.mauri.cucuto.core.data.voices.VoiceCharacter
import app.mauri.cucuto.core.data.voices.VoicePack
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.home.ChatAvatarKey
import app.mauri.cucuto.home.name
import app.mauri.cucuto.ui.components.PackFilterChips
import app.mauri.cucuto.ui.components.ParticleBurst
import app.mauri.cucuto.ui.components.PickerEmptyState
import app.mauri.cucuto.ui.components.VoiceAvatar
import app.mauri.cucuto.ui.components.adaptiveMaxLines
import app.mauri.cucuto.ui.components.selectedChip
import app.mauri.cucuto.ui.feedback.Feedback
import app.mauri.cucuto.ui.feedback.FeedbackBeat
import app.mauri.cucuto.ui.feedback.LocalFeedback
import app.mauri.cucuto.ui.motion.SharedElement
import app.mauri.cucuto.ui.theme.CharacterTheme
import app.mauri.cucuto.ui.theme.CucutoTheme
import app.mauri.cucuto.ui.theme.rememberSeedColor
import kotlinx.coroutines.delay
import java.io.File

/**
 * The character picker (brief, screen 3) for [chat], or a preview-only grid when it is null. The first tap on a
 * character previews it, a second tap assigns it with the "cu-cu!" call, the rising haptic and a particle burst in the
 * character's colors; the character's own clip lands on the call's second note. Then the screen closes ([onDone]).
 */
@Composable
fun PickerScreen(
    chat: ChatKey?,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onOpenPacks: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenDiscover: () -> Unit = {},
) {
    val viewModel =
        hiltViewModel<PickerViewModel, PickerViewModel.Factory>(creationCallback = { factory -> factory.create(chat) })
    val state by viewModel.state.collectAsStateWithLifecycle()
    val feedback = LocalFeedback.current
    var assignBeat by remember { mutableStateOf<FeedbackBeat?>(null) }
    val done by rememberUpdatedState(onDone)

    LaunchedEffect(state.assigned) {
        if (state.assigned == null) return@LaunchedEffect
        delay(ASSIGN_LINGER_MS)
        done()
    }
    LaunchedEffect(state.failed) {
        if (state.failed) feedback.fire(Feedback.Error)
    }

    PickerContent(
        state = state,
        assignBeat = assignBeat,
        onTap = { character ->
            when (viewModel.tap(character)) {
                PickerTap.Preview -> {
                    Unit
                }

                PickerTap.Assign -> {
                    val beat = feedback.fire(Feedback.Assign(character.id))
                    assignBeat = beat
                    viewModel.assign(character, beat.characterClipAtNanos)
                }
            }
        },
        onUnassign = {
            feedback.fire(Feedback.Confirm)
            viewModel.unassign()
        },
        onBack = onBack,
        onOpenPacks = onOpenPacks,
        modifier = modifier,
        onOpenDiscover = onOpenDiscover,
        onFilterPack = { packId ->
            feedback.fire(Feedback.SegmentTick)
            viewModel.filterPack(packId)
        },
        onSearch = viewModel::search,
        onToggleFold = { packId ->
            feedback.fire(Feedback.Tap)
            viewModel.toggleFold(packId)
        },
        onSurprise = { packId ->
            val beat = feedback.fire(Feedback.Roll)
            viewModel.surprise(packId, beat.revealAtNanos)
        },
    )
}

/** Stateless picker, for previews and UI tests. [assignBeat] drives the burst on the assigned character. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerContent(
    state: PickerState,
    assignBeat: FeedbackBeat?,
    onTap: (VoiceCharacter) -> Unit,
    onUnassign: () -> Unit,
    onBack: () -> Unit,
    onOpenPacks: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenDiscover: () -> Unit = {},
    onFilterPack: (String?) -> Unit = {},
    onSearch: (String) -> Unit = {},
    onToggleFold: (String) -> Unit = {},
    onSurprise: (String?) -> Unit = {},
) {
    val bitmaps = remember { mutableStateMapOf<ChatVoice, Bitmap>() }
    val focus = state.selected ?: state.currentVoice
    val focusCharacter = state.character(focus)
    val extracted by rememberSeedColor(focus?.let { bitmaps[it] })
    val seed = focusCharacter?.seedColor?.let { Color(it) } ?: extracted
    val snackbar = remember { SnackbarHostState() }
    val failedMessage = stringResource(R.string.picker_failed)
    LaunchedEffect(state.failed) {
        if (state.failed) snackbar.showSnackbar(failedMessage)
    }

    val grid = rememberLazyGridState()
    val sections =
        remember(state.packs, state.packFilter, state.query, state.folds, state.chat?.voice) { state.sections }
    // Full-width items before the first pack: the header, the search field and the chips.
    val leading = if (state.chips.isEmpty()) 2 else 3
    PickerScrollToRoll(grid, state.roll?.count) { sections.gridIndexOf(leading) { it.voice == state.roll?.pick } }

    CharacterTheme(seed = seed) {
        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        Scaffold(
            modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                val rolled = state.character(state.roll?.pick)
                if (state.rolled && rolled != null) {
                    SurpriseBar(
                        useLabel = stringResource(R.string.picker_use, rolled.name).takeIf { state.canAssign },
                        onReroll = { onSurprise(state.roll?.packId) },
                        onUse = { onTap(rolled) },
                    )
                }
            },
            topBar = {
                MediumFlexibleTopAppBar(
                    title = { Text(stringResource(R.string.picker_title), Modifier.semantics { heading() }) },
                    subtitle = {
                        Text(
                            text =
                                when {
                                    !state.canAssign -> stringResource(R.string.picker_subtitle_preview)
                                    else -> stringResource(R.string.picker_subtitle_for, state.chatName())
                                },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.navigate_back),
                            )
                        }
                    },
                    actions = {
                        if (state.packs.isNotEmpty() && state.assigned == null) {
                            SurpriseButton(
                                label = stringResource(R.string.picker_surprise_all),
                                onClick = { onSurprise(null) },
                            )
                        }
                        if (state.canAssign && state.chat?.voice != null && state.assigned == null) {
                            IconButton(onClick = onUnassign) {
                                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.picker_remove))
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { innerPadding ->
            when {
                state.loading -> {
                    Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                        val label = stringResource(R.string.picker_loading)
                        LoadingIndicator(Modifier.semantics { contentDescription = label })
                    }
                }

                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(CELL_MIN_WIDTH),
                        state = grid,
                        contentPadding =
                            PaddingValues(
                                start = 12.dp,
                                end = 12.dp,
                                bottom = innerPadding.calculateBottomPadding() + 24.dp,
                            ),
                        // Below the top bar, not under it, so the packs' sticky headers stay in sight.
                        modifier = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding()),
                    ) {
                        fullWidth("header") { PickerHeader(state, assignBeat) }
                        if (state.packs.isEmpty()) {
                            fullWidth("empty") {
                                PickerEmptyState(
                                    title = stringResource(R.string.picker_empty_title),
                                    body = stringResource(R.string.picker_empty_body),
                                    onOpenPacks = onOpenPacks,
                                    onOpenDiscover = onOpenDiscover,
                                )
                            }
                            return@LazyVerticalGrid
                        }
                        fullWidth("search") { PickerSearchField(state.query, onSearch) }
                        if (state.chips.isNotEmpty()) {
                            fullWidth("chips") {
                                PackFilterChips(
                                    chips = state.chips,
                                    selected = selectedChip(state.chips, state.packFilter),
                                    onSelect = onFilterPack,
                                    modifier = Modifier.padding(vertical = 4.dp),
                                )
                            }
                        }
                        if (sections.isEmpty()) fullWidth("no-matches") { PickerNoMatches(state.query) }
                        sections.forEach { section ->
                            stickyHeader(key = "pack/${section.packId}", contentType = "pack") {
                                PackSectionHeader(
                                    section = section,
                                    onToggle = { onToggleFold(section.packId) },
                                    onSurprise = { onSurprise(section.packId) },
                                    enabled = state.assigned == null,
                                )
                            }
                            items(
                                section.shown,
                                key = { "character/${it.packId}/${it.id}" },
                                contentType = { "character" },
                            ) { character ->
                                val voice = character.voice
                                CharacterCell(
                                    character = character,
                                    selected = state.selected == voice,
                                    current = state.currentVoice == voice,
                                    speaking = state.speaking == voice,
                                    canAssign = state.canAssign && state.assigned == null,
                                    beat = assignBeat?.takeIf { state.assigned == voice },
                                    onTap = { onTap(character) },
                                    onBitmap = { bitmaps[voice] = it },
                                    modifier = animateCellUnlessReduced(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun LazyGridScope.fullWidth(
    key: String,
    content: @Composable () -> Unit,
) = item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
private fun PickerState.chatName(): String =
    chat?.name()
        ?: stringResource(if (chatKey?.isGroupKey == true) R.string.chat_unnamed_group else R.string.chat_unnamed)

@Composable
private fun PickerHeader(
    state: PickerState,
    beat: FeedbackBeat?,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val key = state.chatKey
        if (key != null) {
            val current = state.character(state.currentVoice)
            val name = state.chatName()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SharedElement(ChatAvatarKey(key)) {
                    VoiceAvatar(
                        character = current,
                        name = state.chat?.displayName,
                        contentDescription = null,
                        size = 72.dp,
                        beat = beat,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = adaptiveMaxLines(1),
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text =
                            when {
                                state.assigned != null && current != null -> {
                                    stringResource(R.string.picker_assigned, current.name, name)
                                }

                                current != null -> {
                                    stringResource(R.string.picker_speaks_as, current.name)
                                }

                                else -> {
                                    stringResource(R.string.picker_no_voice)
                                }
                            },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
        Text(
            text = stringResource(if (state.canAssign) R.string.picker_hint else R.string.picker_hint_preview),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CharacterCell(
    character: VoiceCharacter,
    selected: Boolean,
    current: Boolean,
    speaking: Boolean,
    canAssign: Boolean,
    beat: FeedbackBeat?,
    onTap: () -> Unit,
    onBitmap: (Bitmap) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val stateLabel =
        listOfNotNull(
            if (selected) stringResource(R.string.picker_state_selected) else null,
            if (current) stringResource(R.string.picker_current) else null,
            if (speaking) stringResource(R.string.picker_state_playing) else null,
        ).joinToString()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.zIndex(if (beat != null) 1f else 0f).padding(vertical = 8.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(AVATAR_BOX)) {
            VoiceAvatar(
                character = character,
                name = character.name,
                contentDescription = character.name,
                size = AVATAR_SIZE,
                speaking = speaking,
                beat = beat,
                color =
                    when {
                        selected -> colors.tertiaryContainer
                        current -> colors.primaryContainer
                        else -> colors.surfaceContainerHigh
                    },
                onClick = onTap,
                onClickLabel =
                    stringResource(
                        if (selected &&
                            canAssign
                        ) {
                            R.string.picker_assign_label
                        } else {
                            R.string.picker_preview_label
                        },
                    ),
                onBitmap = onBitmap,
                modifier =
                    Modifier.semantics {
                        this.selected = selected
                        if (stateLabel.isNotEmpty()) stateDescription = stateLabel
                    },
            )
            ParticleBurst(beat, Modifier.requiredSize(BURST_SIZE))
        }
        Text(
            text = character.name,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clearAndSetSemantics {},
        )
        val note =
            when {
                selected && canAssign -> stringResource(R.string.picker_tap_again)
                current -> stringResource(R.string.picker_current)
                else -> null
            }
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) colors.tertiary else colors.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

/** How long the picker stays after an assignment: the burst (0.65 s) and the call play out before it closes. */
private const val ASSIGN_LINGER_MS = 900L
private val CELL_MIN_WIDTH = 104.dp
private val AVATAR_SIZE = 76.dp
private val AVATAR_BOX = 88.dp
private val BURST_SIZE = 240.dp

@Preview(showBackground = true)
@Composable
private fun PickerPreview() {
    CucutoTheme(dynamicColor = false) {
        PickerContent(
            state = previewPickerState(),
            assignBeat = null,
            onTap = {},
            onUnassign = {},
            onBack = {},
            onOpenPacks = {},
        )
    }
}

/** Made-up characters and chat for previews and tests. */
internal fun previewPickerState(): PickerState {
    fun character(
        id: String,
        name: String,
        seed: Int,
        packId: String = "app.mauri.demo.critters",
    ) = VoiceCharacter(
        packId = packId,
        id = id,
        name = name,
        sprite = null,
        spriteAnimated = null,
        seedColor = seed,
        clips = listOf(File("/packs/$packId/$id.ogg")),
        haptic = Haptic.Keep,
    )
    val pack =
        VoicePack(
            packId = "app.mauri.demo.critters",
            name = "Chiptune critters",
            characters =
                listOf(
                    character("frog", "Bleep the frog", 0xFF4CAF50.toInt()),
                    character("bee", "Buzz the bee", 0xFFFFC107.toInt()),
                    character("cat", "Purr the cat", 0xFF9C27B0.toInt()),
                ),
        )
    val kitchen =
        VoicePack(
            packId = "app.mauri.demo.kitchen",
            name = "Kitchen orchestra",
            characters = listOf(character("kettle", "Tootle the kettle", 0xFFE57373.toInt(), "app.mauri.demo.kitchen")),
        )
    val key = ChatKey("com.whatsapp", "100000000000001@lid")
    return PickerState(
        loading = false,
        chatKey = key,
        chat = Chat(key, null, "Ada", false, null, 0L, 1),
        packs = listOf(pack, kitchen),
        selected = ChatVoice(pack.packId, "bee"),
    )
}
