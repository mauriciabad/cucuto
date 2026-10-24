package app.mauri.cucuto.ui.ringtone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mauri.cucuto.R
import app.mauri.cucuto.core.data.chats.Chat
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.db.RingtoneMode
import app.mauri.cucuto.core.data.voices.VoiceCharacter
import app.mauri.cucuto.core.ringtone.variants.RingtoneVariant
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.picker.PackSectionHeader
import app.mauri.cucuto.picker.PickerNoMatches
import app.mauri.cucuto.picker.PickerScrollToRoll
import app.mauri.cucuto.picker.PickerSearchField
import app.mauri.cucuto.picker.RerollButton
import app.mauri.cucuto.picker.SurpriseButton
import app.mauri.cucuto.picker.animateCellUnlessReduced
import app.mauri.cucuto.picker.gridIndexOf
import app.mauri.cucuto.ui.components.BottomActionBar
import app.mauri.cucuto.ui.components.CuckooDoor
import app.mauri.cucuto.ui.components.PackFilterChips
import app.mauri.cucuto.ui.components.ParticleBurst
import app.mauri.cucuto.ui.components.PickerEmptyState
import app.mauri.cucuto.ui.components.PushedScreen
import app.mauri.cucuto.ui.components.adaptiveMaxLines
import app.mauri.cucuto.ui.components.selectedChip
import app.mauri.cucuto.ui.feedback.Feedback
import app.mauri.cucuto.ui.feedback.FeedbackBeat
import app.mauri.cucuto.ui.feedback.LocalFeedback
import app.mauri.cucuto.ui.theme.CharacterTheme
import app.mauri.cucuto.ui.theme.CucutoTheme
import kotlinx.coroutines.delay
import java.io.File

/** What the ringtone picker can ask for; [RingtonePickerScreen] wires it to its view model. */
data class RingtonePickerActions(
    val onBack: () -> Unit = {},
    val onSelect: (RingtoneCharacterItem) -> Unit = {},
    val onTogglePreview: (RingtoneCharacterItem, String) -> Unit = { _, _ -> },
    val onStopPreview: () -> Unit = {},
    val onMode: (RingtoneMode) -> Unit = {},
    val onVariant: (String) -> Unit = {},
    val onSave: () -> Unit = {},
    val onOpenPacks: () -> Unit = {},
    val onOpenDiscover: () -> Unit = {},
    /** A pack chip: only that pack's characters (null: all). */
    val onFilterPack: (String?) -> Unit = {},
    /** The search field's text (ADR 0072). */
    val onSearch: (String) -> Unit = {},
    /** A pack header: fold or unfold that pack. */
    val onToggleFold: (String) -> Unit = {},
    /** "Surprise me" from a pack, or from every pack (null). */
    val onSurprise: (String?) -> Unit = {},
)

/**
 * The ringtone picker (#53; brief, "Ringtones") for [chat], opened from the contact detail's Phone ringtone card: album
 * covers of every character with ringtones, a tap plays one with a spinning record, each version plays on its own,
 * then a rule and "Ring with …" saves it with the assign call and a burst in the character's colors ([onDone] closes).
 */
@Composable
fun RingtonePickerScreen(
    chat: ChatKey,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onOpenPacks: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenDiscover: () -> Unit = {},
) {
    val viewModel =
        hiltViewModel<RingtonePickerViewModel, RingtonePickerViewModel.Factory>(
            creationCallback = { factory -> factory.create(chat) },
        )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val feedback = LocalFeedback.current
    var beat by remember { mutableStateOf<FeedbackBeat?>(null) }
    val done by rememberUpdatedState(onDone)
    // A preview never plays on behind another app or a locked screen.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.stopPreview() }
    LaunchedEffect(state.assigned) {
        if (state.assigned == null) return@LaunchedEffect
        delay(ASSIGN_LINGER_MS)
        done()
    }
    LaunchedEffect(state.failed) {
        if (state.failed) feedback.fire(Feedback.Error)
    }

    fun save() {
        val characterId = state.selection?.voice?.characterId
        if (viewModel.save()) beat = feedback.fire(Feedback.Assign(characterId))
    }
    // "Random per call" re-rolls when a call ends, which needs the phone permission: ask first, save either way.
    val askPhone = rememberPhonePermissionFlow { save() }

    RingtonePickerContent(
        state = state,
        beat = beat,
        actions =
            RingtonePickerActions(
                onBack = onBack,
                onSelect = { item ->
                    feedback.fire(Feedback.Tap)
                    viewModel.select(item)
                },
                onTogglePreview = { item, file ->
                    feedback.fire(Feedback.Tap)
                    viewModel.togglePreview(item, file)
                },
                onStopPreview = {
                    feedback.fire(Feedback.Tap)
                    viewModel.stopPreview()
                },
                onMode = { mode ->
                    feedback.fire(Feedback.SegmentTick)
                    viewModel.setMode(mode)
                },
                onVariant = { file ->
                    feedback.fire(Feedback.SegmentTick)
                    viewModel.setVariant(file)
                },
                onSave = { if (viewModel.needsPhonePermission()) askPhone() else save() },
                onOpenPacks = onOpenPacks,
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
                    feedback.fire(Feedback.Roll)
                    viewModel.surprise(packId)
                },
            ),
        modifier = modifier,
    )
}

