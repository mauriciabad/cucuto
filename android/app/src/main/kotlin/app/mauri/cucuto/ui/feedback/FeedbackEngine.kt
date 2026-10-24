package app.mauri.cucuto.ui.feedback

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.platform.LocalHapticFeedback
import app.mauri.cucuto.core.data.di.ApplicationScope
import app.mauri.cucuto.core.data.settings.UiFeedbackSettings
import app.mauri.cucuto.core.data.settings.UiSoundLevel
import app.mauri.cucuto.ui.motion.ProvideReducedMotionScheme
import app.mauri.cucuto.ui.theme.LocalReducedMotion
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place in-app feedback comes from (brief, "Juice checklist"): [fire] takes a single timestamp, starts the
 * UI sound and the haptic on it and returns it as a [FeedbackBeat] for the visual answer (burst, door, morph), so
 * sound, haptic and motion never drift apart.
 *
 * Settings: "UI sounds" (default quiet) and "UI haptics" (default on) from [UiFeedbackSettings]; on top of them the
 * system ringer mode, "Touch sounds" and "Vibration and haptics" settings apply. "Remove animations" (the system's or
 * Cucuto's own, see [ProvideFeedback]) only affects visuals (components read `LocalReducedMotion`): sound and haptics
 * still carry meaning (brand/motion.md).
 *
 * In composables use [LocalFeedback], which binds the engine to the window's [HapticFeedback].
 */
@Singleton
class FeedbackEngine
    @Inject
    constructor(
        @ApplicationContext context: Context,
        settings: UiFeedbackSettings,
        @ApplicationScope scope: CoroutineScope,
    ) {
        private val sounds = UiSoundPlayer(context)
        private val haptics = UiHapticPlayer(context)
        private val beatFlow = MutableSharedFlow<FeedbackBeat>(extraBufferCapacity = BEAT_BUFFER)

        /** The current "UI sounds" level. */
        val soundLevel: StateFlow<UiSoundLevel> =
            settings.soundLevel.stateIn(scope, SharingStarted.Eagerly, UiSoundLevel.Quiet)

        /** The current "UI haptics" switch. */
        val hapticsEnabled: StateFlow<Boolean> = settings.hapticsEnabled.stateIn(scope, SharingStarted.Eagerly, true)

        /** Cucuto's own "Remove animations" (the system setting is read by [LocalReducedMotion]'s provider). */
        val removeAnimations: StateFlow<Boolean> =
            settings.removeAnimations.stateIn(
                scope,
                SharingStarted.Eagerly,
                false,
            )

        /** Every beat fired, for visuals that are not next to the control that fired them. */
        val beats: SharedFlow<FeedbackBeat> = beatFlow.asSharedFlow()

        init {
            scope.launch(Dispatchers.IO) { sounds.load() }
        }

        /** Whether this device plays the rich composition of [feedback] (false: its standard fallback plays). */
        fun supportsRichHaptic(feedback: Feedback): Boolean =
            (feedback.haptic as? UiHaptic.Rich)?.let(haptics::supportsRich) ?: false

        /**
         * Fires [feedback] now. Audio starts first (its output path is the slowest), then the haptic; both are
         * non-blocking. [standardHaptics] plays the predefined types (pass the window's `LocalHapticFeedback`).
         */
        fun fire(
            feedback: Feedback,
            standardHaptics: HapticFeedback?,
        ): FeedbackBeat {
            val beat = FeedbackBeat(feedback, System.nanoTime())
            feedback.sound?.let { sounds.play(it, soundLevel.value.volume) }
            if (hapticsEnabled.value) feedback.haptic?.let { haptics.play(it, standardHaptics) }
            beatFlow.tryEmit(beat)
            return beat
        }

        private companion object {
            const val BEAT_BUFFER = 8
        }
    }

/**
 * The handle composables use: `LocalFeedback.current.fire(Feedback.Assign(id))`. In previews (no engine) it only
 * returns the beat, so visuals still animate. [nanoTime] stamps those engine-less beats: tests that step Compose's
 * test clock pass its time, so beat-driven motion (door, burst) plays frame by frame (the explainer video, ADR 0060).
 */
@Stable
class FeedbackController internal constructor(
    private val engine: FeedbackEngine?,
    private val haptics: HapticFeedback?,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    fun fire(feedback: Feedback): FeedbackBeat = engine?.fire(feedback, haptics) ?: FeedbackBeat(feedback, nanoTime())
}

val LocalFeedback = staticCompositionLocalOf { FeedbackController(engine = null, haptics = null) }

/**
 * Provides [LocalFeedback] for [content], bound to this window's haptics. Also turns [LocalReducedMotion] on when
 * Cucuto's own "Remove animations" setting is on (the theme already sets it from the system setting).
 */
@Composable
fun ProvideFeedback(
    engine: FeedbackEngine,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val controller = remember(engine, haptics) { FeedbackController(engine, haptics) }
    val removeAnimations by engine.removeAnimations.collectAsState()
    CompositionLocalProvider(
        LocalFeedback provides controller,
        LocalReducedMotion provides (LocalReducedMotion.current || removeAnimations),
    ) {
        // Cucuto's own "Remove animations" snaps Material and Cucuto motion too, like the system setting does.
        ProvideReducedMotionScheme(content)
    }
}
