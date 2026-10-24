package app.mauri.cucuto.ui.creator

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import app.mauri.cucuto.R
import app.mauri.cucuto.ui.feedback.Feedback
import app.mauri.cucuto.ui.feedback.LocalFeedback

/**
 * The in-app pack creator (#47): a pack in a few steps, all on the phone. The Packs screen opens it ("Create a pack",
 * or "Duplicate and edit" with [sourcePackId]). Its own pages (pack, character, picture framing, recording, clip
 * editing) stack inside this one destination; system back walks them back before leaving. "Check and finish" is a
 * screen of its own above it ([onOpenFinish], [CreatorFinishScreen], ADR 0055) on this view model.
 *
 * @param onEditHaptic Opens the haptic editor for a character (the key of [CreatorViewModel.setCustomHaptic]); null
 *   until the editor (#32) is wired, which hides the custom option for characters that have no pattern yet.
 */
@Composable
fun CreatorScreen(
    sourcePackId: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenFinish: () -> Unit = {},
    onEditHaptic: ((characterKey: String) -> Unit)? = null,
    viewModel: CreatorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val feedback = LocalFeedback.current
    val snackbar = remember { SnackbarHostState() }
    val currentOnMessage by rememberUpdatedState(viewModel::messageShown)

    LaunchedEffect(viewModel, sourcePackId) {
        viewModel.start(sourcePackId) { resources.getString(R.string.creator_copy_name, it) }
    }

    // Pickers remember what they pick for: a character key, or COVER.
    var pictureFor by rememberSaveable { mutableStateOf<String?>(null) }
    var audioFor by rememberSaveable { mutableStateOf<String?>(null) }
    val picturePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val target = pictureFor
            if (uri != null && target != null) {
                viewModel.pickPicture(if (target == COVER) PictureTarget.Cover else PictureTarget.Sprite(target), uri)
            }
        }
    val pickPicture: (String) -> Unit = { target ->
        pictureFor = target
        picturePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    val audioPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val key = audioFor
            if (uri != null && key != null) viewModel.pickAudio(key, uri)
        }
    val pickAudio: (String) -> Unit = { key ->
        audioFor = key
        audioPicker.launch(arrayOf("audio/*"))
    }
    // The microphone is asked for on the Record tap, never before (brief: minimum intrusion).
    val micPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) viewModel.startRecording() else viewModel.micDenied()
        }
    val record = {
        if (viewModel.hasMicPermission()) {
            viewModel.startRecording()
        } else {
            micPermission.launch(
                Manifest.permission.RECORD_AUDIO,
            )
        }
    }
    val backState = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)
    NavigationBackHandler(
        state = backState,
        isBackEnabled = state.page != CreatorPage.Pack,
        onBackCompleted = { viewModel.back() },
    )
    val goBack = { if (!viewModel.back()) onBack() }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        feedback.fire(feedbackFor(message))
        val text = messageText(resources, message)
        currentOnMessage()
        snackbar.showSnackbar(text)
    }

    Box(modifier.fillMaxSize()) {
        val draft = state.draft
        if (draft == null) {
            Surface(Modifier.fillMaxSize()) {
                Box(contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(56.dp)) }
            }
        } else {
            val motion = MaterialTheme.motionScheme
            AnimatedContent(
                targetState = state.page,
                transitionSpec = {
                    val forward = depth(targetState) >= depth(initialState)
                    val direction = if (forward) 1 else -1
                    (
                        slideInHorizontally(motion.defaultSpatialSpec()) { direction * it / 4 } +
                            fadeIn(motion.defaultEffectsSpec())
                    ) togetherWith (
                        slideOutHorizontally(motion.defaultSpatialSpec()) { -direction * it / 4 } +
                            fadeOut(motion.fastEffectsSpec())
                    )
                },
                label = "creator page",
            ) { page ->
                when (page) {
                    CreatorPage.Pack -> {
                        PackPage(
                            draft = draft,
                            media = viewModel::media,
                            onBack = goBack,
                            onName = viewModel::setName,
                            onAuthor = viewModel::setAuthor,
                            onLicense = viewModel::setLicense,
                            onDescription = viewModel::setDescription,
                            onPickCover = { pickPicture(COVER) },
                            onRemoveCover = viewModel::removeCover,
                            onOpenCharacter = viewModel::openCharacter,
                            onAddCharacter = viewModel::addCharacter,
                            onCheck = {
                                viewModel.check()
                                onOpenFinish()
                            },
                            onStartOver = viewModel::startNew,
                        )
                    }

                    is CreatorPage.Character -> {
                        val character = draft.character(page.key)
                        if (character != null) {
                            CharacterPage(
                                character = character,
                                suggestions = state.seedSuggestions[page.key].orEmpty(),
                                media = viewModel::media,
                                canEditHaptic = onEditHaptic != null,
                                onBack = goBack,
                                onName = { viewModel.setCharacterName(page.key, it) },
                                onPickSprite = { pickPicture(page.key) },
                                onSeedColor = { viewModel.setSeedColor(page.key, it) },
                                onRecord = { viewModel.openRecorder(page.key) },
                                onPickAudio = { pickAudio(page.key) },
                                onPlayClip = { viewModel.previewClip(page.key, it) },
                                onClipLabel = { clip, label -> viewModel.setClipLabel(page.key, clip, label) },
                                onClipWeight = { clip, weight -> viewModel.setClipWeight(page.key, clip, weight) },
                                onRemoveClip = { viewModel.removeClip(page.key, it) },
                                onHaptic = { viewModel.setHaptic(page.key, it) },
                                onEditHaptic = { onEditHaptic?.invoke(page.key) },
                                onRemove = { viewModel.removeCharacter(page.key) },
                            )
                        }
                    }

                    CreatorPage.Cropper -> {
                        state.cropper?.let { cropper ->
                            CropperPage(
                                cropper = cropper,
                                saving = state.busy?.kind == Busy.Kind.SAVING_PICTURE,
                                onBack = goBack,
                                onCrop = viewModel::setCrop,
                                onDone = viewModel::confirmCrop,
                            )
                        }
                    }

                    CreatorPage.Recorder -> {
                        state.recorder?.let { recorder ->
                            RecorderPage(
                                recorder = recorder,
                                onBack = goBack,
                                onRecord = record,
                                onStop = viewModel::stopRecording,
                            )
                        }
                    }

                    CreatorPage.ClipEditor -> {
                        state.clipEditor?.let { editor ->
                            ClipEditorPage(
                                editor = editor,
                                onBack = goBack,
                                onRange = viewModel::setRange,
                                onZoomIn = viewModel::zoomIn,
                                onZoomOut = viewModel::zoomOut,
                                onPlay = viewModel::togglePlayback,
                                onTrimSilence = viewModel::setTrimSilence,
                                onNormalize = viewModel::setNormalize,
                                onLabel = viewModel::setClipEditorLabel,
                                onSave = viewModel::saveClip,
                            )
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp))
    }

    state.prompt?.let { prompt ->
        StartPromptDialog(
            prompt = prompt,
            onKeep = viewModel::keepDraft,
            onStartNew = viewModel::startNew,
            onReplace = viewModel::replaceWithCopy,
        )
    }
    state.busy?.takeIf { it.kind != Busy.Kind.SAVING_PICTURE }?.let { BusyDialog(it) }
}

/** What the picture picker picks for when it is not a character. */
private const val COVER = "\u0000cover"

private fun depth(page: CreatorPage): Int =
    when (page) {
        CreatorPage.Pack -> 0
        is CreatorPage.Character -> 1
        CreatorPage.Recorder -> 2
        CreatorPage.Cropper, CreatorPage.ClipEditor -> 3
    }

@Composable
private fun StartPromptDialog(
    prompt: StartPrompt,
    onKeep: () -> Unit,
    onStartNew: () -> Unit,
    onReplace: () -> Unit,
) {
    when (prompt) {
        is StartPrompt.Resume -> {
            AlertDialog(
                onDismissRequest = onKeep,
                title = { Text(stringResource(R.string.creator_resume_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.creator_resume_body,
                            prompt.name.ifBlank { stringResource(R.string.creator_untitled) },
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = onKeep,
                    ) { Text(stringResource(R.string.creator_resume_continue)) }
                },
                dismissButton = {
                    TextButton(
                        onClick = onStartNew,
                    ) { Text(stringResource(R.string.creator_resume_new)) }
                },
            )
        }

        is StartPrompt.ReplaceWithCopy -> {
            AlertDialog(
                onDismissRequest = onKeep,
                title = { Text(stringResource(R.string.creator_replace_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.creator_replace_body,
                            prompt.name.ifBlank {
                                stringResource(R.string.creator_untitled)
                            },
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = onReplace,
                    ) { Text(stringResource(R.string.creator_replace_confirm)) }
                },
                dismissButton = {
                    TextButton(
                        onClick = onKeep,
                    ) { Text(stringResource(R.string.creator_replace_keep)) }
                },
            )
        }
    }
}

/** A blocking "working on it" card for decoding and copying, with progress when known. */
@Composable
internal fun BusyDialog(busy: Busy) {
    Dialog(onDismissRequest = {
    }, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(
                Modifier.padding(24.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LoadingIndicator(Modifier.size(48.dp))
                Text(stringResource(busyText(busy.kind)), style = MaterialTheme.typography.titleMedium)
                busy.progress?.let {
                    LinearWavyProgressIndicator(
                        progress = { it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private fun busyText(kind: Busy.Kind): Int =
    when (kind) {
        Busy.Kind.OPENING_PICTURE -> R.string.creator_busy_picture
        Busy.Kind.SAVING_PICTURE -> R.string.creator_busy_saving_picture
        Busy.Kind.OPENING_AUDIO -> R.string.creator_busy_audio
        Busy.Kind.COPYING_PACK -> R.string.creator_busy_copying
    }

internal fun feedbackFor(message: CreatorMessage): Feedback =
    when (message) {
        is CreatorMessage.Installed, CreatorMessage.Exported -> Feedback.Success
        CreatorMessage.ClipSaved, is CreatorMessage.Duplicated -> Feedback.Confirm
        else -> Feedback.Error
    }

internal fun messageText(
    resources: android.content.res.Resources,
    message: CreatorMessage,
): String =
    when (message) {
        is CreatorMessage.Installed -> {
            resources.getString(R.string.creator_message_installed, message.name)
        }

        CreatorMessage.Exported -> {
            resources.getString(R.string.creator_message_exported)
        }

        CreatorMessage.ExportFailed -> {
            resources.getString(R.string.creator_message_export_failed)
        }

        is CreatorMessage.InstallFailed -> {
            resources.getString(R.string.creator_message_install_failed, resources.getString(message.problem.summary))
        }

        CreatorMessage.PictureFailed -> {
            resources.getString(R.string.creator_message_picture_failed)
        }

        CreatorMessage.AudioFailed -> {
            resources.getString(R.string.creator_message_audio_failed)
        }

        CreatorMessage.AudioTooLarge -> {
            resources.getString(R.string.creator_message_audio_too_large)
        }

        CreatorMessage.MicUnavailable -> {
            resources.getString(R.string.creator_message_mic_unavailable)
        }

        CreatorMessage.MicDenied -> {
            resources.getString(R.string.creator_message_mic_denied)
        }

        CreatorMessage.ClipSaved -> {
            resources.getString(R.string.creator_message_clip_saved)
        }

        is CreatorMessage.Duplicated -> {
            if (message.droppedRingtones + message.droppedAnimations == 0) {
                resources.getString(R.string.creator_message_duplicated)
            } else {
                resources.getString(R.string.creator_message_duplicated_partly)
            }
        }

        CreatorMessage.DuplicateFailed -> {
            resources.getString(R.string.creator_message_duplicate_failed)
        }
    }
