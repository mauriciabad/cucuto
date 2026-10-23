package app.mauri.cucuto.ui.feedback

import android.os.VibrationEffect.Composition
import androidx.annotation.RawRes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import app.mauri.cucuto.R

/**
 * Every in-app feedback event: what Cucuto plays (UI sound), how it feels (haptic) and, through the [FeedbackBeat]
 * that [FeedbackEngine.fire] returns, when the visual answer starts. The mapping is the table in
 * `brand/sound-and-haptics.md`; same interaction, same feedback, everywhere.
 *
 * ```
 * val feedback = LocalFeedback.current
 * val beat = feedback.fire(Feedback.Assign(characterId))
 * ParticleBurst(beat)
 * ```
 */
@Immutable
sealed interface Feedback {
    val sound: UiSound?
    val haptic: UiHaptic?

    /** Taps on cards, chips and grid items. No haptic: Material components and the system handle plain taps. */
    data object Tap : Feedback {
        override val sound = UiSound.Tap
        override val haptic = null
    }

    /** A switch or a clip turning on. */
    data object ToggleOn : Feedback {
        override val sound = UiSound.ToggleOn
        override val haptic = UiHaptic.Standard(HapticFeedbackType.ToggleOn)
    }

    /** A switch or a clip turning off. */
    data object ToggleOff : Feedback {
        override val sound = UiSound.ToggleOff
        override val haptic = UiHaptic.Standard(HapticFeedbackType.ToggleOff)
    }

    /** An action confirmed (save, import confirmed). Haptic only; [Success] is the rich, audible version. */
    data object Confirm : Feedback {
        override val sound = null
        override val haptic = UiHaptic.Standard(HapticFeedbackType.Confirm)
    }

    /** Invalid input or a rejected action. Haptic only; [Error] is the rich, audible version. */
    data object Reject : Feedback {
        override val sound = null
        override val haptic = UiHaptic.Standard(HapticFeedbackType.Reject)
    }

    /** Pack imported, backup written, status card turned green: the success arpeggio and its haptic echo. */
    data object Success : Feedback {
        override val sound = UiSound.Success
        override val haptic =
            UiHaptic.Rich(
                listOf(
                    HapticPrimitive(Composition.PRIMITIVE_TICK, scale = 0.3f, atMs = 0),
                    HapticPrimitive(Composition.PRIMITIVE_TICK, scale = 0.5f, atMs = 60),
                    HapticPrimitive(Composition.PRIMITIVE_CLICK, scale = 0.8f, atMs = 120),
                ),
                fallback = HapticFeedbackType.Confirm,
            )
    }

    /** Invalid pack, failed download: the gentle two-note "nope". */
    data object Error : Feedback {
        override val sound = UiSound.Error
        override val haptic =
            UiHaptic.Rich(
                listOf(
                    HapticPrimitive(Composition.PRIMITIVE_LOW_TICK, scale = 0.8f, atMs = 0),
                    HapticPrimitive(Composition.PRIMITIVE_LOW_TICK, scale = 0.6f, atMs = 90),
                ),
                fallback = HapticFeedbackType.Reject,
            )
    }

    /**
     * A character was assigned: the "cu-cu!" call, the rising haptic, the particle burst and the avatar morph. The
     * character's own clip starts on the call's second note, [CHARACTER_CLIP_DELAY_MS] after the beat
     * ([FeedbackBeat.characterClipAtNanos]).
     */
    data class Assign(
        val characterId: String? = null,
    ) : Feedback {
        override val sound = UiSound.Assign
        override val haptic = AssignHaptic

        companion object {
            /** The second note of `assign.ogg` ("-cu!") starts 130 ms in. */
            const val CHARACTER_CLIP_DELAY_MS = 130L

            private val AssignHaptic =
                UiHaptic.Rich(
                    listOf(
                        HapticPrimitive(Composition.PRIMITIVE_QUICK_RISE, scale = 0.5f, atMs = 0),
                        HapticPrimitive(Composition.PRIMITIVE_CLICK, scale = 0.9f, atMs = 60),
                        HapticPrimitive(Composition.PRIMITIVE_TICK, scale = 0.6f, atMs = CHARACTER_CLIP_DELAY_MS.toInt()),
                    ),
                    fallback = HapticFeedbackType.Confirm,
                )
        }
    }

