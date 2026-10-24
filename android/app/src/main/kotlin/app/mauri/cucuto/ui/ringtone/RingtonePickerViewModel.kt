package app.mauri.cucuto.ui.ringtone

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.cucuto.core.data.chats.Chat
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.chats.ChatVoice
import app.mauri.cucuto.core.data.db.PhoneRingtoneEntity
import app.mauri.cucuto.core.data.db.RingtoneMode
import app.mauri.cucuto.core.data.db.RingtoneRuleEntry
import app.mauri.cucuto.core.data.db.RingtoneRules
import app.mauri.cucuto.core.data.di.WallClock
import app.mauri.cucuto.core.data.settings.PickerFoldSettings
import app.mauri.cucuto.core.data.settings.PickerKind
import app.mauri.cucuto.core.ringtone.variants.RuleContext
import app.mauri.cucuto.core.ringtone.variants.VariantRules
import app.mauri.cucuto.picker.PackFolds
import app.mauri.cucuto.picker.PackSection
import app.mauri.cucuto.picker.SurprisePick
import app.mauri.cucuto.picker.SurpriseRoll
import app.mauri.cucuto.picker.packSections
import app.mauri.cucuto.ui.components.PackChip
import app.mauri.cucuto.ui.components.packChips
import app.mauri.cucuto.ui.components.selectedChip
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * A phone ringtone as the picker edits it: the character that rings ([voice]), whether it [followsVoice] (the user
 * never picked one: the message character, or the one kept while the message character has no ringtones, ADR 0063),
 * how the version is chosen, the version for [RingtoneMode.FIXED], and the contact's own [rules] for
 * [RingtoneMode.CUSTOM] (ADR 0074; created and edited only on the rules screen).
 */
@Immutable
data class RingtoneSelection(
    val voice: ChatVoice,
    val followsVoice: Boolean,
    val mode: RingtoneMode = RingtoneMode.PACK,
    val variant: String? = null,
    val rules: List<RingtoneRuleEntry> = emptyList(),
)

/** The version being previewed (ringing like the call, from its start, until stopped: [RingTimeline]). */
@Immutable
data class PlayingVariant(
    val voice: ChatVoice,
    val file: String,
)

/**
 * What the ringtone picker (#53) shows.
 *
 * @property saved The phone ringtone stored for the chat, null when it has none.
 * @property edited The user's choice on this screen, null until they change something.
 * @property assigned Set once the choice is saved: the screen celebrates, then closes.
 * @property packs Installed packs with characters that have ringtones (only those: ADR 0063).
 * @property packFilter The pack chip chosen, or null for all packs.
 * @property playing The version ringing or paused; [preview] has its progress and whether it is paused.
 * @property query The search field's text (ADR 0072).
 * @property folds Which pack sections are folded (ADR 0072).
 * @property roll The last "Surprise me" pick (ADR 0072).
 */
