package app.mauri.cucuto.ui.haptics

import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.cucuto.core.data.chats.ChatHapticOverrides
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.routing.HapticOverride
import app.mauri.cucuto.core.data.voices.CharacterDirectory
import app.mauri.cucuto.core.data.voices.VoiceCharacter
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import app.mauri.cucuto.voice.VoicePreview
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A tap-the-rhythm recording in progress.
 *
 * @property events What the presses so far would become (shown live on the timeline).
 * @property elapsedMs Time since the first press (0 before it).
 */
@Immutable
data class RhythmRecording(
    val events: List<HapticEvent> = emptyList(),
    val elapsedMs: Int = 0,
    val pressed: Boolean = false,
)

/**
 * What the haptic editor shows.
 *
 * @property chat The chat whose override is edited, or null for a scratch pad that only previews.
 * @property character The chat's character, to preview the pattern with its clips; null without a voice.
 * @property recording Non-null while "tap the rhythm" records.
 * @property playback Increments on every preview; the timeline sweeps a playhead over [playbackMs].
 * @property saved Set once the override is stored: the screen closes.
 */
@Immutable
data class HapticEditorState(
    val loading: Boolean = true,
    val chat: ChatKey? = null,
    val character: VoiceCharacter? = null,
    val draft: HapticDraft = HapticDraft(),
    val tool: HapticTool = HapticTool.Click,
    val recording: RhythmRecording? = null,
    val dirty: Boolean = false,
    val playback: Int = 0,
    val playbackMs: Int = 0,
    val saved: Boolean = false,
    val failed: Boolean = false,
) {
    val canSave: Boolean get() = chat != null && draft.haptic != null && recording == null && !saved

    val canPreviewWithClip: Boolean get() = character != null && draft.haptic != null

    /** What the timeline draws: the live recording, else the draft. */
    val shownEvents: List<HapticEvent> get() = recording?.events ?: draft.events
}

/**
 * The haptic editor (brief, screen 7; #32): a timeline of presets and basic envelopes the user taps, drags and
 * previews, with "tap the rhythm" to record a pattern by tapping. Opened for a chat, it starts from the chat's custom
 * override (else its character's custom pack pattern) and saving stores a custom override (#33), which the routing
 * table carries to the module.
 */
