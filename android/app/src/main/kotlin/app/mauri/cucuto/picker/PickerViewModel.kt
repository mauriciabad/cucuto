package app.mauri.cucuto.picker

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.cucuto.core.data.chats.Chat
import app.mauri.cucuto.core.data.chats.ChatDirectory
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.chats.ChatVoice
import app.mauri.cucuto.core.data.settings.PickerFoldSettings
import app.mauri.cucuto.core.data.settings.PickerKind
import app.mauri.cucuto.core.data.voices.CharacterDirectory
import app.mauri.cucuto.core.data.voices.VoiceCharacter
import app.mauri.cucuto.core.data.voices.VoicePack
import app.mauri.cucuto.sample.SampleChatPickerTarget
import app.mauri.cucuto.ui.components.PackChip
import app.mauri.cucuto.ui.components.packChips
import app.mauri.cucuto.ui.components.selectedChip
import app.mauri.cucuto.voice.VoicePreview
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the character picker shows.
 *
 * @property chatKey The chat being assigned, or null for the sample chat ([sample]).
 * @property chat That chat as the directory knows it (null while loading, for a chat not seen yet, or the sample chat).
 * @property sample The picker gives a voice to the sample chat (ADR 0075, #346), kept in its own settings.
 * @property sampleVoice The sample chat's voice (a real chat's is [Chat.voice]).
 * @property selected The character the first tap previewed; a second tap on it assigns.
 * @property speaking The character whose clip is playing (its sprite bounces).
 * @property assigned Set once an assignment is saved: the screen celebrates, then closes.
 * @property packs Installed packs with the characters that can speak for messages (with clips, [voicePacks]).
 * @property packFilter The pack chip chosen, or null for all packs.
 * @property query The search field's text (ADR 0072).
 * @property folds Which pack sections are folded (ADR 0072).
 * @property roll The last "Surprise me" pick (ADR 0072).
 */
@Immutable
data class PickerState(
    val loading: Boolean = true,
    val chatKey: ChatKey? = null,
    val chat: Chat? = null,
    val sample: Boolean = false,
    val sampleVoice: ChatVoice? = null,
    val packs: List<VoicePack> = emptyList(),
    val selected: ChatVoice? = null,
    val speaking: ChatVoice? = null,
    val assigned: ChatVoice? = null,
    val failed: Boolean = false,
    val packFilter: String? = null,
    val query: String = "",
    val folds: PackFolds = PackFolds(),
    val roll: SurpriseRoll<ChatVoice>? = null,
) {
    val canAssign: Boolean get() = chatKey != null || sample

    /** The voice stored for the chat (or the sample chat). */
    val voice: ChatVoice? get() = if (sample) sampleVoice else chat?.voice

    /** One chip per pack with message characters, with their count; empty with fewer than two such packs. */
    val chips: List<PackChip> get() = packChips(packs, { it.packId }, { it.name }, { it.characters.size })

    /** The packs the grid shows: the chosen chip's, or all. */
    val visiblePacks: List<VoicePack>
        get() = selectedChip(chips, packFilter)?.let { id -> packs.filter { it.packId == id } } ?: packs

    /** The pack sections the grid shows: chip, search and folds applied (ADR 0072). */
    val sections: List<PackSection<VoiceCharacter>>
        get() =
            packSections(
                packs = packs,
                id = { it.packId },
                name = { it.name },
                characters = { it.characters },
                characterName = { it.name },
                chipPack = selectedChip(chips, packFilter),
                query = query,
                folds = folds,
                currentPack = voice?.packId,
            )

    /** The selected character came from "Surprise me": the screen offers to re-roll or use it. */
    val rolled: Boolean get() = roll != null && roll.pick == selected && assigned == null

    /** The chat's voice: the one just assigned, else the stored one. */
    val currentVoice: ChatVoice? get() = assigned ?: voice

    fun character(voice: ChatVoice?): VoiceCharacter? =
        voice?.let { v ->
            packs.firstOrNull { it.packId == v.packId }?.characters?.firstOrNull { it.id == v.characterId }
        }
}

/**
 * The packs the character picker offers (ADR 0063, #245): only characters with notification clips, so a ringtone-only
 * character (a song) never shows up for messages; packs left with none are dropped.
 */
fun voicePacks(packs: List<VoicePack>): List<VoicePack> =
    packs.mapNotNull { pack ->
        val speaking = pack.characters.filter { it.forMessages }
        when {
            speaking.isEmpty() -> null
            speaking.size == pack.characters.size -> pack
            else -> pack.copy(characters = speaking)
        }
    }

/** What a tap on a character does: the first tap previews it, a second tap on the same character assigns it. */
enum class PickerTap { Preview, Assign }

/**
 * The character picker (brief, screen 3): tap previews (the sprite bounces, a clip plays, the haptic fires), a second
 * tap assigns. Assigning goes through [ChatDirectory], which republishes the routing table, so the chat's next
 * notification plays the new character. With no [chatKey], the picker gives a voice to the sample chat (ADR 0075,
 * #346) through [sampleChat] instead: it never reads or writes the chat directory then.
 */
