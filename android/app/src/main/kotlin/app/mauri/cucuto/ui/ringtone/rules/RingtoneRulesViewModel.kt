package app.mauri.cucuto.ui.ringtone.rules

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.cucuto.core.data.chats.Chat
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.chats.ChatVoice
import app.mauri.cucuto.core.data.db.RingtoneMode
import app.mauri.cucuto.core.data.db.RingtoneRuleEntry
import app.mauri.cucuto.core.data.di.WallClock
import app.mauri.cucuto.core.data.voices.VoiceCharacter
import app.mauri.cucuto.core.ringtone.variants.RingtoneVariant
import app.mauri.cucuto.core.ringtone.variants.RuleContext
import app.mauri.cucuto.core.ringtone.variants.VariantRules
import app.mauri.cucuto.ui.ringtone.PhoneRingtoneViewModel
import app.mauri.cucuto.ui.ringtone.PreviewPlaying
import app.mauri.cucuto.ui.ringtone.RingtonePickerViewModel
import app.mauri.cucuto.ui.ringtone.RingtonePlayback
import app.mauri.cucuto.ui.ringtone.RingtonePreview
import app.mauri.cucuto.ui.ringtone.RingtoneSelection
import app.mauri.cucuto.ui.ringtone.RingtoneVariantItem
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

/** What the user changed on the rules screen, null fields meaning "as stored". */
@Immutable
data class RulesEdit(
    val mode: RingtoneMode? = null,
    val variant: String? = null,
    val draft: RulesDraft? = null,
)

/**
 * What the ringtone rules screen (ADR 0074) shows.
 *
 * @property entries The character's ringtone entries, `pack.json` order: the pack's rules.
 * @property saved What rings for the chat: its stored ringtone, or its voice's own while it has none (null: nothing).
 * @property edit The changes not saved yet.
 * @property now The moment "Ringing now" is computed for (day from 07:00 to 19:00 here; the sync uses the place).
 */
