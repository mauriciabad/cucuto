package app.mauri.cucuto.ui.creator

import android.graphics.Bitmap
import androidx.compose.runtime.Immutable
import app.mauri.cucuto.core.audio.PcmAudio
import app.mauri.cucuto.core.creator.CreatorDraft
import app.mauri.cucuto.core.creator.SpriteCrop
import app.mauri.cucuto.core.pack.ImportProgress
import app.mauri.cucuto.packs.ImportProblem
import app.mauri.cucuto.packs.ProblemItem
import app.mauri.cucuto.packspec.PackManifest
import app.mauri.cucuto.packspec.authoring.Authoring
import app.mauri.cucuto.packspec.authoring.DraftProblem
import java.io.File

/** Everything the creator shows. */
@Immutable
data class CreatorUiState(
    /** Null while the saved draft loads. */
    val draft: CreatorDraft? = null,
    /** The page on top; [CreatorPage.Pack] is the bottom of the creator's own back stack. */
    val page: CreatorPage = CreatorPage.Pack,
    val prompt: StartPrompt? = null,
    val cropper: CropperUi? = null,
    val clipEditor: ClipEditorUi? = null,
    val recorder: RecorderUi? = null,
    /** Seed color suggestions (ARGB, best first) for each character key, from its sprite. */
    val seedSuggestions: Map<String, List<Int>> = emptyMap(),
    val busy: Busy? = null,
    val finish: FinishUi? = null,
    val message: CreatorMessage? = null,
)

/** The creator's pages, deepest last. */
sealed interface CreatorPage {
    data object Pack : CreatorPage

    data class Character(
        val key: String,
    ) : CreatorPage

    /** Framing a picture: [CropperUi] has the picture and the target. */
    data object Cropper : CreatorPage

    data object Recorder : CreatorPage

    data object ClipEditor : CreatorPage
}

/** A question before the creator starts. */
sealed interface StartPrompt {
    /** An unfinished pack is saved: continue it or start a new one. */
    data class Resume(
        val name: String,
    ) : StartPrompt

    /** The user asked to duplicate a pack, but an unfinished pack is saved: replace it or keep it. */
    data class ReplaceWithCopy(
        val name: String,
        val sourcePackId: String,
    ) : StartPrompt
}

/** Where a framed picture goes. */
sealed interface PictureTarget {
    data class Sprite(
        val characterKey: String,
    ) : PictureTarget

    data object Cover : PictureTarget
}

@Immutable
data class CropperUi(
    val target: PictureTarget,
    val picture: Bitmap,
    val crop: SpriteCrop = SpriteCrop(),
) {
    val aspect: Float get() = picture.width.toFloat() / picture.height
}

@Immutable
data class RecorderUi(
    val characterKey: String,
    val recording: Boolean = false,
    val elapsedMs: Long = 0,
    /** Recent peak levels (0..1), oldest first, for the level meter. */
    val levels: List<Float> = emptyList(),
)

@Immutable
data class ClipEditorUi(
    val characterKey: String,
    val source: PcmAudio,
    val startMs: Long,
    val endMs: Long,
    /** The part of the source the waveform shows (zoomed in on long files). */
    val viewStartMs: Long = 0,
    val viewEndMs: Long = source.durationMs,
    val trimSilence: Boolean = true,
    val normalize: Boolean = true,
    val label: String = "",
    /** The playhead while the selection plays. */
    val playheadMs: Long? = null,
    /** The file was longer than six minutes and only its start was opened. */
    val truncated: Boolean = false,
    val saving: Boolean = false,
    /** The last save attempt's problem, shown under the waveform. */
    val problem: ClipProblem? = null,
) {
    val selectionMs: Long get() = endMs - startMs
    val zoomed: Boolean get() = viewStartMs > 0 || viewEndMs < source.durationMs
}

sealed interface ClipProblem {
    data class OutOfRange(
        val durationMs: Long,
    ) : ClipProblem

    data object Silent : ClipProblem

    data object Failed : ClipProblem
}

/** Long work in progress, shown as a blocking indicator. [progress] is 0..1 when known. */
data class Busy(
    val kind: Kind,
    val progress: Float? = null,
) {
    enum class Kind { OPENING_PICTURE, SAVING_PICTURE, OPENING_AUDIO, COPYING_PACK }
}

/** The "Check and finish" screen. */
sealed interface FinishUi {
    data object Checking : FinishUi

    /** The draft is not complete yet. */
    data class Incomplete(
        val problems: List<DraftProblem>,
    ) : FinishUi

    /** The validator rejected the built pack (should not happen for files the creator made). */
    data class Invalid(
        val problem: ImportProblem,
    ) : FinishUi

    /** Built and valid: install, export or share. */
    data class Ready(
        val file: File,
        val manifest: PackManifest,
        val warnings: List<ProblemItem>,
        val installing: Boolean = false,
        val progress: ImportProgress? = null,
        /** Installed into the library during this visit. */
        val installed: Boolean = false,
    ) : FinishUi {
        val fileName: String get() = Authoring.packFileName(manifest.name, manifest.id)
    }
}

/** One-off results for a snackbar and the matching feedback. */
sealed interface CreatorMessage {
    data class Installed(
        val name: String,
    ) : CreatorMessage

    data object Exported : CreatorMessage

    data object ExportFailed : CreatorMessage

    data class InstallFailed(
        val problem: ImportProblem,
    ) : CreatorMessage

    data object PictureFailed : CreatorMessage

    data object AudioFailed : CreatorMessage

    data object AudioTooLarge : CreatorMessage

    data object MicUnavailable : CreatorMessage

    data object MicDenied : CreatorMessage

    data object ClipSaved : CreatorMessage

    data class Duplicated(
        val droppedRingtones: Int,
        val droppedAnimations: Int,
    ) : CreatorMessage

    data object DuplicateFailed : CreatorMessage
}
