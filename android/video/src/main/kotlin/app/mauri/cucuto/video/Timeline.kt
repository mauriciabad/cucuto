package app.mauri.cucuto.video

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable

/** How a shot fills the frame: the illustrated phone on the stage, or the whole 16:9 frame (door, end card). */
internal enum class ShotFrame { Phone, Full }

/** Which side of the phone a character pops out on when it speaks. */
internal enum class Side { Left, Right }

/**
 * A sound at [at] seconds into its shot: a file relative to the repository (CC0 demo-pack clips and brand sounds
 * only), its gain, an optional ffmpeg effect (`octave-down` makes the plain ping), and optionally the [pop]: the
 * character jumping out beside the phone while it speaks.
 */
@Immutable
internal data class Cue(
    val at: Double,
    val file: String,
    val gainDb: Double,
    val effect: String = "",
    val pop: Character? = null,
    val side: Side = Side.Left,
)

/**
 * A caption cue (WebVTT, SDH) from [at] to [until] seconds into its shot, in English and Spanish. Lines break with
 * `\n` (at most 2 lines of 42 characters, docs/explainer-video.md#captions). [spoken] is the voice-over's text when
 * the cue is a voice line (the English text with the line breaks removed, unless the TTS needs a respelling), or null
 * for a sound.
 */
@Immutable
internal data class Caption(
    val id: String,
    val at: Double,
    val until: Double,
    val en: String,
    val es: String,
    val spoken: String? = null,
) {
    val isVoice: Boolean get() = spoken != null
}

/** A voice line: its caption is the English text, and the narrator says [spoken] (by default, the same words). */
internal fun voice(
    id: String,
    at: Double,
    until: Double,
    en: String,
    es: String,
    spoken: String = en.replace('\n', ' '),
) = Caption(id, at, until, en, es, spoken)

/** A sound caption ("[Cat mews]"). */
internal fun sound(
    id: String,
    at: Double,
    until: Double,
    en: String,
    es: String,
) = Caption(id, at, until, en, es)

/**
 * One shot of the video. All times inside a shot are seconds from its start, so inserting a shot moves everything
 * after it, sounds, voice and captions included. [content] draws the picture for the time in [ShotScope]: the phone's
 * screen for [ShotFrame.Phone] shots, the whole frame for [ShotFrame.Full].
 */
@Immutable
internal class Shot(
    val id: String,
    val duration: Double,
    val frame: ShotFrame,
    val captions: List<Caption> = emptyList(),
    val cues: List<Cue> = emptyList(),
    /** When the phone vibrates (drawn as ripples around it, and named in the captions). */
    val vibrations: List<Double> = emptyList(),
    /** Extra drawing on the stage around the phone (the characters that speak pop out on their own, see [Cue.pop]). */
    val stage: @Composable ShotScope.() -> Unit = {},
    val content: @Composable ShotScope.() -> Unit,
)

/** A [Shot] placed on the video's timeline. */
@Immutable
internal data class Placed(
    val number: Int,
    val shot: Shot,
    val start: Double,
) {
    val end: Double get() = start + shot.duration
}

/** The video: shots back to back. */
@Immutable
internal class Timeline(
    val shots: List<Shot>,
) {
    val placed: List<Placed> =
        shots.runningFold(0.0) { start, shot -> start + shot.duration }.zip(shots).mapIndexed { i, (start, shot) ->
            Placed(i + 1, shot, start)
        }

    val duration: Double get() = placed.last().end

    /** The shot on screen at [time] seconds. */
    fun at(time: Double): Placed = placed.lastOrNull { time >= it.start } ?: placed.first()

    /** Every caption with its time on the video, in order. */
    fun captions(): List<Caption> =
        placed
            .flatMap { p -> p.shot.captions.map { it.copy(at = p.start + it.at, until = p.start + it.until) } }
            .sortedBy { it.at }

    /** Every sound with its time on the video. */
    fun cues(): List<Cue> = placed.flatMap { p -> p.shot.cues.map { it.copy(at = p.start + it.at) } }

    /** Every vibration on the video (seconds). */
    fun vibrations(): List<Double> = placed.flatMap { p -> p.shot.vibrations.map { p.start + it } }
}

/** Gains of the mix (docs/explainer-video.md#sound-design): UI sounds sit 6 dB under the character clips. */
internal object Mix {
    const val CLIP_DB = -3.0
    const val UI_DB = -9.0
    const val PING_DB = -6.0
}