@Immutable
data class RingtoneRulesState(
    val voice: ChatVoice,
    val chatKey: ChatKey? = null,
    val loading: Boolean = true,
    val character: VoiceCharacter? = null,
    val packName: String? = null,
    val entries: List<RingtoneVariantItem> = emptyList(),
    val chat: Chat? = null,
    val saved: RingtoneSelection? = null,
    val edit: RulesEdit = RulesEdit(),
    val preview: PreviewPlaying<String>? = null,
    val saving: Boolean = false,
    val done: Boolean = false,
    val failed: Boolean = false,
    /** Counts the resets, so each one shows its message once. */
    val resets: Int = 0,
    val now: LocalDateTime = LocalDateTime.MIN,
) {
    val variants: List<RingtoneVariant> get() = entries.map { it.variant }

    /** The pack's rules as the screen reads them. */
    val packView: RulesView get() = RulesView.of(variants)

    /** One version per file: what "One version" and the previews offer. */
    val versions: List<RingtoneVariantItem> get() = entries.distinctBy { it.file }

    val isGroup: Boolean get() = chat?.isGroup ?: (chatKey?.isGroupKey == true)

    /** This character rings for the chat, so its rules can be changed for it. */
    val editable: Boolean get() = chatKey != null && !isGroup && saved?.voice == voice && entries.isNotEmpty()

    /** A chat whose ringtone is another character (or none): the screen only shows the rules. */
    val otherCharacter: Boolean get() = chatKey != null && !isGroup && !editable && character != null

    /** The rules worth offering: the picker's ([PhoneRingtoneViewModel.modesFor]) and "Your own rules". */
    val modes: List<RingtoneMode>
        get() {
            val modes = PhoneRingtoneViewModel.modesFor(variants)
            if (modes.isEmpty()) return modes
            return modes.toMutableList().apply { add(indexOf(RingtoneMode.PACK) + 1, RingtoneMode.CUSTOM) }
        }

    private val savedMode: RingtoneMode get() = saved?.mode?.takeIf { it in modes } ?: RingtoneMode.PACK

    val mode: RingtoneMode get() = edit.mode ?: savedMode

    val variant: String?
        get() = edit.variant ?: saved?.variant?.takeIf { v -> versions.any { it.file == v } } ?: versions.firstOrNull()?.file

    /** "Your own rules" as edited, else as stored, else a copy of the pack's. */
    val draft: RulesDraft
        get() =
            edit.draft ?: saved
                ?.takeIf { it.mode == RingtoneMode.CUSTOM && it.rules.isNotEmpty() }
                ?.let { RulesDraft.fromSaved(variants, it.rules) }
                ?: RulesDraft.fromPack(variants)

    /** What saving stores: own rules equal to the pack's are the pack's rules, so pack updates keep applying. */
    val selection: RingtoneSelection?
        get() {
            val base = saved ?: return null
            val single = modes.isEmpty()
            return when {
                single -> base.copy(mode = RingtoneMode.PACK, variant = null, rules = emptyList())
                mode == RingtoneMode.FIXED -> base.copy(mode = mode, variant = variant, rules = emptyList())
                mode == RingtoneMode.CUSTOM -> {
                    val rules = draft.rules
                    if (rules.isEmpty() || RulesDraft.isPack(variants, rules)) {
                        base.copy(mode = RingtoneMode.PACK, variant = null, rules = emptyList())
                    } else {
                        base.copy(mode = mode, variant = null, rules = rules)
                    }
                }

                else -> base.copy(mode = mode, variant = null, rules = emptyList())
            }
        }

    private val storedSelection: RingtoneSelection?
        get() =
            saved?.let {
                when (it.mode) {
                    RingtoneMode.FIXED -> it.copy(rules = emptyList())
                    RingtoneMode.CUSTOM -> it.copy(variant = null)
                    else -> it.copy(variant = null, rules = emptyList())
                }
            }

    val canSave: Boolean get() = editable && !saving && !done && selection != storedSelection

    val canReset: Boolean get() = editable && !saving && !done && saved?.mode != RingtoneMode.PACK

    /** The version the chosen rule rings with now, or null for "Random per call" (a new one after every call). */
    val ringingNow: RingtoneVariant?
        get() {
            if (variants.isEmpty()) return null
            val (mode, fixed, rules) =
                if (editable) {
                    Triple(mode, variant, if (mode == RingtoneMode.CUSTOM) draft.rules else emptyList())
                } else {
                    Triple(RingtoneMode.PACK, null, emptyList<RingtoneRuleEntry>())
                }
            if (mode == RingtoneMode.RANDOM) return null
            return VariantRules.pick(variants, mode, fixed, RuleContext(now), null, false, Random(0), rules)
        }
}

/**
 * The ringtone rules screen (#288, ADR 0074) for one character: its pack's rules in plain words with every version
 * playable and, for a chat it rings for, the rule that picks the version, including "Your own rules", an edited copy
 * of the pack's. Saving goes through the picker's write ([RingtoneRulesSource.save]).
 */
