package app.mauri.cucuto.ui.creator

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.cucuto.core.audio.MicRecorder
import app.mauri.cucuto.core.audio.PcmAudio
import app.mauri.cucuto.core.audio.PcmPlayer
import app.mauri.cucuto.core.audio.dsp.ClipEdit
import app.mauri.cucuto.core.creator.CreatorDraft
import app.mauri.cucuto.core.creator.DraftCharacter
import app.mauri.cucuto.core.creator.HapticMode
import app.mauri.cucuto.core.creator.SpriteCrop
import app.mauri.cucuto.core.creator.WriteResult
import app.mauri.cucuto.core.pack.FailureReason
import app.mauri.cucuto.core.pack.ImportSource
import app.mauri.cucuto.core.pack.PackSelection
import app.mauri.cucuto.packs.ImportProblem
import app.mauri.cucuto.packs.ImportStep
import app.mauri.cucuto.packs.PackCatalog
import app.mauri.cucuto.packs.problemFor
import app.mauri.cucuto.packs.warningItems
import app.mauri.cucuto.packspec.ErrorCode
import app.mauri.cucuto.packspec.HapticEvent
import app.mauri.cucuto.packspec.authoring.Authoring
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.Locale
import javax.inject.Inject

/**
 * The in-app pack creator (#47, ADR 0034): pack details, characters with a framed picture and colors from it, clips
 * recorded or cut from audio files (trimmed, evened out and encoded to Opus on the phone), haptics, then a check with
 * the same validator as packer and the website, and install, export or share. The draft is saved after every change.
 *
 * Pages stack inside the creator ([CreatorPage]); system back pops them ([back]) before leaving the screen.
 */
