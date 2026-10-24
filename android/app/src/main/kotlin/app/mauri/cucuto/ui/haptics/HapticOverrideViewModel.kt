package app.mauri.cucuto.ui.haptics

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.cucuto.core.data.chats.ChatHapticOverrides
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.routing.HapticOverride
import app.mauri.cucuto.core.data.voices.CharacterDirectory
import app.mauri.cucuto.core.data.voices.VoiceCharacter
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.voice.VoicePreview
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

/** The four per-contact vibration choices (#33), in the order the screen shows them. */
enum class HapticChoice { Pack, Auto, Off, Custom }

/** The choice that [override] stands for. */
fun HapticOverride.choice(): HapticChoice =
    when (this) {
        HapticOverride.PackDefault -> HapticChoice.Pack
        HapticOverride.Auto -> HapticChoice.Auto
        HapticOverride.Off -> HapticChoice.Off
        is HapticOverride.Custom -> HapticChoice.Custom
    }

/**
 * What the per-contact vibration section shows.
 *
 * @property hasVoice Whether the chat has a voice; an override belongs to it, so without one there is nothing to set.
 * @property character The assigned character, for its pack haptic and its clips (null while loading or when its pack
 *   is not installed).
 * @property choice The stored choice.
 * @property custom The custom pattern: the stored one, or the last one this screen saw (so switching to Auto and back
 *   to Custom does not lose it).
 */
@Immutable
data class HapticOverrideState(
    val loading: Boolean = true,
    val hasVoice: Boolean = false,
    val character: VoiceCharacter? = null,
    val choice: HapticChoice = HapticChoice.Pack,
    val custom: Haptic.Custom? = null,
    val failed: Boolean = false,
) {
    /** The vibration the module plays with this choice. */
    val effective: Haptic?
        get() =
            when (choice) {
                HapticChoice.Pack -> character?.haptic
                HapticChoice.Auto -> Haptic.Auto
                HapticChoice.Off -> Haptic.Off
                HapticChoice.Custom -> custom
            }
}

/** What choosing an option did. */
enum class HapticChoiceResult {
    /** Stored; the routing table follows. */
    Stored,

    /** "Custom" without a pattern yet: the screen opens the haptic editor, which stores the override on save. */
    OpenEditor,
}

/**
 * The per-contact vibration (#33) for one chat: keep the pack's haptic, follow the voice (auto), no vibration, or a
 * custom pattern from the haptic editor. Stored through [ChatHapticOverrides], which republishes the routing table, so
 * the next message from the chat vibrates the new way.
 */
@HiltViewModel(assistedFactory = HapticOverrideViewModel.Factory::class)
class HapticOverrideViewModel
    @AssistedInject
    constructor(
        @Assisted private val chat: ChatKey,
        private val overrides: ChatHapticOverrides,
        characters: CharacterDirectory,
        private val preview: VoicePreview,
    ) : ViewModel() {
        private val lastCustom = MutableStateFlow<Haptic.Custom?>(null)
        private val failed = MutableStateFlow(false)

        val state: StateFlow<HapticOverrideState> =
            combine(overrides.observe(chat), characters.observePacks(), lastCustom, failed) {
                stored,
                packs,
                remembered,
                failed,
                ->
                val storedCustom = (stored?.override as? HapticOverride.Custom)?.haptic
                HapticOverrideState(
                    loading = false,
                    hasVoice = stored != null,
                    character =
                        stored?.voice?.let { voice ->
                            packs
                                .firstOrNull { it.packId == voice.packId }
                                ?.characters
                                ?.firstOrNull { it.id == voice.characterId }
                        },
                    choice = stored?.override?.choice() ?: HapticChoice.Pack,
                    custom = storedCustom ?: remembered,
                    failed = failed,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HapticOverrideState())

        /** Picks [choice]. "Custom" stores the remembered pattern, or asks the screen to open the editor. */
        fun choose(choice: HapticChoice): HapticChoiceResult {
            // Leaving Custom forgets the stored pattern; keep it here so Custom can bring it back.
            state.value.custom?.let { lastCustom.value = it }
            val override =
                when (choice) {
                    HapticChoice.Pack -> {
                        HapticOverride.PackDefault
                    }

                    HapticChoice.Auto -> {
                        HapticOverride.Auto
                    }

                    HapticChoice.Off -> {
                        HapticOverride.Off
                    }

                    HapticChoice.Custom -> {
                        HapticOverride.Custom(state.value.custom ?: return HapticChoiceResult.OpenEditor)
                    }
                }
            failed.value = false
            viewModelScope.launch {
                try {
                    if (!overrides.set(chat, override)) failed.value = true
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failed.value = true
                }
            }
            return HapticChoiceResult.Stored
        }

        /** Plays one of the character's clips with the chosen vibration, as a message would. */
        fun preview() {
            val current = state.value
            val character = current.character ?: return
            val haptic = current.effective ?: return
            viewModelScope.launch { preview.play(character, haptic) }
        }

        fun dismissError() = failed.update { false }

        override fun onCleared() {
            preview.stop()
        }

        @AssistedFactory
        interface Factory {
            fun create(chat: ChatKey): HapticOverrideViewModel
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
