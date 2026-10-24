package app.mauri.cucuto.contact

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.cucuto.core.data.chats.ChatDirectory
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.voices.CharacterDirectory
import app.mauri.cucuto.voice.VoicePreview
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Contact detail (brief, screen 4): the chat's character, its clip pool, vibration and group policy, the decision
 * log for this chat and "Simulate message", which plays exactly what the chat's next message will do: through the
 * module when `system_server` runs this build, otherwise in the app with the same rules (ADR 0025).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = ContactDetailViewModel.Factory::class)
class ContactDetailViewModel
    @AssistedInject
    constructor(
        @Assisted private val chatKey: ChatKey,
        private val chats: ChatDirectory,
        characters: CharacterDirectory,
        private val source: ContactDetailSource,
        private val simulator: MessageSimulator,
        private val preview: VoicePreview,
    ) : ViewModel() {
        private val local = MutableStateFlow(ContactDetailState(chatKey))
        private var playJob: Job? = null

        private val chat =
            chats.observeChats().map { all -> all.firstOrNull { chatKey in it.keys } }.distinctUntilChanged()

        private val settings =
            chat
                .map { it?.contactId }
                .distinctUntilChanged()
                .flatMapLatest { id -> if (id == null) flowOf(null) else source.observeSettings(id) }

        val state: StateFlow<ContactDetailState> =
            combine(chat, characters.observePacks(), settings, source.observeDecisions(), local) {
                chat,
                packs,
                settings,
                log,
                local,
                ->
                val found = characterOf(chat, packs)
                val decisions = decisionsForChat(log, chatKey, chat)
                local.copy(
                    loading = false,
                    chat = chat,
                    character = found?.first,
                    packName = found?.second,
                    settings = settings,
                    clips = clipRows(found?.first, settings?.disabledClips.orEmpty()),
                    decisions = decisions,
                    report = (local.simulation as? SimulationStatus.Sent)?.let { reportFor(decisions, it.atMillis) },
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), local.value)

        init {
            refreshPath()
        }

        /** Re-reads which path a simulation takes (the screen calls it on resume: LSPosed state changes outside). */
        fun refreshPath() {
            viewModelScope.launch { local.update { it.copy(path = simulator.path()) } }
        }

        /**
         * Plays what the chat's next message will do. [title] is the simulated notification's title. Chooses the
         * path again first, so a reboot or a module change since the screen opened is picked up.
         */
        fun simulate(title: String) {
            if (local.value.simulation == SimulationStatus.Working) return
            playJob?.cancel()
            local.update { it.copy(simulation = SimulationStatus.Working, playingClip = null) }
            playJob =
                viewModelScope.launch {
                    try {
                        val path = simulator.path()
                        local.update { it.copy(path = path) }
                        when (path) {
                            SimulatePath.Module -> simulateThroughModule(title)
                            is SimulatePath.InApp -> simulateInApp()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        local.update { it.copy(simulation = SimulationStatus.Failed, speaking = false) }
                    } finally {
                        // Cancelled before a result (a clip tap took over): ready for the next tap.
                        local.update {
                            if (it.simulation == SimulationStatus.Working) it.copy(simulation = SimulationStatus.Idle) else it
                        }
                    }
                }
        }

        private suspend fun simulateThroughModule(title: String) {
            when (val result = simulator.throughModule(chatKey, title)) {
                is ModuleSimulation.Posted -> {
                    local.update { it.copy(simulation = SimulationStatus.Sent(result.atMillis), speaking = true) }
                    speakFor(MODULE_SPEAK_MS)
                }

                ModuleSimulation.Blocked -> {
                    local.update { it.copy(simulation = SimulationStatus.Blocked) }
                }

                is ModuleSimulation.TooSoon -> {
                    local.update { it.copy(simulation = SimulationStatus.TooSoon(result.seconds)) }
                }
            }
        }

        private suspend fun simulateInApp() {
            when (val result = simulator.inApp(chatKey)) {
                is InAppSimulation.Played -> {
                    local.update {
                        it.copy(
                            simulation = SimulationStatus.PlayedInApp(result.clipName, result.haptic),
                            speaking = true,
                        )
                    }
                    speakFor(result.durationMs)
                }

                is InAppSimulation.WhatsAppTone -> {
                    local.update { it.copy(simulation = SimulationStatus.WhatsAppTone(result.outcome)) }
                }

                InAppSimulation.Failed -> {
                    local.update { it.copy(simulation = SimulationStatus.Failed) }
                }
            }
        }

        private suspend fun speakFor(durationMs: Long) {
            try {
                delay(durationMs.coerceIn(MIN_SPEAK_MS, MAX_SPEAK_MS))
            } finally {
                local.update { it.copy(speaking = false) }
            }
        }

        /** Plays one clip of the pool on its own, without the haptic. */
        fun playClip(clip: ClipRow) {
            playJob?.cancel()
            playJob =
                viewModelScope.launch {
                    local.update { it.copy(playingClip = clip.path, speaking = false) }
                    val duration = preview.playClip(clip.file)
                    try {
                        if (duration != null) delay(duration.coerceIn(MIN_SPEAK_MS, MAX_SPEAK_MS))
                    } finally {
                        local.update { if (it.playingClip == clip.path) it.copy(playingClip = null) else it }
                    }
                }
        }

        /** Switches [clip] on or off for this contact. The last enabled clip stays on. */
        fun setClipEnabled(
            clip: ClipRow,
            enabled: Boolean,
        ) {
            val contactId = state.value.chat?.contactId ?: return
            if (!enabled && !clip.canToggle) return
            write { source.setClipEnabled(contactId, clip.path, enabled) }
        }

        /** Removes the chat's voice: it keeps WhatsApp's tone again. */
        fun unassign() = write { chats.unassign(chatKey) }

        /** Clears a shown error. */
        fun clearFailure() = local.update { it.copy(failed = false) }

        private fun write(block: suspend () -> Unit) {
            viewModelScope.launch {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    local.update { it.copy(failed = true) }
                }
            }
        }

        override fun onCleared() {
            simulator.stop()
            preview.stop()
        }

        @AssistedFactory
        interface Factory {
            fun create(chatKey: ChatKey): ContactDetailViewModel
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
            const val MIN_SPEAK_MS = 300L
            const val MAX_SPEAK_MS = 3_000L

            /** How long the avatar "speaks" after the module path posts (the system plays the clip meanwhile). */
            const val MODULE_SPEAK_MS = 1_500L
        }
    }