@HiltViewModel
class CreatorViewModel
    @Inject
    constructor(
        private val tools: CreatorTools,
        private val catalog: PackCatalog,
        private val preview: CreatorPreview,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(CreatorUiState())
        val state: StateFlow<CreatorUiState> = mutableState.asStateFlow()

        private val pages = ArrayDeque<CreatorPage>(listOf(CreatorPage.Pack))
        private var started = false
        private var copyName: (String) -> String = { it }
        private var mic: MicRecorder? = null
        private var recordJob: Job? = null
        private var playJob: Job? = null
        private var workJob: Job? = null
        private val player = PcmPlayer()

        /**
         * Opens the saved draft, or a copy of [sourcePackId] ("duplicate and edit"). Asks first when that would
         * replace unfinished work. [copyName] names a copy ("Birds (copy)"). Runs once per screen.
         */
        fun start(
            sourcePackId: String?,
            copyName: (String) -> String,
        ) {
            if (started) return
            started = true
            this.copyName = copyName
            viewModelScope.launch {
                val saved = tools.loadDraft()
                when {
                    sourcePackId != null && (saved == null || saved.isEmpty) -> {
                        duplicate(sourcePackId)
                    }

                    sourcePackId != null -> {
                        setState {
                            copy(
                                draft = saved,
                                prompt = StartPrompt.ReplaceWithCopy(saved!!.name, sourcePackId),
                            )
                        }
                    }

                    saved != null && !saved.isEmpty -> {
                        setState { copy(draft = saved, prompt = StartPrompt.Resume(saved.name)) }
                    }

                    else -> {
                        val fresh = tools.newDraft()
                        setState { copy(draft = fresh) }
                    }
                }
            }
        }

        // region Start prompts

        /** Keeps the saved draft (answers both prompts). */
        fun keepDraft() = setState { copy(prompt = null) }

        fun startNew() {
            viewModelScope.launch {
                val fresh = tools.newDraft()
                resetPages()
                setState { copy(draft = fresh, prompt = null, seedSuggestions = emptyMap(), page = CreatorPage.Pack) }
            }
        }

        fun replaceWithCopy() {
            val prompt = state.value.prompt as? StartPrompt.ReplaceWithCopy ?: return
            setState { copy(prompt = null) }
            viewModelScope.launch { duplicate(prompt.sourcePackId) }
        }

        private suspend fun duplicate(packId: String) {
            try {
                val result = tools.duplicate(packId, copyName)
                resetPages()
                setState {
                    copy(
                        draft = result.draft,
                        page = CreatorPage.Pack,
                        seedSuggestions = emptyMap(),
                        message = CreatorMessage.Duplicated(result.droppedRingtones, result.droppedAnimations),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { copy(draft = draft ?: CreatorDraft(), message = CreatorMessage.DuplicateFailed) }
            }
        }

        // endregion

        // region Pages

        /** Pops the top page. Returns false on the pack page, so the screen itself goes back. */
        fun back(): Boolean {
            if (pages.size <= 1) return false
            when (pages.last()) {
                CreatorPage.Recorder -> cancelRecording()
                CreatorPage.ClipEditor -> stopPlayback()
                else -> Unit
            }
            pop()
            return true
        }

        fun openCharacter(key: String) {
            push(CreatorPage.Character(key))
            loadSeedSuggestions(key)
        }

        private fun push(page: CreatorPage) {
            pages.addLast(page)
            setState { copy(page = page) }
        }

        private fun pop() {
            val leaving = pages.removeLast()
            setState {
                copy(
                    page = pages.last(),
                    cropper = if (leaving == CreatorPage.Cropper) null else cropper,
                    recorder = if (leaving == CreatorPage.Recorder) null else recorder,
                    clipEditor = if (leaving == CreatorPage.ClipEditor) null else clipEditor,
                )
            }
        }

        private fun resetPages() {
            pages.clear()
            pages.addLast(CreatorPage.Pack)
        }

        // endregion

        // region Pack details

        fun setName(name: String) = edit { copy(name = name.take(MAX_NAME)) }

        fun setAuthor(author: String) = edit { copy(author = author.take(MAX_AUTHOR)) }

        fun setLicense(license: String) = edit { copy(license = license.take(MAX_LICENSE)) }

        fun setDescription(description: String) = edit { copy(description = description.take(MAX_DESCRIPTION)) }

        fun removeCover() = edit { copy(cover = null) }

        // endregion

        // region Characters

        fun addCharacter() {
            val character = DraftCharacter()
            edit { copy(characters = characters + character) }
            push(CreatorPage.Character(character.key))
        }

        fun removeCharacter(key: String) {
            edit { copy(characters = characters.filterNot { it.key == key }) }
            if ((pages.last() as? CreatorPage.Character)?.key == key) pop()
        }

        fun setCharacterName(
            key: String,
            name: String,
        ) = editCharacter(key) { copy(name = name.take(MAX_NAME)) }

        /** [argb] null lets apps extract the color from the sprite. */
        fun setSeedColor(
            key: String,
            argb: Int?,
        ) = editCharacter(key) { copy(seedColor = argb?.let(::hexColor)) }

        fun setHaptic(
            key: String,
            mode: HapticMode,
        ) = editCharacter(key) { copy(haptic = mode) }

        /** The haptic editor's result for a character (hook for #32: the editor calls this when it saves). */
        fun setCustomHaptic(
            key: String,
            events: List<HapticEvent>,
        ) = editCharacter(key) { copy(haptic = HapticMode.CUSTOM, customHaptic = events) }

        fun removeClip(
            characterKey: String,
            clipKey: String,
        ) = editCharacter(characterKey) { copy(clips = clips.filterNot { it.key == clipKey }) }

        fun setClipLabel(
            characterKey: String,
            clipKey: String,
            label: String,
        ) = editCharacter(characterKey) {
            copy(clips = clips.map { if (it.key == clipKey) it.copy(label = label.take(MAX_NAME)) else it })
        }

        fun setClipWeight(
            characterKey: String,
            clipKey: String,
            weight: Double,
        ) = editCharacter(characterKey) {
            copy(
                clips =
                    clips.map {
                        if (it.key ==
                            clipKey
                        ) {
                            it.copy(weight = weight.coerceIn(MIN_WEIGHT, MAX_WEIGHT))
                        } else {
                            it
                        }
                    },
            )
        }

        /** Plays one clip as a notification would, with the character's vibration. */
        fun previewClip(
            characterKey: String,
            clipKey: String,
        ) {
            val character = state.value.draft?.character(characterKey) ?: return
            val clip = character.clips.firstOrNull { it.key == clipKey } ?: return
            viewModelScope.launch { preview.play(tools.media(clip.file), character) }
        }

        fun stopPreview() = preview.stop()

        private fun loadSeedSuggestions(key: String) {
            val sprite =
                state.value.draft
                    ?.character(key)
                    ?.sprite ?: return
            if (state.value.seedSuggestions.containsKey(key)) return
            viewModelScope.launch {
                val bitmap = tools.spriteBitmap(sprite) ?: return@launch
                val seeds = tools.seedColors(bitmap)
                bitmap.recycle()
                setState { copy(seedSuggestions = seedSuggestions + (key to seeds)) }
            }
        }

        // endregion

        // region Pictures

        /** Opens a picked picture in the cropper, for a character's sprite or the cover. */
        fun pickPicture(
            target: PictureTarget,
            uri: Uri,
        ) {
            workJob?.cancel()
            setState { copy(busy = Busy(Busy.Kind.OPENING_PICTURE)) }
            workJob =
                viewModelScope.launch {
                    try {
                        val picture = tools.decodePicture(uri)
                        setState { copy(busy = null, cropper = CropperUi(target, picture)) }
                        push(CreatorPage.Cropper)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        setState { copy(busy = null, message = CreatorMessage.PictureFailed) }
                    }
                }
        }

        fun setCrop(crop: SpriteCrop) = setState { copy(cropper = cropper?.copy(crop = crop)) }

        fun confirmCrop() {
            val cropper = state.value.cropper ?: return
            if (state.value.busy != null) return
            setState { copy(busy = Busy(Busy.Kind.SAVING_PICTURE)) }
            viewModelScope.launch {
                try {
                    val target = cropper.target
                    val prefix = if (target is PictureTarget.Cover) "cover" else "sprite"
                    val (name, seeds) = tools.savePicture(cropper.picture, cropper.crop, prefix)
                    when (target) {
                        PictureTarget.Cover -> {
                            edit { copy(cover = name) }
                        }

                        is PictureTarget.Sprite -> {
                            editCharacter(
                                target.characterKey,
                            ) { copy(sprite = name, seedColor = seeds.firstOrNull()?.let(::hexColor)) }
                            setState { copy(seedSuggestions = seedSuggestions + (target.characterKey to seeds)) }
                        }
                    }
                    setState { copy(busy = null) }
                    if (pages.last() == CreatorPage.Cropper) pop()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    setState { copy(busy = null, message = CreatorMessage.PictureFailed) }
                }
            }
        }

        // endregion

        // region Recording

        fun openRecorder(characterKey: String) {
            setState { copy(recorder = RecorderUi(characterKey)) }
            push(CreatorPage.Recorder)
        }

        /** Starts recording. The screen asked for the microphone permission when the user tapped Record. */
        fun startRecording() {
            val ui = state.value.recorder ?: return
            if (ui.recording) return
            val recording = tools.recorder().also { mic = it }
            setState { copy(recorder = ui.copy(recording = true, elapsedMs = 0, levels = emptyList())) }
            recordJob =
                viewModelScope.launch {
                    try {
                        val audio =
                            recording.record { level, elapsed ->
                                mutableState.update { s ->
                                    val r = s.recorder ?: return@update s
                                    s.copy(
                                        recorder =
                                            r.copy(
                                                elapsedMs = elapsed,
                                                levels = (r.levels + level).takeLast(LEVEL_HISTORY),
                                            ),
                                    )
                                }
                            }
                        if (pages.last() == CreatorPage.Recorder) pop()
                        openClipEditor(ui.characterKey, audio, truncated = false)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: SecurityException) {
                        setState {
                            copy(
                                recorder = recorder?.copy(recording = false),
                                message = CreatorMessage.MicDenied,
                            )
                        }
                    } catch (e: Exception) {
                        setState {
                            copy(
                                recorder = recorder?.copy(recording = false),
                                message = CreatorMessage.MicUnavailable,
                            )
                        }
                    } finally {
                        mic = null
                    }
                }
        }

        /** Ends the recording and opens it in the clip editor. */
        fun stopRecording() {
            mic?.stop()
        }

        fun micDenied() = setState { copy(message = CreatorMessage.MicDenied) }

        private fun cancelRecording() {
            recordJob?.cancel()
            recordJob = null
            mic = null
        }

        // endregion

        // region Clip editor

        /** Decodes a picked audio file and opens it in the clip editor. */
        fun pickAudio(
            characterKey: String,
            uri: Uri,
        ) {
            workJob?.cancel()
            setState { copy(busy = Busy(Busy.Kind.OPENING_AUDIO)) }
            workJob =
                viewModelScope.launch {
                    try {
                        val decoded =
                            tools.decodeAudio(uri) { progress ->
                                mutableState.update { it.copy(busy = it.busy?.copy(progress = progress)) }
                            }
                        setState { copy(busy = null) }
                        openClipEditor(characterKey, decoded.audio, decoded.truncated)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: CreatorTools.SourceTooLargeException) {
                        setState { copy(busy = null, message = CreatorMessage.AudioTooLarge) }
                    } catch (e: Exception) {
                        setState { copy(busy = null, message = CreatorMessage.AudioFailed) }
                    }
                }
        }

        private fun openClipEditor(
            characterKey: String,
            audio: PcmAudio,
            truncated: Boolean,
        ) {
            val duration = audio.durationMs
            // Short files and recordings open whole; from a song, the editor starts on its first seconds.
            val end = if (duration <= ClipLimits.MAX_MS) duration else DEFAULT_CUT_MS
            setState {
                copy(
                    clipEditor = ClipEditorUi(characterKey, audio, startMs = 0, endMs = end, truncated = truncated),
                )
            }
            push(CreatorPage.ClipEditor)
        }

        fun setRange(
            startMs: Long,
            endMs: Long,
        ) = editClip {
            val start = startMs.coerceIn(0, source.durationMs)
            copy(startMs = start, endMs = endMs.coerceIn(start, source.durationMs), problem = null)
        }

        /** Shows the selection with some room around it, for precise cuts in a long file. */
        fun zoomIn() =
            editClip {
                val pad = maxOf(selectionMs / 2, MIN_ZOOM_PAD_MS)
                copy(
                    viewStartMs = (startMs - pad).coerceAtLeast(0),
                    viewEndMs = (endMs + pad).coerceAtMost(source.durationMs),
                )
            }

        fun zoomOut() = editClip { copy(viewStartMs = 0, viewEndMs = source.durationMs) }

        fun setTrimSilence(on: Boolean) = editClip { copy(trimSilence = on, problem = null) }

        fun setNormalize(on: Boolean) = editClip { copy(normalize = on) }

        fun setClipEditorLabel(label: String) = editClip { copy(label = label.take(MAX_NAME)) }

        /** Plays the selection, or stops it. */
        fun togglePlayback() {
            val editor = state.value.clipEditor ?: return
            if (playJob?.isActive == true) {
                stopPlayback()
                return
            }
            playJob =
                viewModelScope.launch {
                    try {
                        player.play(editor.source, editor.startMs, editor.endMs) { position ->
                            mutableState.update { s -> s.copy(clipEditor = s.clipEditor?.copy(playheadMs = position)) }
                        }
                    } finally {
                        mutableState.update { s -> s.copy(clipEditor = s.clipEditor?.copy(playheadMs = null)) }
                    }
                }
        }

        private fun stopPlayback() {
            playJob?.cancel()
            playJob = null
        }

        /** Trims, evens out, encodes and adds the clip to its character. */
        fun saveClip() {
            val editor = state.value.clipEditor ?: return
            if (editor.saving) return
            stopPlayback()
            editClip { copy(saving = true, problem = null) }
            viewModelScope.launch {
                val edit = ClipEdit(editor.startMs, editor.endMs, editor.trimSilence, editor.normalize)
                val problem =
                    try {
                        when (val result = tools.saveClip(editor.source, edit, editor.label)) {
                            is ClipResult.Saved -> {
                                editCharacter(editor.characterKey) { copy(clips = clips + result.clip) }
                                if (pages.last() == CreatorPage.ClipEditor) pop()
                                setState { copy(message = CreatorMessage.ClipSaved) }
                                null
                            }

                            is ClipResult.OutOfRange -> {
                                ClipProblem.OutOfRange(result.durationMs)
                            }

                            ClipResult.Silent -> {
                                ClipProblem.Silent
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ClipProblem.Failed
                    }
                editClip { copy(saving = false, problem = problem) }
            }
        }

        // endregion

        // region Finish

        /** Builds the pack and checks it with every rule: the "Check and finish" sheet. */
        fun check() {
            val start = state.value.draft ?: return
            setState { copy(finish = FinishUi.Checking) }
            viewModelScope.launch {
                try {
                    // Installing again must update the library's copy, never ask about the same version.
                    val id = start.packId ?: Authoring.suggestPackId(start.author, start.name, start.idSuffix)
                    val installed =
                        catalog.installed
                            .first()
                            .firstOrNull { it.id == id }
                            ?.version
                    val version = Authoring.versionAfter(start.version, installed)
                    if (version != start.version) edit { copy(version = version) }
                    val draft = state.value.draft ?: return@launch
                    val finish =
                        when (val result = tools.build(draft)) {
                            is WriteResult.Incomplete -> {
                                FinishUi.Incomplete(result.problems)
                            }

                            is WriteResult.Written -> {
                                if (result.isValid) {
                                    // A license in the person's own words is a choice here, not a mistake.
                                    val warnings =
                                        result.report.warnings.filterNot {
                                            it.code == ErrorCode.LICENSE_NOT_SPDX &&
                                                draft.license == Authoring.PERSONAL_USE_LICENSE
                                        }
                                    FinishUi.Ready(result.file, result.manifest, warningItems(warnings))
                                } else {
                                    FinishUi.Invalid(problemFor(result.report))
                                }
                            }
                        }
                    setState { copy(finish = finish) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    setState { copy(finish = FinishUi.Invalid(problemFor(FailureReason.UNEXPECTED, e.toString()))) }
                }
            }
        }

        /** Installs the checked pack into the library, through the same importer as any pack file. */
        fun install() {
            val ready = state.value.finish as? FinishUi.Ready ?: return
            if (ready.installing) return
            setState { copy(finish = ready.copy(installing = true, progress = null)) }
            viewModelScope.launch {
                try {
                    var outcome: ImportStep? = null
                    catalog.prepare(ImportSource.of(ready.file)).collect { step ->
                        if (step is ImportStep.Working) {
                            updateReady { copy(progress = step.progress) }
                        } else {
                            outcome =
                                step
                        }
                    }
                    val prepared = outcome
                    if (prepared is ImportStep.Ready) {
                        val ids =
                            prepared.review.packs
                                .map { it.id }
                                .toSet()
                        outcome = null
                        catalog.install(prepared.review, PackSelection(ids, ids)).collect { step ->
                            if (step is ImportStep.Working) {
                                updateReady { copy(progress = step.progress) }
                            } else {
                                outcome =
                                    step
                            }
                        }
                    }
                    when (val done = outcome) {
                        is ImportStep.Installed -> {
                            edit { withFixedIds(ready.manifest) }
                            updateReady { copy(installing = false, installed = true, progress = null) }
                            setState { copy(message = CreatorMessage.Installed(ready.manifest.name)) }
                        }

                        is ImportStep.Invalid -> {
                            failInstall(problemFor(done.report, done.detected))
                        }

                        is ImportStep.Failed -> {
                            failInstall(problemFor(done.reason, done.message))
                        }

                        else -> {
                            failInstall(problemFor(FailureReason.UNEXPECTED, "No install result"))
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failInstall(problemFor(FailureReason.UNEXPECTED, e.toString()))
                }
            }
        }

        private fun failInstall(problem: ImportProblem) {
            updateReady { copy(installing = false, progress = null) }
            setState { copy(message = CreatorMessage.InstallFailed(problem)) }
        }

        /** Copies the checked pack to a document the user created with the system file picker. */
        fun export(target: Uri) {
            val ready = state.value.finish as? FinishUi.Ready ?: return
            setState { copy(busy = Busy(Busy.Kind.COPYING_PACK)) }
            viewModelScope.launch {
                val message =
                    try {
                        tools.export(ready.file, target)
                        edit { withFixedIds(ready.manifest) }
                        CreatorMessage.Exported
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: IOException) {
                        CreatorMessage.ExportFailed
                    } catch (e: SecurityException) {
                        CreatorMessage.ExportFailed
                    }
                setState { copy(busy = null, message = message) }
            }
        }

        /** A content URI of the checked pack for the share sheet, or null when it cannot be prepared. */
        suspend fun shareUri(): Uri? {
            val ready = state.value.finish as? FinishUi.Ready ?: return null
            return try {
                tools.shareUri(ready.file, ready.fileName).also { edit { withFixedIds(ready.manifest) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { copy(message = CreatorMessage.ExportFailed) }
                null
            }
        }

        fun closeFinish() {
            val finish = state.value.finish
            if (finish is FinishUi.Ready && finish.installing) return
            setState { copy(finish = null) }
        }

        // endregion

        fun messageShown() = setState { copy(message = null) }

        fun hasMicPermission(): Boolean = tools.hasMicPermission()

        /** The file of a draft media name, for previews. */
        fun media(name: String): File = tools.media(name)

        override fun onCleared() {
            recordJob?.cancel()
            playJob?.cancel()
            preview.stop()
        }

        private fun edit(change: CreatorDraft.() -> CreatorDraft) {
            var saved: CreatorDraft? = null
            mutableState.update { s ->
                val draft = s.draft ?: return@update s
                val next = draft.change()
                saved = next
                s.copy(draft = next)
            }
            saved?.let(tools::saveSoon)
        }

        private fun editCharacter(
            key: String,
            change: DraftCharacter.() -> DraftCharacter,
        ) = edit { updateCharacter(key) { it.change() } }

        private fun editClip(change: ClipEditorUi.() -> ClipEditorUi) =
            mutableState.update { s -> s.clipEditor?.let { s.copy(clipEditor = it.change()) } ?: s }

        private fun updateReady(change: FinishUi.Ready.() -> FinishUi.Ready) =
            mutableState.update { s -> (s.finish as? FinishUi.Ready)?.let { s.copy(finish = it.change()) } ?: s }

        private inline fun setState(change: CreatorUiState.() -> CreatorUiState) = mutableState.update { it.change() }

        companion object {
            private const val MAX_NAME = 80
            private const val MAX_AUTHOR = 120
            private const val MAX_LICENSE = 200
            private const val MAX_DESCRIPTION = 2_000
            const val MIN_WEIGHT = 0.1
            const val MAX_WEIGHT = 100.0
            private const val LEVEL_HISTORY = 48
            private const val DEFAULT_CUT_MS = 5_000L
            private const val MIN_ZOOM_PAD_MS = 500L

            /** `#RRGGBB` for an ARGB color (alpha dropped). */
            fun hexColor(argb: Int): String = String.format(Locale.ROOT, "#%06X", argb and 0xFFFFFF)
        }
    }
