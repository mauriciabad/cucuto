package app.mauri.cucuto.ui.ringtone

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.cucuto.core.data.chats.Chat
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.chats.ChatVoice
import app.mauri.cucuto.core.data.db.PhoneRingtoneEntity
import app.mauri.cucuto.core.data.db.RingtoneMode
import app.mauri.cucuto.core.data.di.WallClock
import app.mauri.cucuto.core.ringtone.variants.RuleContext
import app.mauri.cucuto.core.ringtone.variants.VariantRules
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
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * A phone ringtone as the picker edits it: the character that rings ([voice]), whether that is because it
 * [followsVoice] (the message character), how the version is chosen, and the version for [RingtoneMode.FIXED].
 */
@Immutable
data class RingtoneSelection(
    val voice: ChatVoice,
    val followsVoice: Boolean,
    val mode: RingtoneMode = RingtoneMode.PACK,
    val variant: String? = null,
)

/** The version being previewed. */
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
    val assigned: RingtoneSelection? = null,
    val failed: Boolean = false,
) {
    val contactId: Long? get() = chat?.contactId

    /** The chat's message character. */
    val voice: ChatVoice? get() = chat?.voice

    /** What the screen shows as chosen: the edit, else what is stored, else the voice's own ringtone. */
    val selection: RingtoneSelection?
        get() =
            edited ?: saved
                ?: voice?.takeIf { character(it) != null }?.let { RingtoneSelection(it, followsVoice = true) }

    val selectedCharacter: RingtoneCharacterItem? get() = character(selection?.voice)

    /** The rules worth offering for the selected character's versions ([PhoneRingtoneViewModel.modesFor]). */
    val modes: List<RingtoneMode>
        get() =
            selectedCharacter
                ?.let { item ->
                    PhoneRingtoneViewModel.modesFor(item.variants.map { it.variant })
                }.orEmpty()

    /** Something to save: a selected character with ringtones, different from what is stored. */
    val canSave: Boolean
        get() = contactId != null && selectedCharacter != null && assigned == null && selection != saved

    fun character(voice: ChatVoice?): RingtoneCharacterItem? =
        voice?.let { v ->
            packs.firstOrNull { it.packId == v.packId }?.characters?.firstOrNull { it.voice == v }
        }
}

/**
 * The ringtone picker (#53, brief "Ringtones"): every installed character with ringtones as an album cover. A tap picks
 * it and plays the version its rule would ring with now (the record spins); each version plays on its own too. The
 * user picks a character (the message character follows the voice; any other one mixes, "messages from Pikachu,
 * ringtone from K.K. Slider") and a rule, and saves.
 */