@Immutable
data class RingtonePickerState(
    val loading: Boolean = true,
    val chatKey: ChatKey? = null,
    val chat: Chat? = null,
    val packs: List<RingtonePackItem> = emptyList(),
    val saved: RingtoneSelection? = null,
    val edited: RingtoneSelection? = null,
    val playing: PlayingVariant? = null,
    val preview: PreviewPlaying<PlayingVariant>? = null,
    val assigned: RingtoneSelection? = null,
    val failed: Boolean = false,
    val packFilter: String? = null,
    val query: String = "",
    val folds: PackFolds = PackFolds(),
    val roll: SurpriseRoll<ChatVoice>? = null,
) {
    val contactId: Long? get() = chat?.contactId

    /** Phone calls come from people: a group has no phone ringtone (#52). */
    val isGroup: Boolean get() = chat?.isGroup ?: (chatKey?.isGroupKey == true)

    /** One chip per pack with ringtone characters, with their count; empty with fewer than two such packs. */
    val chips: List<PackChip> get() = packChips(packs, { it.packId }, { it.name }, { it.characters.size })

    /** The packs the grid shows: the chosen chip's, or all. */
    val visiblePacks: List<RingtonePackItem>
        get() = selectedChip(chips, packFilter)?.let { id -> packs.filter { it.packId == id } } ?: packs

    /** The chat's message character. */
    val voice: ChatVoice? get() = chat?.voice

    /** The pack sections the grid shows: chip, search and folds applied; the ringing character's pack unfolded. */
    val sections: List<PackSection<RingtoneCharacterItem>>
        get() =
            packSections(
                packs = packs,
                id = { it.packId },
                name = { it.name },
                characters = { it.characters },
                characterName = { it.character.name },
                chipPack = selectedChip(chips, packFilter),
                query = query,
                folds = folds,
                currentPack = (saved?.voice ?: voice)?.packId,
            )

    /** The selection came from "Surprise me": the save bar offers to re-roll it. */
    val rolled: Boolean get() = roll != null && roll.pick == selection?.voice && assigned == null

    /** What the screen shows as chosen: the edit, else what is stored, else the voice's own ringtone. */
    val selection: RingtoneSelection?
        get() =
            edited ?: saved
                ?: voice?.takeIf { character(it) != null }?.let { RingtoneSelection(it, followsVoice = true) }

    val selectedCharacter: RingtoneCharacterItem? get() = character(selection?.voice)

    /** The rules worth offering for the selected character's versions ([PhoneRingtoneViewModel.modesFor]). */
    val modes: List<RingtoneMode>
        get() = selectedCharacter?.let(::modesFor).orEmpty()

    /**
     * The rules offered for [item]: those its versions make sense for, plus "Your own rules" while they are what is
     * stored for it (they are created and edited on the rules screen, ADR 0074).
     */
    fun modesFor(item: RingtoneCharacterItem): List<RingtoneMode> {
        val modes = PhoneRingtoneViewModel.modesFor(item.variants.map { it.variant })
        val own = saved?.takeIf { it.voice == item.voice && it.mode == RingtoneMode.CUSTOM && it.rules.isNotEmpty() }
        if (own == null || modes.isEmpty()) return modes
        return modes.toMutableList().apply { add(indexOf(RingtoneMode.PACK) + 1, RingtoneMode.CUSTOM) }
    }

    /**
     * Something to save: a selected character with ringtones, different from what is stored. A chat without a contact
     * gets one when it saves (ADR 0063), so a ringtone can come before a voice.
     */
    val canSave: Boolean
        get() = chatKey != null && !isGroup && selectedCharacter != null && assigned == null && selection != saved

    fun character(voice: ChatVoice?): RingtoneCharacterItem? =
        voice?.let { v ->
            packs.firstOrNull { it.packId == v.packId }?.characters?.firstOrNull { it.voice == v }
        }
}

/**
 * The ringtone picker (#53, brief "Ringtones"): every installed character with ringtones as an album cover, filtered by
 * pack. A tap picks it and plays the version its rule would ring with now (the record spins); each version plays on
 * its own too, from the start (#246). The user picks a character and a rule, and saves. The message voice and the
 * ringtone are independent (ADR 0063): the message character keeps following the voice, the character kept while the
 * voice has no ringtones keeps following too, and any other character is the user's own pick.
 */
