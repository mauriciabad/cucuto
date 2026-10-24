package app.mauri.cucuto.ui.ringtone.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mauri.cucuto.R
import app.mauri.cucuto.core.data.chats.Chat
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.chats.ChatVoice
import app.mauri.cucuto.core.data.db.RingtoneMode
import app.mauri.cucuto.core.data.voices.VoiceCharacter
import app.mauri.cucuto.core.ringtone.variants.RingtoneVariant
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.packspec.When
import app.mauri.cucuto.ui.components.BottomActionBar
import app.mauri.cucuto.ui.components.PushedScreen
import app.mauri.cucuto.ui.components.adaptiveMaxLines
import app.mauri.cucuto.ui.feedback.Feedback
import app.mauri.cucuto.ui.feedback.LocalFeedback
import app.mauri.cucuto.ui.ringtone.RecordCover
import app.mauri.cucuto.ui.ringtone.RingtonePlayButton
import app.mauri.cucuto.ui.ringtone.RingtoneSelection
import app.mauri.cucuto.ui.ringtone.RingtoneVariantItem
import app.mauri.cucuto.ui.ringtone.body
import app.mauri.cucuto.ui.ringtone.label
import app.mauri.cucuto.ui.ringtone.rememberPhonePermissionFlow
import app.mauri.cucuto.ui.theme.CharacterTheme
import app.mauri.cucuto.ui.theme.CucutoTheme
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.LocalDateTime

/** What the rules screen can ask for; [RingtoneRulesScreen] wires it to its view model. */
data class RingtoneRulesActions(
    val onBack: () -> Unit = {},
    val onTogglePreview: (String) -> Unit = {},
    val onStopPreview: () -> Unit = {},
    val onMode: (RingtoneMode) -> Unit = {},
    val onVariant: (String) -> Unit = {},
    val onToggleRow: (Int, Boolean) -> Unit = { _, _ -> },
    val onWhen: (Int, WhenChoice) -> Unit = { _, _ -> },
    val onFallback: (Int) -> Unit = {},
    val onSave: () -> Unit = {},
    val onReset: () -> Unit = {},
)

/**
 * The ringtone rules screen (#288, ADR 0074) of [voice]'s character: which version rings when, in plain words, each
 * version playable like the call; with a [chat] it rings for, the rule for that chat, "Your own rules" included, and
 * "Reset to the pack's rules". Saving closes it ([onDone]).
 */