/** Stateless ringtone picker, for previews, screenshots and UI tests. [beat] drives the burst after saving. */
@Composable
fun RingtonePickerContent(
    state: RingtonePickerState,
    beat: FeedbackBeat?,
    actions: RingtonePickerActions,
    modifier: Modifier = Modifier,
) {
    val selected = state.selectedCharacter
    val snackbar = remember { SnackbarHostState() }
    val failedMessage = stringResource(R.string.ringtone_picker_failed)
    LaunchedEffect(state.failed) {
        if (state.failed) snackbar.showSnackbar(failedMessage)
    }
    val grid = rememberLazyGridState()
    val sections =
        remember(state.packs, state.packFilter, state.query, state.folds, state.saved, state.chat?.voice) {
            state.sections
        }
    // Full-width items before the first pack: the header, the "Characters" title, the search field and the chips.
    val leading = if (state.chips.isEmpty()) 3 else 4
    PickerScrollToRoll(grid, state.roll?.count) { sections.gridIndexOf(leading) { it.voice == state.roll?.pick } }
    CharacterTheme(seed = selected?.character?.seedColor?.let { Color(it) }) {
        PushedScreen(
            title = stringResource(R.string.ringtone_picker_title),
            subtitle = state.chatName()?.let { stringResource(R.string.ringtone_picker_subtitle, it) },
            onBack = actions.onBack,
            modifier = modifier,
            actions = {
                if (!state.loading && state.packs.isNotEmpty() && state.assigned == null) {
                    SurpriseButton(
                        label = stringResource(R.string.picker_surprise_all),
                        onClick = { actions.onSurprise(null) },
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (!state.loading && state.packs.isNotEmpty() && !state.isGroup) {
                    SaveBar(state, actions.onSave, onReroll = { actions.onSurprise(state.roll?.packId) })
                }
            },
        ) { padding ->
            when {
                state.loading -> {
                    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
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
                                bottom = padding.calculateBottomPadding() + 24.dp,
                            ),
                        // Below the top bar, not under it, so the packs' sticky headers stay in sight.
                        modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
                    ) {
                        when {
                            state.packs.isEmpty() -> {
                                fullWidth("empty") {
                                    PickerEmptyState(
                                        title = stringResource(R.string.ringtone_picker_empty_title),
                                        body = stringResource(R.string.ringtone_picker_empty_body),
                                        onOpenPacks = actions.onOpenPacks,
                                        onOpenDiscover = actions.onOpenDiscover,
                                    )
                                }
                            }

                            else -> {
                                fullWidth("header") { Header(state, beat, actions) }
                                fullWidth("characters") { SectionTitle(R.string.ringtone_picker_characters) }
                                fullWidth("search") { PickerSearchField(state.query, actions.onSearch) }
                                if (state.chips.isNotEmpty()) {
                                    fullWidth("chips") {
                                        PackFilterChips(
                                            chips = state.chips,
                                            selected = selectedChip(state.chips, state.packFilter),
                                            onSelect = actions.onFilterPack,
                                            modifier = Modifier.padding(vertical = 4.dp),
                                        )
                                    }
                                }
                                if (sections.isEmpty()) fullWidth("no-matches") { PickerNoMatches(state.query) }
                                sections.forEach { section ->
                                    stickyHeader(key = "pack/${section.packId}", contentType = "pack") {
                                        PackSectionHeader(
                                            section = section,
                                            onToggle = { actions.onToggleFold(section.packId) },
                                            onSurprise = { actions.onSurprise(section.packId) },
                                            enabled = state.assigned == null,
                                        )
                                    }
                                    items(
                                        section.shown,
                                        key = { "character/${it.voice.packId}/${it.voice.characterId}" },
                                        contentType = { "cover" },
                                    ) {
                                        CoverCell(
                                            item = it,
                                            selected = state.selection?.voice == it.voice,
                                            playing = state.playing?.voice == it.voice && state.preview?.paused != true,
                                            isVoice = state.voice == it.voice,
                                            onTap = { actions.onSelect(it) },
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
    }
}

private fun LazyGridScope.fullWidth(
    key: String,
    content: @Composable () -> Unit,
) = item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
private fun RingtonePickerState.chatName(): String? =
    chat?.displayName ?: chatKey?.let { stringResource(R.string.chat_unnamed) }

/** The chosen character: its record, its versions with play buttons, and the rule that picks one. */
@Composable
private fun Header(
    state: RingtonePickerState,
    beat: FeedbackBeat?,
    actions: RingtonePickerActions,
) {
    val selection = state.selection
    val item = state.selectedCharacter
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (selection == null || item == null) {
            Hint(stringResource(R.string.ringtone_picker_hint))
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.zIndex(1f)) {
                RecordCover(
                    character = item.character,
                    size = HEADER_COVER,
                    pulledOut = true,
                    spinning = state.playing?.voice == item.voice && state.preview?.paused != true,
                    sleeveColor = MaterialTheme.colorScheme.primaryContainer,
                )
                ParticleBurst(beat, Modifier.requiredSize(BURST_SIZE))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    item.character.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = adaptiveMaxLines(2),
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = relation(state, selection),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        SectionTitle(R.string.ringtone_picker_versions)
        Column {
            for (variant in item.variants) {
                VariantRow(
                    variant = variant,
                    preview = state.preview?.takeIf { it.key == PlayingVariant(item.voice, variant.file) },
                    fixed = selection.mode == RingtoneMode.FIXED && state.modes.isNotEmpty(),
                    chosen = selection.variant == variant.file,
                    onPlay = { actions.onTogglePreview(item, variant.file) },
                    onStop = actions.onStopPreview,
                    onChoose = { actions.onVariant(variant.file) },
                )
            }
        }
        if (state.modes.isNotEmpty()) {
            SectionTitle(R.string.ringtone_picker_rule)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (mode in state.modes) {
                    FilterChip(
                        selected = selection.mode == mode,
                        onClick = { if (selection.mode != mode) actions.onMode(mode) },
                        label = { Text(stringResource(mode.label)) },
                    )
                }
            }
            Hint(stringResource(selection.mode.body))
        }
    }
}

/** Who else this character speaks for: the messages (same character) or not (mixing). */
@Composable
private fun relation(
    state: RingtonePickerState,
    selection: RingtoneSelection,
): String {
    val name = state.chatName().orEmpty()
    val voiceName =
        state.voice?.let { v ->
            state.packs.firstNotNullOfOrNull { p ->
                p.characters.firstOrNull {
                    it.voice ==
                        v
                }
            }
        }
    return when {
        state.assigned != null -> {
            stringResource(R.string.ringtone_picker_assigned, name)
        }

        selection.followsVoice -> {
            stringResource(R.string.ringtone_picker_follows_voice)
        }

        state.voice != null -> {
            stringResource(
                R.string.ringtone_picker_mixed,
                voiceName?.character?.name ?: state.voice!!.characterId,
            )
        }

        else -> {
            stringResource(R.string.ringtone_picker_calls_only)
        }
    }
}

@Composable
private fun VariantRow(
    variant: RingtoneVariantItem,
    preview: PreviewPlaying<*>?,
    fixed: Boolean,
    chosen: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onChoose: () -> Unit,
) {
    val label = variant.label
    val tags = variant.variant.tags.joinToString(" · ")
    val choose =
        if (fixed) {
            Modifier.selectable(selected = chosen, role = Role.RadioButton, onClick = onChoose)
        } else {
            Modifier
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(choose)
                .padding(vertical = 4.dp),
    ) {
        RingtonePlayButton(
            preview = preview,
            name = label,
            onToggle = onPlay,
            onStop = onStop,
            enabled = variant.audio != null,
        )
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (tags.isNotEmpty()) {
                Text(
                    tags,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (fixed) RadioButton(selected = chosen, onClick = null)
    }
}

@Composable
private fun CoverCell(
    item: RingtoneCharacterItem,
    selected: Boolean,
    playing: Boolean,
    isVoice: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val stateLabel =
        listOfNotNull(
            if (selected) stringResource(R.string.picker_state_selected) else null,
            if (playing) stringResource(R.string.picker_state_playing) else null,
            if (isVoice) stringResource(R.string.ringtone_picker_voice_badge) else null,
        ).joinToString()
    val versions = item.variants.size
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier =
            modifier
                .padding(vertical = 8.dp)
                .clickable(
                    onClickLabel = stringResource(R.string.ringtone_picker_preview_label),
                    role = Role.Button,
                    onClick = onTap,
                ).semantics(mergeDescendants = true) {
                    this.selected = selected
                    if (stateLabel.isNotEmpty()) stateDescription = stateLabel
                },
    ) {
        RecordCover(
            character = item.character,
            size = CELL_COVER,
            pulledOut = selected,
            spinning = playing,
            sleeveColor =
                when {
                    selected -> colors.tertiaryContainer
                    isVoice -> colors.primaryContainer
                    else -> colors.surfaceContainerHigh
                },
        )
        Text(
            text = item.character.name,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text =
                if (isVoice) {
                    stringResource(R.string.ringtone_picker_voice_badge)
                } else {
                    pluralStringResource(R.plurals.ringtone_picker_versions_count, versions, versions)
                },
            style = MaterialTheme.typography.labelSmall,
            color = if (isVoice) colors.primary else colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@Composable
private fun SaveBar(
    state: RingtonePickerState,
    onSave: () -> Unit,
    onReroll: () -> Unit,
) {
    val name = state.selectedCharacter?.character?.name
    BottomActionBar {
        if (state.rolled) RerollButton(onReroll)
        Button(onClick = onSave, enabled = state.canSave, shapes = ButtonDefaults.shapes()) {
            Text(
                when {
                    name == null -> stringResource(R.string.ringtone_picker_pick_first)
                    !state.canSave && state.selection == state.saved -> stringResource(R.string.ringtone_picker_current)
                    else -> stringResource(R.string.ringtone_use, name)
                },
            )
        }
    }
}

@Composable
private fun SectionTitle(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp).semantics { heading() },
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/** How long the picker stays after saving: the burst (0.65 s) and the call play out before it closes. */
private const val ASSIGN_LINGER_MS = 900L
private val CELL_MIN_WIDTH = 120.dp
private val CELL_COVER = 76.dp
private val HEADER_COVER = 104.dp
private val BURST_SIZE = 260.dp

internal val RingtoneMode.label: Int
    get() =
        when (this) {
            RingtoneMode.PACK -> R.string.ringtone_rule_pack
            RingtoneMode.RANDOM -> R.string.ringtone_rule_random
            RingtoneMode.TIME -> R.string.ringtone_rule_time
            RingtoneMode.SEASON -> R.string.ringtone_rule_season
            RingtoneMode.FIXED -> R.string.ringtone_rule_fixed
        }

internal val RingtoneMode.body: Int
    get() =
        when (this) {
            RingtoneMode.PACK -> R.string.ringtone_rule_pack_body
            RingtoneMode.RANDOM -> R.string.ringtone_rule_random_body
            RingtoneMode.TIME -> R.string.ringtone_rule_time_body
            RingtoneMode.SEASON -> R.string.ringtone_rule_season_body
            RingtoneMode.FIXED -> R.string.ringtone_rule_fixed_body
        }

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun RingtonePickerPreview() {
    CucutoTheme(dynamicColor = false) {
        RingtonePickerContent(state = previewRingtonePickerState(), beat = null, actions = RingtonePickerActions())
    }
}

/** Made-up characters and chat for previews and tests (the files do not exist: a preview just fails quietly). */
internal fun previewRingtonePickerState(): RingtonePickerState {
    fun character(
        packId: String,
        id: String,
        name: String,
        seed: Long,
    ) = VoiceCharacter(packId, id, name, null, null, seed.toInt(), emptyList(), Haptic.Keep)
    val frog =
        RingtoneCharacterItem(
            character("app.mauri.demo.critters", "frog", "Bleep the frog", 0xFF4CAF50),
            listOf(
                RingtoneVariantItem(
                    RingtoneVariant("frog/ringtones/day.ogg", "Pond song (day)", listOf("day")),
                    File("day.ogg"),
                ),
                RingtoneVariantItem(
                    RingtoneVariant("frog/ringtones/night.ogg", "Pond song (night)", listOf("night")),
                    File("night.ogg"),
                ),
            ),
        )
    val kettle =
        RingtoneCharacterItem(
            character("app.mauri.demo.kitchen", "kettle", "Tootle the kettle", 0xFFE57373),
            listOf(RingtoneVariantItem(RingtoneVariant("kettle/ringtones/reel.ogg", "Kettle reel"), File("reel.ogg"))),
        )
    val key = ChatKey("com.whatsapp", "100000000000003@lid")
    return RingtonePickerState(
        loading = false,
        chatKey = key,
        chat = Chat(key, 3, "Iris", false, frog.voice, 0L, 1),
        packs =
            listOf(
                RingtonePackItem("app.mauri.demo.critters", "Chiptune critters", listOf(frog)),
                RingtonePackItem("app.mauri.demo.kitchen", "Kitchen orchestra", listOf(kettle)),
            ),
        saved = RingtoneSelection(frog.voice, followsVoice = true, mode = RingtoneMode.TIME),
    )
}