@HiltViewModel(assistedFactory = HapticEditorViewModel.Factory::class)
class HapticEditorViewModel
    @AssistedInject
    constructor(
        @Assisted private val chat: ChatKey?,
        private val overrides: ChatHapticOverrides,
        private val characters: CharacterDirectory,
        private val preview: VoicePreview,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(HapticEditorState(chat = chat, loading = chat != null))
        val state: StateFlow<HapticEditorState> = mutableState.asStateFlow()

        private val recorder = RhythmRecorder()
        private var recordingJob: Job? = null

        init {
            if (chat != null) load(chat)
        }

        private fun load(chat: ChatKey) {
            viewModelScope.launch {
                val (stored, character) =
                    try {
                        combine(overrides.observe(chat), characters.observePacks()) { stored, packs ->
                            val voice = stored?.voice
                            stored to
                                packs
                                    .firstOrNull { it.packId == voice?.packId }
                                    ?.characters
                                    ?.firstOrNull { it.id == voice?.characterId }
                        }.first()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null to null
                    }
                val start =
                    (stored?.override as? HapticOverride.Custom)?.haptic
                        ?: character?.haptic as? Haptic.Custom
                mutableState.update { current ->
                    current.copy(
                        loading = false,
                        character = character,
                        // Keep anything the user did while loading.
                        draft = if (current.dirty) current.draft else HapticDraft.of(start),
                    )
                }
            }
        }

        // Editing.

        fun selectTool(tool: HapticTool) = mutableState.update { it.copy(tool = tool) }

        fun select(index: Int?) = mutableState.update { it.copy(draft = it.draft.select(index)) }

        /** A tap on the timeline at [atMs]: selects the event there, else drops the current tool. */
        fun tapTimeline(
            atMs: Int,
            toleranceMs: Int,
        ): Boolean {
            val draft = mutableState.value.draft
            val hit = draft.eventAt(atMs, toleranceMs)
            if (hit != null) {
                select(hit)
                return true
            }
            return edit { it.add(mutableState.value.tool, atMs) }
        }

        fun add(
            tool: HapticTool,
            atMs: Int,
        ) = edit { it.add(tool, atMs) }

        fun move(
            index: Int,
            atMs: Int,
        ) = edit { it.move(index, atMs) }

        /** Moves the selected event (the one being dragged; it keeps the selection when it hops over a neighbour). */
        fun moveSelected(atMs: Int) = edit { draft -> draft.selected?.let { draft.move(it, atMs) } ?: draft }

        fun setPreset(
            index: Int,
            preset: HapticPreset,
        ) = edit { it.setPreset(index, preset) }

        fun setStrength(
            index: Int,
            milli: Int,
        ) = edit { it.setStrength(index, milli) }

        fun setSharpness(
            index: Int,
            milli: Int,
        ) = edit { it.setSharpness(index, milli) }

        fun movePoint(
            index: Int,
            point: Int,
            milliIntensity: Int,
            durationMs: Int,
        ) = edit { it.movePoint(index, point, milliIntensity, durationMs) }

        fun addPoint(index: Int) = edit { it.addPoint(index) }

        fun removePoint(
            index: Int,
            point: Int,
        ) = edit { it.removePoint(index, point) }

        fun delete(index: Int) = edit { it.delete(index) }

        fun clear() = edit { it.clear() }

        fun undo() = edit { it.undo() }

        /** Applies [change] to the draft. Returns false when nothing changed (it did not fit). */
        private fun edit(change: (HapticDraft) -> HapticDraft): Boolean {
            var changed = false
            mutableState.update { current ->
                if (current.recording != null || current.saved) return@update current
                val updated = change(current.draft)
                changed = updated.events != current.draft.events
                current.copy(draft = updated, dirty = current.dirty || changed)
            }
            return changed
        }

        // Tap the rhythm.

        /** Starts recording. The first press starts the clock; the recording replaces the timeline (undo brings it back). */
        fun startRecording() {
            if (mutableState.value.recording != null) return
            preview.stop()
            recorder.reset()
            mutableState.update { it.copy(recording = RhythmRecording(), draft = it.draft.select(null)) }
            recordingJob?.cancel()
            recordingJob =
                viewModelScope.launch {
                    while (isActive) {
                        delay(TICK_MS)
                        val now = SystemClock.uptimeMillis()
                        if (recorder.isFull(now)) {
                            stopRecording(now)
                            break
                        }
                        refreshRecording(now)
                    }
                }
        }

        /** A finger went down on the pad at [nowMs] (`uptimeMillis`). Returns false when it was not recorded. */
        fun press(nowMs: Long): Boolean {
            if (mutableState.value.recording == null) return false
            val recorded = recorder.press(nowMs)
            refreshRecording(nowMs)
            return recorded
        }

        /** The finger went up at [nowMs]. */
        fun release(nowMs: Long) {
            if (mutableState.value.recording == null) return
            recorder.release(nowMs)
            refreshRecording(nowMs)
        }

        /** Ends the recording: what was tapped replaces the timeline. Nothing tapped leaves it as it was. */
        fun stopRecording(nowMs: Long = SystemClock.uptimeMillis()) {
            recordingJob?.cancel()
            recordingJob = null
            val events = recorder.events(nowMs)
            val tapped = !recorder.isEmpty
            recorder.reset()
            mutableState.update { current ->
                if (current.recording == null) return@update current
                if (!tapped) return@update current.copy(recording = null)
                current.copy(recording = null, draft = current.draft.replaceAll(events), dirty = true)
            }
        }

        private fun refreshRecording(nowMs: Long) {
            mutableState.update { current ->
                if (current.recording == null) return@update current
                current.copy(
                    recording =
                        RhythmRecording(
                            events = recorder.events(nowMs),
                            elapsedMs = recorder.elapsedMs(nowMs),
                            pressed = recorder.isPressed,
                        ),
                )
            }
        }

        // Preview and save.

        /** Plays the pattern, with one of the character's clips when [withClip]. */
        fun preview(withClip: Boolean) {
            val current = mutableState.value
            val haptic = current.draft.haptic ?: return
            val character = current.character
            mutableState.update { it.copy(playback = it.playback + 1, playbackMs = current.draft.durationMs) }
            if (withClip && character != null) {
                viewModelScope.launch { preview.play(character, haptic) }
            } else {
                preview.vibrate(haptic)
            }
        }

        /** Stores the pattern as the chat's custom vibration. */
        fun save() {
            val current = mutableState.value
            val key = chat ?: return
            val haptic = current.draft.haptic ?: return
            if (!current.canSave) return
            viewModelScope.launch {
                val stored =
                    try {
                        overrides.set(key, HapticOverride.Custom(haptic))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        false
                    }
                mutableState.update { if (stored) it.copy(saved = true, dirty = false) else it.copy(failed = true) }
            }
        }

        fun dismissError() = mutableState.update { it.copy(failed = false) }

        override fun onCleared() {
            preview.stop()
        }

        @AssistedFactory
        interface Factory {
            fun create(chat: ChatKey?): HapticEditorViewModel
        }

        private companion object {
            const val TICK_MS = 50L
        }
    }