@HiltViewModel(assistedFactory = RingtonePickerViewModel.Factory::class)
class RingtonePickerViewModel
    @AssistedInject
    constructor(
        @Assisted private val chatKey: ChatKey,
        private val source: RingtonePickerSource,
        private val preview: RingtonePreview,
        private val clock: WallClock,
    ) : ViewModel() {
        private val interaction = MutableStateFlow(RingtonePickerState(chatKey = chatKey))
        private var previewJob: Job? = null

        val state: StateFlow<RingtonePickerState> =
            combine(source.observe(chatKey), interaction) { data, local ->
                local.copy(
                    loading = false,
                    chat = data.chat,
                    packs = data.packs,
                    saved = saved(data.saved, data.chat?.voice),
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), interaction.value)

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
            val modes = PhoneRingtoneViewModel.modesFor(item.variants.map { it.variant })
            val previous = current.selection
            val mode = previous?.mode?.takeIf { it in modes } ?: RingtoneMode.PACK
            val variant =
                if (mode == RingtoneMode.FIXED) {
                    previous?.variant?.takeIf { item.variant(it) != null } ?: item.variants.first().file
                } else {
                    null
                }
            val selection = RingtoneSelection(item.voice, followsVoice = item.voice == current.voice, mode, variant)
            interaction.update { it.copy(edited = selection, failed = false) }
            play(item, previewVariant(item, mode, variant))
        }

        /** Plays [file] of [item], or stops it when it is the one playing. */
        fun togglePreview(
            item: RingtoneCharacterItem,
            file: String,
        ) {
            if (state.value.playing == PlayingVariant(item.voice, file)) stopPreview() else play(item, file)
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
            interaction.update { it.copy(edited = selection.copy(mode = mode, variant = variant), failed = false) }
        }

        /** Always rings with [file] ("One version"). */
        fun setVariant(file: String) {
            val selection = state.value.selection ?: return
            if (state.value.assigned != null || state.value.selectedCharacter?.variant(file) == null) return
            interaction.update {
                it.copy(edited = selection.copy(mode = RingtoneMode.FIXED, variant = file), failed = false)
            }
        }

        /** Whether saving should first ask for the phone permission ("Random per call" re-rolls after each call). */
        fun needsPhonePermission(): Boolean =
            state.value.selection?.mode == RingtoneMode.RANDOM && !source.canReadPhoneState()

        /** Saves the selection. Returns false when there is nothing to save. */
        fun save(): Boolean {
            val current = state.value
            val selection = current.selection ?: return false
            val contactId = current.contactId ?: return false
            if (!current.canSave) return false
            val single = current.modes.isEmpty()
            val mode = if (single) RingtoneMode.PACK else selection.mode
            val variant = if (mode == RingtoneMode.FIXED) selection.variant else null
            stopPreview()
            interaction.update { it.copy(assigned = selection, failed = false) }
            viewModelScope.launch {
                try {
                    source.save(contactId, if (selection.followsVoice) null else selection.voice, mode, variant)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    interaction.update { it.copy(assigned = null, failed = true) }
                }
            }
            return true
        }

        fun stopPreview() {
            previewJob?.cancel()
            previewJob = null
            preview.stop()
            interaction.update { it.copy(playing = null) }
        }

        private fun play(
            item: RingtoneCharacterItem,
            file: String,
        ) {
            val variant = item.variant(file) ?: return
            val audio = variant.audio ?: return
            val playing = PlayingVariant(item.voice, file)
            previewJob?.cancel()
            previewJob =
                viewModelScope.launch {
                    interaction.update { it.copy(playing = playing) }
                    try {
                        val duration = preview.play(audio) ?: return@launch
                        delay(duration.coerceIn(MIN_PREVIEW_MS, MAX_PREVIEW_MS))
                        preview.stop()
                    } finally {
                        interaction.update { if (it.playing == playing) it.copy(playing = null) else it }
                    }
                }
        }

        /** The version [mode] would ring with now (day from 07:00 to 19:00 here; the sync uses the chosen place). */
        private fun previewVariant(
            item: RingtoneCharacterItem,
            mode: RingtoneMode,
            fixed: String?,
        ): String {
            val variants = item.variants.map { it.variant }
            if (mode == RingtoneMode.RANDOM) return variants.first().file
            val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(clock.now()), ZoneId.systemDefault())
            return VariantRules
                .pick(variants, mode, fixed, RuleContext(now), current = null, reroll = false, random = Random(0))
                ?.file ?: variants.first().file
        }

        override fun onCleared() {
            preview.stop()
        }

        @AssistedFactory
        interface Factory {
            fun create(chatKey: ChatKey): RingtonePickerViewModel
        }

        internal companion object {
            private const val STOP_TIMEOUT_MS = 5_000L

            /** A preview stops after 30 s: enough to hear the loop, short of a 6 minute song. */
            const val MAX_PREVIEW_MS = 30_000L
            private const val MIN_PREVIEW_MS = 300L

            /** The stored row as a selection: no character of its own follows [voice]. */
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
                    followsVoice = own == null,
                    mode = row.mode,
                    variant = row.variant.takeIf { row.mode == RingtoneMode.FIXED },
                )
            }
        }
    }