    /** The cuckoo door pops open to reveal a character (pack preview, onboarding success). */
    data object DoorOpen : Feedback {
        override val sound = UiSound.Assign
        override val haptic =
            UiHaptic.Rich(
                listOf(
                    HapticPrimitive(Composition.PRIMITIVE_TICK, scale = 0.4f, atMs = 0),
                    HapticPrimitive(Composition.PRIMITIVE_CLICK, scale = 0.7f, atMs = 100),
                ),
                fallback = HapticFeedbackType.Confirm,
            )
    }

    /** Long press on a contact or a sprite. */
    data object LongPress : Feedback {
        override val sound = null
        override val haptic = UiHaptic.Standard(HapticFeedbackType.LongPress)
    }

    /** A reorder item passing a neighbor, slider steps, timeline snapping. */
    data object SegmentTick : Feedback {
        override val sound = null
        override val haptic = UiHaptic.Standard(HapticFeedbackType.SegmentTick)
    }

    /** Many fine steps, such as trimming a clip in the waveform editor. */
    data object SegmentFrequentTick : Feedback {
        override val sound = null
        override val haptic = UiHaptic.Standard(HapticFeedbackType.SegmentFrequentTick)
    }

    /** Swipe-to-dismiss or predictive back crossing its threshold. */
    data object GestureThresholdActivate : Feedback {
        override val sound = null
        override val haptic = UiHaptic.Standard(HapticFeedbackType.GestureThresholdActivate)
    }

    /** A gesture released after its threshold. */
    data object GestureEnd : Feedback {
        override val sound = null
        override val haptic = UiHaptic.Standard(HapticFeedbackType.GestureEnd)
    }
}

/** The six CC0 brand UI sounds (`brand/sounds/<name>.ogg`, compiled into `res/raw` by `app/build.gradle.kts`). */
enum class UiSound(
    @param:RawRes val res: Int,
    /** Touch sounds also follow the system "Touch sounds" setting; event sounds only the app's own setting. */
    val isTouchSound: Boolean,
) {
    Tap(R.raw.ui_tap, isTouchSound = true),
    ToggleOn(R.raw.ui_toggle_on, isTouchSound = true),
    ToggleOff(R.raw.ui_toggle_off, isTouchSound = true),
    Success(R.raw.ui_success, isTouchSound = false),
    Error(R.raw.ui_error, isTouchSound = false),
    Assign(R.raw.ui_assign, isTouchSound = false),
}

/** How a [Feedback] feels. */
@Immutable
sealed interface UiHaptic {
    /** A predefined Compose haptic: consistent with the system and adapted to each device. */
    data class Standard(
        val type: HapticFeedbackType,
    ) : UiHaptic

    /**
     * A moment of delight: composition primitives at absolute offsets from the beat, so they line up with the
     * notes of the matching sound. Played only when the device supports every primitive, otherwise [fallback].
     */
    data class Rich(
        val primitives: List<HapticPrimitive>,
        val fallback: HapticFeedbackType,
    ) : UiHaptic
}

/** One `VibrationEffect.Composition` primitive, [atMs] after the start of the effect. */
@Immutable
data class HapticPrimitive(
    val id: Int,
    val scale: Float,
    val atMs: Int,
)

/**
 * The single timestamp a [Feedback] was fired at, in the `System.nanoTime()` time base that Compose frame times use
 * (`withFrameNanos`). Sound and haptic start at [startNanos]; visuals compute their progress from it, so the three
 * channels never drift apart even when a frame is late.
 */
@Immutable
data class FeedbackBeat(
    val event: Feedback,
    val startNanos: Long,
) {
    /** When the character's own clip should start (the assign call's second note). */
    val characterClipAtNanos: Long
        get() = startNanos + Feedback.Assign.CHARACTER_CLIP_DELAY_MS * NANOS_PER_MILLI

    /** Seconds since the beat at frame time [frameNanos]. */
    fun elapsedSeconds(frameNanos: Long): Float = (frameNanos - startNanos) / NANOS_PER_SECOND

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000f
    }
}