@HiltViewModel(assistedFactory = PickerViewModel.Factory::class)
class PickerViewModel
    @AssistedInject
    constructor(
        @Assisted private val chatKey: ChatKey?,
        private val chats: ChatDirectory,
        private val sampleChat: SampleChatPickerTarget,
        characters: CharacterDirectory,
        private val preview: VoicePreview,
        private val foldSettings: PickerFoldSettings,
    ) : ViewModel() {
        private val interaction = MutableStateFlow(PickerState(chatKey = chatKey, sample = chatKey == null))
        private var previewJob: Job? = null

        /** The chat, or the sample chat's voice: only one of them is read. */
        private val target: Flow<Pair<Chat?, ChatVoice?>> =
            if (chatKey == null) {
                sampleChat.observeVoice().map { null to it }
            } else {
                chats.observeChats().map { all -> all.firstOrNull { chatKey in it.keys } to null }
            }

        val state: StateFlow<PickerState> =
            combine(
                target,
                characters.observePacks(),
                foldSettings.observe(PickerKind.Voice),
                interaction,
            ) { (chat, sampleVoice), packs, remembered, local ->
                local.copy(
                    loading = false,
                    chat = chat,
                    sampleVoice = sampleVoice,
                    packs = voicePacks(packs),
                    folds = local.folds.copy(remembered = remembered),
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), interaction.value)

        /** Shows only [packId]'s characters, or every pack for null. */
        fun filterPack(packId: String?) {
            interaction.update { it.copy(packFilter = packId) }
        }

        /** The search field's text: finds characters in folded packs too (ADR 0072). */
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
                    foldSettings.remember(PickerKind.Voice, packId, expanded)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Only the memory for next time is lost; the section still folds now.
                }
            }
        }

        /**
         * "Surprise me" (ADR 0072, #290): selects a random character with clips from [packId], or from every pack for
         * null, never the one selected now ([SurprisePick]), and previews it with its haptic at [atNanos] (after the
         * roll's rattle). The pick becomes visible: the search clears, a chip hiding it goes back to "All" and its pack
         * unfolds for this visit. Returns the pick, or null when there is none.
         */
        fun surprise(
            packId: String?,
            atNanos: Long? = null,
        ): VoiceCharacter? {
            val current = state.value
            if (current.assigned != null) return null
            val pool = current.packs.filter { packId == null || it.packId == packId }.flatMap { it.characters }
            val previous = current.selected ?: current.currentVoice
            val pick = SurprisePick.pick(pool, pool.firstOrNull { it.voice == previous }) ?: return null
            interaction.update {
                it.copy(
                    selected = pick.voice,
                    failed = false,
                    query = "",
                    packFilter = it.packFilter?.takeIf { chip -> chip == pick.packId },
                    folds = it.folds.with(pick.packId, expanded = true),
                    roll = SurpriseRoll(packId, pick.voice, (it.roll?.count ?: 0) + 1),
                )
            }
            speak(pick, withHaptic = true, atNanos = atNanos)
            return pick
        }

        /**
         * Handles a tap on [character]. Returns [PickerTap.Assign] when the screen should fire the assign feedback
         * and call [assign]; otherwise starts the preview and returns [PickerTap.Preview].
         */
        fun tap(character: VoiceCharacter): PickerTap {
            val current = interaction.value
            if (current.assigned != null) return PickerTap.Preview
            if (current.canAssign && current.selected == character.voice) return PickerTap.Assign
            interaction.update { it.copy(selected = character.voice, failed = false) }
            speak(character, withHaptic = true, atNanos = null)
            return PickerTap.Preview
        }

        /**
         * Gives the chat [character]'s voice. Its clip starts at [clipAtNanos] (the assign call's second note,
         * `FeedbackBeat.characterClipAtNanos`) without the character's haptic: the assign haptic is already playing.
         */
        fun assign(
            character: VoiceCharacter,
            clipAtNanos: Long?,
        ) {
            if (interaction.value.assigned != null) return
            interaction.update { it.copy(selected = character.voice, assigned = character.voice, failed = false) }
            speak(character, withHaptic = false, atNanos = clipAtNanos)
            viewModelScope.launch {
                try {
                    if (chatKey == null) {
                        sampleChat.assignVoice(character.voice)
                    } else {
                        chats.assign(chatKey, character.packId, character.id)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    interaction.update { it.copy(assigned = null, failed = true) }
                }
            }
        }

        /** Removes the chat's voice: it keeps WhatsApp's tone again. */
        fun unassign() {
            val key = chatKey ?: return
            viewModelScope.launch {
                try {
                    chats.unassign(key)
                    interaction.update { it.copy(selected = null, failed = false) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    interaction.update { it.copy(failed = true) }
                }
            }
        }

        private fun speak(
            character: VoiceCharacter,
            withHaptic: Boolean,
            atNanos: Long?,
        ) {
            previewJob?.cancel()
            previewJob =
                viewModelScope.launch {
                    val duration = preview.play(character, withHaptic, atNanos)
                    if (duration == null) {
                        interaction.update { it.copy(speaking = null) }
                        return@launch
                    }
                    interaction.update { it.copy(speaking = character.voice) }
                    try {
                        delay(duration.coerceIn(MIN_SPEAK_MS, MAX_SPEAK_MS))
                    } finally {
                        interaction.update { if (it.speaking == character.voice) it.copy(speaking = null) else it }
                    }
                }
        }

        override fun onCleared() {
            preview.stop()
        }

        @AssistedFactory
        interface Factory {
            /** [chatKey] null: the sample chat (ADR 0075). */
            fun create(chatKey: ChatKey?): PickerViewModel
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
            const val MIN_SPEAK_MS = 300L
            const val MAX_SPEAK_MS = 2_000L
        }
    }