@Composable
fun RingtoneRulesScreen(
    voice: ChatVoice,
    chat: ChatKey?,
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel =
        hiltViewModel<RingtoneRulesViewModel, RingtoneRulesViewModel.Factory>(
            creationCallback = { factory -> factory.create(voice, chat) },
        )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val feedback = LocalFeedback.current
    val done by rememberUpdatedState(onDone)
    // A preview never plays on behind another app or a locked screen.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.stopPreview() }
    LaunchedEffect(state.done) {
        if (state.done) {
            feedback.fire(Feedback.Success)
            done()
        }
    }
    LaunchedEffect(state.failed) { if (state.failed) feedback.fire(Feedback.Error) }

    fun save() {
        viewModel.save()
    }
    // "Random per call" re-rolls when a call ends, which needs the phone permission: ask first, save either way.
    val askPhone = rememberPhonePermissionFlow { save() }

    RingtoneRulesContent(
        state = state,
        actions =
            RingtoneRulesActions(
                onBack = onBack,
                onTogglePreview = { file ->
                    feedback.fire(Feedback.Tap)
                    viewModel.togglePreview(file)
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
                onToggleRow = { index, on ->
                    feedback.fire(if (on) Feedback.ToggleOn else Feedback.ToggleOff)
                    viewModel.toggleRow(index, on)
                },
                onWhen = { index, choice ->
                    feedback.fire(Feedback.SegmentTick)
                    viewModel.chooseWhen(index, choice)
                },
                onFallback = { index ->
                    feedback.fire(Feedback.SegmentTick)
                    viewModel.setFallback(index)
                },
                onSave = { if (viewModel.needsPhonePermission()) askPhone() else save() },
                onReset = {
                    if (viewModel.reset()) feedback.fire(Feedback.Confirm)
                },
            ),
        modifier = modifier,
    )
}

/** Stateless rules screen, for previews, screenshots and UI tests. */
@Composable
fun RingtoneRulesContent(
    state: RingtoneRulesState,
    actions: RingtoneRulesActions,
    modifier: Modifier = Modifier,
) {
    val text = rememberRuleText()
    val snackbar = remember { SnackbarHostState() }
    val failedMessage = stringResource(R.string.ringtone_rules_failed)
    val resetMessage = stringResource(R.string.ringtone_rules_reset_done)
    LaunchedEffect(state.failed) { if (state.failed) snackbar.showSnackbar(failedMessage) }
    LaunchedEffect(state.resets) { if (state.resets > 0) snackbar.showSnackbar(resetMessage) }
    val character = state.character
    CharacterTheme(seed = character?.seedColor?.let { Color(it) }) {
        PushedScreen(
            title = stringResource(R.string.ringtone_rules_title),
            subtitle = character?.name,
            onBack = actions.onBack,
            modifier = modifier,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = { if (state.editable) SaveBar(state, actions) },
        ) { padding ->
            if (state.loading) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    val loading = stringResource(R.string.a11y_loading)
                    LoadingIndicator(Modifier.size(56.dp).semantics { contentDescription = loading })
                }
                return@PushedScreen
            }
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .consumeWindowInsets(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(padding)
                        .padding(horizontal = 24.dp)
                        .padding(top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (character == null || state.entries.isEmpty()) {
                    Hint(stringResource(R.string.ringtone_rules_missing))
                    return@Column
                }
                Header(state, character, text)
                PackRules(state, text, actions)
                val chatName = state.chat?.displayName ?: stringResource(R.string.chat_unnamed)
                when {
                    state.chatKey == null -> {
                        Hint(stringResource(R.string.ringtone_rules_view_only))
                    }

                    state.otherCharacter -> {
                        Hint(stringResource(R.string.ringtone_rules_other_character, character.name, chatName))
                    }

                    state.editable -> {
                        ContactRules(state, chatName, text, actions)
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(
    state: RingtoneRulesState,
    character: VoiceCharacter,
    text: RuleText,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        RecordCover(
            character = character,
            size = COVER,
            pulledOut = true,
            spinning = state.preview?.paused == false,
            sleeveColor = MaterialTheme.colorScheme.primaryContainer,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                character.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = adaptiveMaxLines(2),
                overflow = TextOverflow.Ellipsis,
            )
            state.packName?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                state.ringingNow?.let { stringResource(R.string.ringtone_rules_now, text.name(it)) }
                    ?: stringResource(R.string.ringtone_rule_random_body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** The pack's rules: a summary sentence, each entry with its play button and when it rings, and the fallback. */
@Composable
private fun PackRules(
    state: RingtoneRulesState,
    text: RuleText,
    actions: RingtoneRulesActions,
) {
    val view = state.packView
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(stringResource(R.string.ringtone_rules_pack_section))
        Text(text.summary(view), style = MaterialTheme.typography.titleMedium)
        Column {
            view.lines.forEachIndexed { index, line ->
                RuleLineRow(line, view, state, text, actions, enabled = state.entries.getOrNull(index)?.audio != null)
            }
        }
        text.fallback(view)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (mentionsDay(state.variants.map { it.condition })) Hint(stringResource(R.string.ringtone_rules_day_note))
    }
}

/** One entry of the pack's rules: its play button, its name and when it rings. */
@Composable
private fun RuleLineRow(
    line: RuleLine,
    view: RulesView,
    state: RingtoneRulesState,
    text: RuleText,
    actions: RingtoneRulesActions,
    enabled: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp),
    ) {
        RingtonePlayButton(
            preview = state.preview?.takeIf { it.key == line.variant.file },
            name = text.name(line.variant),
            onToggle = { actions.onTogglePreview(line.variant.file) },
            onStop = actions.onStopPreview,
            enabled = enabled,
        )
        Column(Modifier.weight(1f)) {
            Text(text.name(line.variant), style = MaterialTheme.typography.bodyLarge)
            Text(
                text.line(line, view),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The rule for this chat: a radio list, "One version"'s versions and "Your own rules"' editor under the chosen one. */
@Composable
private fun ContactRules(
    state: RingtoneRulesState,
    chatName: String,
    text: RuleText,
    actions: RingtoneRulesActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionTitle(stringResource(R.string.ringtone_rules_contact_section, chatName))
        if (state.modes.isEmpty()) {
            Hint(stringResource(R.string.ringtone_rules_one_version))
        } else {
            Column(Modifier.selectableGroup()) {
                for (mode in state.modes) {
                    val selected = state.mode == mode
                    ModeRow(mode, selected) { if (!selected) actions.onMode(mode) }
                    if (selected && mode == RingtoneMode.FIXED) Versions(state, text, actions)
                    if (selected && mode == RingtoneMode.CUSTOM) OwnRules(state, text, actions)
                }
            }
        }
    }
}

/** One rule of the radio list, with its one-line explanation. */
@Composable
private fun ModeRow(
    mode: RingtoneMode,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                .padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(stringResource(mode.label), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(mode.body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Versions(
    state: RingtoneRulesState,
    text: RuleText,
    actions: RingtoneRulesActions,
) {
    Column(Modifier.padding(start = 36.dp).selectableGroup()) {
        for (item in state.versions) {
            val chosen = state.variant == item.file
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(selected = chosen, role = Role.RadioButton) { actions.onVariant(item.file) },
            ) {
                RadioButton(selected = chosen, onClick = null)
                Text(text.name(item.variant), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** "Your own rules": each version on or off with its "When" menu, the fallback, and the rules read back. */
@Composable
private fun OwnRules(
    state: RingtoneRulesState,
    text: RuleText,
    actions: RingtoneRulesActions,
) {
    val draft = state.draft
    Column(Modifier.padding(start = 36.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        draft.rows.forEachIndexed { index, row ->
            val name = text.name(row.variant)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                val switchLabel = stringResource(R.string.ringtone_rules_use_version, name)
                Switch(
                    checked = row.on,
                    onCheckedChange = { actions.onToggleRow(index, it) },
                    enabled = !row.on || draft.onCount > 1,
                    modifier = Modifier.semantics { contentDescription = switchLabel },
                )
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                    if (row.on) {
                        Menu(
                            label = text.choice(row.choice, row.packCondition),
                            description = stringResource(R.string.ringtone_rules_when, name),
                            options = row.choices.map { it to text.choice(it, row.packCondition) },
                            selected = row.choice,
                            onSelect = { actions.onWhen(index, it) },
                        )
                    }
                }
            }
        }
        if (draft.onCount <= 1) Hint(stringResource(R.string.ringtone_rules_last_on))
        val onRows = draft.rows.withIndex().filter { it.value.on }
        if (onRows.size > 1) {
            Text(
                stringResource(R.string.ringtone_rules_fallback_label),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
            val fallback = draft.fallbackRow
            Menu(
                label = fallback?.let { text.name(it.variant) }.orEmpty(),
                description = stringResource(R.string.ringtone_rules_fallback_label),
                options = onRows.map { it.index to text.name(it.value.variant) },
                selected = draft.rows.indexOf(fallback),
                onSelect = actions.onFallback,
            )
        }
        Text(
            stringResource(R.string.ringtone_rules_yours, text.summary(RulesView.of(draft.variants))),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** A button that opens a menu of [options] and shows the chosen one. */
@Composable
private fun <T> Menu(
    label: String,
    description: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { open = true },
            shapes = ButtonDefaults.shapes(),
            contentPadding = ButtonDefaults.ContentPadding,
            modifier = Modifier.semantics { contentDescription = "$description: $label" },
        ) {
            Text(label, maxLines = adaptiveMaxLines(2), overflow = TextOverflow.Ellipsis)
            Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((value, text) in options) {
                DropdownMenuItem(
                    text = { Text(text) },
                    // The check mark is drawn only: TalkBack hears "selected" (#226).
                    modifier = Modifier.semantics { this.selected = value == selected },
                    leadingIcon =
                        if (value == selected) {
                            ({ Icon(Icons.Rounded.Check, contentDescription = null) })
                        } else {
                            null
                        },
                    onClick = {
                        open = false
                        onSelect(value)
                    },
                )
            }
        }
    }
}

@Composable
private fun SaveBar(
    state: RingtoneRulesState,
    actions: RingtoneRulesActions,
) {
    BottomActionBar {
        if (state.canReset) {
            OutlinedButton(onClick = actions.onReset, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.ringtone_rules_reset))
            }
        }
        Button(onClick = actions.onSave, enabled = state.canSave, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.ringtone_rules_save))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun mentionsDay(conditions: List<When?>): Boolean =
    conditions.any { c -> (c?.time as? JsonPrimitive)?.content in setOf("day", "night") }

private val COVER = 88.dp

@Preview(showBackground = true, heightDp = 1100)
@Composable
private fun RingtoneRulesPreview() {
    CucutoTheme(dynamicColor = false) {
        RingtoneRulesContent(state = previewRingtoneRulesState(), actions = RingtoneRulesActions())
    }
}

/**
 * Made-up rules for previews and tests: a song with a live version by day, a radio version at night and a winter
 * version, which Iris's ringtone rings with by her own rules (the files do not exist: a preview just fails quietly).
 */
internal fun previewRingtoneRulesState(
    character: VoiceCharacter =
        VoiceCharacter(
            "app.mauri.demo.songs",
            "bubblegum",
            "Bubblegum K.K.",
            null,
            null,
            0xFFEC407A.toInt(),
            emptyList(),
            Haptic.Keep,
        ),
    files: (String) -> File? = { File(it) },
): RingtoneRulesState {
    fun entry(
        path: String,
        label: String,
        condition: When?,
    ) = RingtoneVariantItem(RingtoneVariant(path, label, condition = condition), files(path))
    val entries =
        listOf(
            entry("bubblegum/live.ogg", "Live", When(time = JsonPrimitive("day"))),
            entry("bubblegum/radio.ogg", "Radio", When(time = JsonPrimitive("night"))),
            entry(
                "bubblegum/winter.ogg",
                "Winter",
                When(time = JsonPrimitive("night"), season = JsonPrimitive("winter")),
            ),
        )
    val key = ChatKey("com.whatsapp", "100000000000003@lid")
    val variants = entries.map { it.variant }
    val draft =
        RulesDraft
            .fromPack(variants)
            .choose(0, WhenChoice.NIGHT)
            .choose(1, WhenChoice.DAY)
            .toggle(2, false)
    return RingtoneRulesState(
        voice = character.voice,
        chatKey = key,
        loading = false,
        character = character,
        packName = "Songs",
        entries = entries,
        chat = Chat(key, 3, "Iris", false, character.voice, 0L, 1),
        saved =
            RingtoneSelection(
                character.voice,
                followsVoice = true,
                mode = RingtoneMode.CUSTOM,
                rules = draft.rules,
            ),
        now = LocalDateTime.parse("2026-10-24T12:00"),
    )
}