@HiltViewModel(assistedFactory = RingtoneRulesViewModel.Factory::class)
class RingtoneRulesViewModel
    @AssistedInject
    constructor(
        @Assisted private val voice: ChatVoice,
        @Assisted private val chatKey: ChatKey?,
        private val source: RingtoneRulesSource,
        preview: RingtonePreview,
        private val clock: WallClock,
    ) : ViewModel() {
        private val local = MutableStateFlow(RingtoneRulesState(voice = voice, chatKey = chatKey, now = now()))
        private val playback = RingtonePlayback<String>(viewModelScope, preview)

        val state: StateFlow<RingtoneRulesState> =
            combine(source.observe(voice, chatKey), local, playback.playing) { data, current, playing ->
                current.copy(
                    loading = false,
                    character = data.character,
                    packName = data.packName,
                    entries = data.entries,
                    chat = data.chat,
                    saved = ringing(data),
                    preview = playing,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), local.value)

        fun setMode(mode: RingtoneMode) = edit { it.copy(mode = mode) }

        /** "One version": always [file]. */
        fun setVariant(file: String) = edit { it.copy(mode = RingtoneMode.FIXED, variant = file) }

        /** Turns version [index] of "Your own rules" on or off (one always stays on). */
        fun toggleRow(
            index: Int,
            on: Boolean,
        ) = editDraft { it.toggle(index, on) }

        fun chooseWhen(
            index: Int,
            choice: WhenChoice,
        ) = editDraft { it.choose(index, choice) }

        /** The version that rings when none of the conditions holds. */
        fun setFallback(index: Int) = editDraft { it.fallbackTo(index) }

        /** Rings [file] like the call, or pauses and resumes it when it is the one ringing. */
        fun togglePreview(file: String) {
            val sound = state.value.versions.firstOrNull { it.file == file }?.sound() ?: return
            playback.toggle(file, sound)
        }

        fun stopPreview() = playback.stop()

        /** Whether saving should first ask for the phone permission ("Random per call" re-rolls after each call). */
        fun needsPhonePermission(): Boolean =
            state.value.selection?.mode == RingtoneMode.RANDOM && !source.canReadPhoneState()

        /** Saves the edited rule; the screen closes once [RingtoneRulesState.done]. Returns false with nothing to save. */
        fun save(): Boolean {
            val current = state.value
            val selection = current.selection ?: return false
            if (!current.canSave) return false
            write(current, selection) { it.copy(done = true) }
            return true
        }

        /** Back to the pack's rules, saved at once; the screen stays. */
        fun reset(): Boolean {
            val current = state.value
            val saved = current.saved ?: return false
            if (!current.canReset) return false
            val selection = saved.copy(mode = RingtoneMode.PACK, variant = null, rules = emptyList())
            write(current, selection) { it.copy(edit = RulesEdit(), resets = it.resets + 1) }
            return true
        }

        private fun write(
            current: RingtoneRulesState,
            selection: RingtoneSelection,
            after: (RingtoneRulesState) -> RingtoneRulesState,
        ) {
            val chat = chatKey ?: return
            stopPreview()
            local.update { it.copy(saving = true, failed = false) }
            viewModelScope.launch {
                try {
                    // Following the voice stores no character, as in the picker (ADR 0063).
                    val character = selection.voice.takeUnless { selection.followsVoice && it == current.chat?.voice }
                    source.save(
                        chat,
                        current.chat?.contactId,
                        character,
                        selection.followsVoice,
                        selection.mode,
                        selection.variant.takeIf { selection.mode == RingtoneMode.FIXED },
                        selection.rules.takeIf { selection.mode == RingtoneMode.CUSTOM }.orEmpty(),
                    )
                    local.update { after(it.copy(saving = false)) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    local.update { it.copy(saving = false, failed = true) }
                }
            }
        }

        private fun edit(change: (RulesEdit) -> RulesEdit) {
            if (!state.value.editable || state.value.saving || state.value.done) return
            local.update { it.copy(edit = change(it.edit), failed = false, now = now()) }
        }

        private fun editDraft(change: (RulesDraft) -> RulesDraft) {
            val current = state.value
            edit { it.copy(mode = RingtoneMode.CUSTOM, draft = change(current.draft)) }
        }

        private fun now(): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(clock.now()), ZoneId.systemDefault())

        /** What rings for the chat: its stored ringtone, else its voice's own (which then rings by the pack's rules). */
        private fun ringing(data: RingtoneRulesData): RingtoneSelection? {
            val chat = data.chat
            RingtonePickerViewModel.saved(data.saved, chat?.voice)?.let { return it }
            val voice = chat?.voice ?: return null
            return if (voice == this.voice && data.entries.isNotEmpty()) RingtoneSelection(voice, followsVoice = true) else null
        }

        override fun onCleared() {
            playback.stop()
        }

        @AssistedFactory
        interface Factory {
            fun create(
                voice: ChatVoice,
                chatKey: ChatKey?,
            ): RingtoneRulesViewModel
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