@HiltViewModel(assistedFactory = RingtonePickerViewModel.Factory::class)
class RingtonePickerViewModel
    @AssistedInject
    constructor(
        @Assisted private val chatKey: ChatKey,
        private val source: RingtonePickerSource,
        private val preview: RingtonePreview,
        private val clock: WallClock,
        private val foldSettings: PickerFoldSettings,
    ) : ViewModel() {
        private val interaction = MutableStateFlow(RingtonePickerState(chatKey = chatKey))
        private val playback = RingtonePlayback<PlayingVariant>(viewModelScope, preview)
        private var lastSaved: PhoneRingtoneEntity? = null

        // A save elsewhere (the rules screen on top, ADR 0074) replaces an unsaved choice made here before it.
        private val data =
            source.observe(chatKey).onEach { data ->
                val before = lastSaved
                lastSaved = data.saved
                if (before != null && data.saved != before && interaction.value.assigned == null) {
                    interaction.update { it.copy(edited = null) }
                }
            }

        val state: StateFlow<RingtonePickerState> =
            combine(
                data,
                foldSettings.observe(PickerKind.Ringtone),
                interaction,
                playback.playing,
            ) { data, remembered, local, playing ->
                local.copy(
                    loading = false,
                    chat = data.chat,
                    packs = data.packs,
                    saved = saved(data.saved, data.chat?.voice),
                    playing = playing?.key,
                    preview = playing,
                    folds = local.folds.copy(remembered = remembered),
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), interaction.value)

        /** Shows only [packId]'s characters, or every pack for null. */
        fun filterPack(packId: String?) {
            interaction.update { it.copy(packFilter = packId) }
        }

        /**
         * A tap on [item]'s cover: picks it (keeping the rule when it still applies) and previews the version that rule
         * picks now. A tap on the cover that is playing stops it.
         */
        fun select(item: RingtoneCharacterItem) {
            val current = state.value
            if (current.assigned != null) return
            if (current.selection?.voice == item.voice && current.playing?.voice == item.voice) {
                stopPreview()
                return
            }
            choose(current, item)
        }

        /** The search field's text: finds covers in folded packs too (ADR 0072). */
        fun search(query: String) {
            interaction.update { it.copy(query = query) }
        }

        /** Folds or unfolds [packId]'s section, and remembers it for next time (ADR 0072). */
        fun toggleFold(packId: String) {
            val section = state.value.sections.firstOrNull { it.packId == packId && it.foldable } ?: return
            val expanded = !section.expanded
            interaction.update { it.copy(folds = it.folds.with(packId, expanded)) }
            viewModelScope.launch {
                try {
                    foldSettings.remember(PickerKind.Ringtone, packId, expanded)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Only the memory for next time is lost; the section still folds now.
                }
            }
        }

        /**
         * "Surprise me" (ADR 0072, #290): picks a random character with ringtones from [packId], or from every pack
         * for null, never the one selected now ([SurprisePick]), and rings it like a tap on its cover. The pick becomes
         * visible: the search clears, a chip hiding it goes back to "All" and its pack unfolds for this visit. Returns
         * the pick, or null when there is none.
         */
        fun surprise(packId: String?): RingtoneCharacterItem? {
            val current = state.value
            if (current.assigned != null) return null
            val pool = current.packs.filter { packId == null || it.packId == packId }.flatMap { it.characters }
            val previous = current.selection?.voice
            val pick = SurprisePick.pick(pool, pool.firstOrNull { it.voice == previous }) ?: return null
            val home = pick.voice.packId
            interaction.update {
                it.copy(
                    query = "",
                    packFilter = it.packFilter?.takeIf { chip -> chip == home },
                    folds = it.folds.with(home, expanded = true),
                    roll = SurpriseRoll(packId, pick.voice, (it.roll?.count ?: 0) + 1),
                )
            }
            choose(current, pick)
            return pick
        }

        /** Picks [item] (keeping the rule when it still applies) and rings the version that rule picks now. */
        private fun choose(
            current: RingtonePickerState,
            item: RingtoneCharacterItem,
        ) {
            val modes = current.modesFor(item)
            val previous = current.selection
            val mode = previous?.mode?.takeIf { it in modes } ?: RingtoneMode.PACK
            val variant =
                if (mode == RingtoneMode.FIXED) {
                    previous?.variant?.takeIf { item.variant(it) != null } ?: item.variants.first().file
                } else {
                    null
                }
            val rules =
                current.saved
                    ?.rules
                    .orEmpty()
                    .takeIf { mode == RingtoneMode.CUSTOM }
                    .orEmpty()
            val selection =
                RingtoneSelection(item.voice, followsVoice = follows(current, item.voice), mode, variant, rules)
            interaction.update { it.copy(edited = selection, failed = false) }
            play(item, previewVariant(item, mode, variant, rules))
        }

        /** Rings [file] of [item] like the call, or pauses and resumes it when it is the one ringing. */
        fun togglePreview(
            item: RingtoneCharacterItem,
            file: String,
        ) {
            val sound = item.variant(file)?.sound() ?: return
            playback.toggle(PlayingVariant(item.voice, file), sound)
        }

        fun setMode(mode: RingtoneMode) {
            val selection = state.value.selection ?: return
            val item = state.value.selectedCharacter ?: return
            if (state.value.assigned != null) return
            val variant =
                if (mode == RingtoneMode.FIXED) {
                    selection.variant?.takeIf { item.variant(it) != null } ?: item.variants.first().file
                } else {
                    null
                }
            // "Your own rules" are only offered while stored for this character: picking the chip again brings them back.
            val rules =
                state.value.saved
                    ?.rules
                    .orEmpty()
                    .takeIf { mode == RingtoneMode.CUSTOM }
                    .orEmpty()
            interaction.update {
                it.copy(edited = selection.copy(mode = mode, variant = variant, rules = rules), failed = false)
            }
        }

        /** Always rings with [file] ("One version"). */
        fun setVariant(file: String) {
            val selection = state.value.selection ?: return
            if (state.value.assigned != null || state.value.selectedCharacter?.variant(file) == null) return
            interaction.update {
                it.copy(
                    edited = selection.copy(mode = RingtoneMode.FIXED, variant = file, rules = emptyList()),
                    failed = false,
                )
            }
        }

        /** Whether saving should first ask for the phone permission ("Random per call" re-rolls after each call). */
        fun needsPhonePermission(): Boolean =
            state.value.selection?.mode == RingtoneMode.RANDOM && !source.canReadPhoneState()

        /** Saves the selection. Returns false when there is nothing to save. */
        fun save(): Boolean {
            val current = state.value
            val selection = current.selection ?: return false
            if (!current.canSave) return false
            val single = current.modes.isEmpty()
            val mode = if (single) RingtoneMode.PACK else selection.mode
            val variant = if (mode == RingtoneMode.FIXED) selection.variant else null
            val rules = if (mode == RingtoneMode.CUSTOM) selection.rules else emptyList()
            stopPreview()
            interaction.update { it.copy(assigned = selection, failed = false) }
            viewModelScope.launch {
                try {
                    // Following the voice stores no character; a kept one (the voice has no ringtones) is stored.
                    val character = selection.voice.takeUnless { selection.followsVoice && it == current.voice }
                    source.save(chatKey, current.contactId, character, selection.followsVoice, mode, variant, rules)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    interaction.update { it.copy(assigned = null, failed = true) }
                }
            }
            return true
        }

        fun stopPreview() = playback.stop()

        private fun play(
            item: RingtoneCharacterItem,
            file: String,
        ) {
            val sound = item.variant(file)?.sound() ?: return
            playback.play(PlayingVariant(item.voice, file), sound)
        }

        /**
         * Whether picking [voice] keeps the ringtone following the message voice: it is the voice's own character,
         * or the one stored while following (kept because the voice has no ringtones). Anything else is the user's
         * own pick, which a later voice change leaves alone (ADR 0063).
         */
        private fun follows(
            current: RingtonePickerState,
            voice: ChatVoice,
        ): Boolean = voice == current.voice || (current.saved?.followsVoice == true && current.saved.voice == voice)

        /** The version [mode] would ring with now (day from 07:00 to 19:00 here; the sync uses the chosen place). */
        private fun previewVariant(
            item: RingtoneCharacterItem,
            mode: RingtoneMode,
            fixed: String?,
            rules: List<RingtoneRuleEntry> = emptyList(),
        ): String {
            val variants = item.variants.map { it.variant }
            if (mode == RingtoneMode.RANDOM) return variants.first().file
            val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(clock.now()), ZoneId.systemDefault())
            return VariantRules
                .pick(
                    variants,
                    mode,
                    fixed,
                    RuleContext(now),
                    current = null,
                    reroll = false,
                    random = Random(0),
                    rules = rules,
                )?.file ?: variants.first().file
        }

        override fun onCleared() {
            playback.stop()
        }

        @AssistedFactory
        interface Factory {
            fun create(chatKey: ChatKey): RingtonePickerViewModel
        }

        internal companion object {
            private const val STOP_TIMEOUT_MS = 5_000L

            /** The stored row as a selection: no character of its own means [voice]'s (ADR 0063). */
            fun saved(
                row: PhoneRingtoneEntity?,
                voice: ChatVoice?,
            ): RingtoneSelection? {
                row ?: return null
                val own =
                    if (row.packId != null &&
                        row.characterId != null
                    ) {
                        ChatVoice(row.packId!!, row.characterId!!)
                    } else {
                        null
                    }
                val character = own ?: voice ?: return null
                return RingtoneSelection(
                    voice = character,
                    followsVoice = row.followsVoice || own == null,
                    mode = row.mode,
                    variant = row.variant.takeIf { row.mode == RingtoneMode.FIXED },
                    rules = if (row.mode == RingtoneMode.CUSTOM) RingtoneRules.decode(row.rules) else emptyList(),
                )
            }
        }
    }
