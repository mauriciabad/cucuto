package app.mauri.cucuto.picker

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.cucuto.core.data.chats.Chat
import app.mauri.cucuto.core.data.chats.ChatDirectory
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.chats.ChatVoice
import app.mauri.cucuto.core.data.voices.CharacterDirectory
import app.mauri.cucuto.core.data.voices.VoiceCharacter
import app.mauri.cucuto.core.data.voices.VoicePack
import app.mauri.cucuto.voice.VoicePreview
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the character picker shows.
 *
 * @property chatKey The chat being assigned, or null when only previewing characters.
 * @property chat That chat as the directory knows it (null while loading, or for a chat not seen yet).
 * @property selected The character the first tap previewed; a second tap on it assigns.
 * @property speaking The character whose clip is playing (its sprite bounces).
 * @property assigned Set once an assignment is saved: the screen celebrates, then closes.
 */
@Immutable
data class PickerState(
    val loading: Boolean = true,
    val chatKey: ChatKey? = null,
    val chat: Chat? = null,
    val packs: List<VoicePack> = emptyList(),
    val selected: ChatVoice? = null,
    val speaking: ChatVoice? = null,
    val assigned: ChatVoice? = null,
    val failed: Boolean = false,
) {
    val canAssign: Boolean get() = chatKey != null

    /** The chat's voice: the one just assigned, else the stored one. */
    val currentVoice: ChatVoice? get() = assigned ?: chat?.voice

    fun character(voice: ChatVoice?): VoiceCharacter? =
        voice?.let { v ->
            packs.firstOrNull { it.packId == v.packId }?.characters?.firstOrNull { it.id == v.characterId }
        }
}

/** What a tap on a character does: the first tap previews it, a second tap on the same character assigns it. */
enum class PickerTap { Preview, Assign }

/**
 * The character picker (brief, screen 3): tap previews (the sprite bounces, a clip plays, the haptic fires), a second
 * tap assigns. Assigning goes through [ChatDirectory], which republishes the routing table, so the chat's next
 * notification plays the new character.
 */
@HiltViewModel(assistedFactory = PickerViewModel.Factory::class)
class PickerViewModel
    @AssistedInject
    constructor(
        @Assisted private val chatKey: ChatKey?,
        private val chats: ChatDirectory,
        characters: CharacterDirectory,
        private val preview: VoicePreview,
    ) : ViewModel() {
        private val interaction = MutableStateFlow(PickerState(chatKey = chatKey))
        private var previewJob: Job? = null

        val state: StateFlow<PickerState> =
            combine(chats.observeChats(), characters.observePacks(), interaction) { all, packs, local ->
                local.copy(
                    loading = false,
                    chat = chatKey?.let { key -> all.firstOrNull { key in it.keys } },
                    packs = packs,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), interaction.value)

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
            val key = chatKey ?: return
            if (interaction.value.assigned != null) return
            interaction.update { it.copy(selected = character.voice, assigned = character.voice, failed = false) }
            speak(character, withHaptic = false, atNanos = clipAtNanos)
            viewModelScope.launch {
                try {
                    chats.assign(key, character.packId, character.id)
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
            fun create(chatKey: ChatKey?): PickerViewModel
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
            const val MIN_SPEAK_MS = 300L
            const val MAX_SPEAK_MS = 2_000L
        }
    }
