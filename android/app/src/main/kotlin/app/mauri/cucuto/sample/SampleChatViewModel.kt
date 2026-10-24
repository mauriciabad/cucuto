package app.mauri.cucuto.sample

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.cucuto.core.data.di.WallClock
import app.mauri.cucuto.core.data.settings.SampleChatStore
import app.mauri.cucuto.core.data.voices.CharacterDirectory
import app.mauri.cucuto.core.ringtone.variants.RuleContext
import app.mauri.cucuto.picker.voicePacks
import app.mauri.cucuto.ui.discover.DemoPacksRequests
import app.mauri.cucuto.ui.ringtone.PreviewPlaying
import app.mauri.cucuto.ui.ringtone.RingtonePlayback
import app.mauri.cucuto.ui.ringtone.RingtonePreview
import app.mauri.cucuto.voice.VoicePreview
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
import javax.inject.Inject

/**
 * The sample chat (ADR 0075): a demo chat that gets a voice and a ringtone and plays them in the app, for Play
 * reviewers and anyone without the module. It reads the installed characters and [SampleChatStore], which the real
 * pickers write through [SampleChatPickerTarget] (#346): never the chat directory, so nothing reaches the routing
 * table, the module, backups or Android contacts. Playing uses the app's previews (public APIs only) and posts no
 * notification.
 */
@HiltViewModel
class SampleChatViewModel
    @Inject
    constructor(
        characters: CharacterDirectory,
        ringtones: SampleRingtones,
        store: SampleChatStore,
        private val preview: VoicePreview,
        ringtonePreview: RingtonePreview,
        private val clock: WallClock,
        private val demoPacks: DemoPacksRequests,
    ) : ViewModel() {
        private val local = MutableStateFlow(Local())
        private var playJob: Job? = null

        /** The sample call ringing, or null. */
        private val call = RingtonePlayback<String>(viewModelScope, ringtonePreview)

        val ringing: StateFlow<PreviewPlaying<String>?> = call.playing

        private data class Local(
            val speaking: Boolean = false,
            val playFailed: Boolean = false,
        )

        val state: StateFlow<SampleChatState> =
            combine(characters.observePacks(), ringtones.observe(), store.choice, local) { packs, rings, choice, l ->
                SampleChatState(
                    loading = false,
                    packs = voicePacks(packs),
                    ringtonePacks = rings,
                    choice = choice,
                    speaking = l.speaking,
                    playFailed = l.playFailed,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SampleChatState())

        /** "Play a sample message": one of the character's clips with its vibration, in the app. */
        fun playMessage() {
            val character = state.value.character ?: return
            call.stop()
            play { preview.play(character) }
        }

        /** "Play a sample call": rings the ringtone like a call until [stopCall]; a second tap stops it. */
        fun toggleCall() {
            if (call.playing.value != null) {
                stopCall()
                return
            }
            val item = state.value.ringtone ?: return
            val sound = sampleCallSound(item, RuleContext(now()))
            if (sound == null) {
                local.update { it.copy(playFailed = true) }
                return
            }
            playJob?.cancel()
            preview.stop()
            local.update { it.copy(speaking = false, playFailed = false) }
            call.play(CALL_KEY, sound)
        }

        fun stopCall() = call.stop()

        /** "Get the demo packs" when nothing is installed: Discover, which the caller opens next, installs them. */
        fun requestDemoPacks() = demoPacks.offer()

        /** Clears a shown playback error. */
        fun clearFailure() = local.update { it.copy(playFailed = false) }

        private fun play(block: suspend () -> Long?) {
            playJob?.cancel()
            playJob =
                viewModelScope.launch {
                    local.update { it.copy(speaking = true, playFailed = false) }
                    try {
                        val duration = block()
                        if (duration == null) {
                            local.update { it.copy(playFailed = true) }
                        } else {
                            delay(duration.coerceIn(MIN_SPEAK_MS, MAX_SPEAK_MS))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        local.update { it.copy(playFailed = true) }
                    } finally {
                        local.update { it.copy(speaking = false) }
                    }
                }
        }

        private fun now(): LocalDateTime =
            LocalDateTime.ofInstant(Instant.ofEpochMilli(clock.now()), ZoneId.systemDefault())

        override fun onCleared() {
            call.stop()
            preview.stop()
        }

        private companion object {
            const val CALL_KEY = "sample-call"
            const val STOP_TIMEOUT_MS = 5_000L
            const val MIN_SPEAK_MS = 300L
            const val MAX_SPEAK_MS = 3_000L
        }
    }
