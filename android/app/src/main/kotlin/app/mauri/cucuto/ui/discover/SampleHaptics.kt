package app.mauri.cucuto.ui.discover

import android.content.Context
import android.os.VibrationAttributes
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import app.mauri.cucuto.core.data.settings.UiFeedbackSettings
import app.mauri.cucuto.core.haptics.HapticCapabilities
import app.mauri.cucuto.core.haptics.PublicHapticEffects
import app.mauri.cucuto.core.haptics.auto.AutoHaptics
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.packs.ui.ClipPlayer
import app.mauri.cucuto.packs.ui.rememberClipPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The vibration of Discover's sample previews (#272, ADR 0069). A tap fetches the sample, [prepare] works out its
 * vibration, and [play] starts it on the same callback that starts the clip, so both begin together.
 */
interface SampleHaptics {
    /**
     * The vibration to play with [sample] for a character whose index entry says [haptic], or null for sound only:
     * no haptic in the index, pack mode `none`, a silent clip, or the "App vibration" switch off.
     */
    suspend fun prepare(
        haptic: Haptic?,
        sample: File,
    ): Haptic.Custom?

    /** Starts [haptic] now. */
    fun play(haptic: Haptic.Custom)

    /** Stops the vibration [play] started, if it may still be running. */
    fun stop()

    /** Sound-only previews: tests, screenshots and previews without a vibrator. */
    object None : SampleHaptics {
        override suspend fun prepare(
            haptic: Haptic?,
            sample: File,
        ): Haptic.Custom? = null

        override fun play(haptic: Haptic.Custom) = Unit

        override fun stop() = Unit
    }
}

/**
 * Discover's preview player: a character's sample clip and, started on the same callback as the clip, its vibration
 * (ADR 0069). [playingKey] tells which character is speaking, like [ClipPlayer.playingKey].
 */
@Stable
internal class SamplePlayer(
    private val clips: ClipPlayer,
    private val haptics: SampleHaptics,
) {
    val playingKey: String? get() = clips.playingKey

    /**
     * Plays [clip], the sample of [character], with its vibration, stopping whatever was playing. The vibration is
     * worked out first (an auto envelope is analysed on the first tap), so it can start with the clip.
     */
    suspend fun play(
        character: RemoteCharacter,
        clip: File,
    ) {
        val vibration = haptics.prepare(character.haptic, clip)
        haptics.stop()
        clips.play(character.key, listOf(clip), onStart = vibration?.let { { haptics.play(it) } })
    }
}

/** A [SamplePlayer] whose clip and vibration stop when it leaves the composition. */
@Composable
internal fun rememberSamplePlayer(haptics: SampleHaptics): SamplePlayer {
    val clips = rememberClipPlayer()
    DisposableEffect(haptics) { onDispose { haptics.stop() } }
    return remember(clips, haptics) { SamplePlayer(clips, haptics) }
}

/**
 * Which vibration a sample plays with, without the vibrator: [Haptic.Custom] as the index has it, [Haptic.Auto] as
 * the envelope [analyze] computes from the sample (once per file: preview files never change, PreviewStore names
 * them by URL), nothing for the other modes, for no haptic, or while [enabled] says the "App vibration" switch is off.
 */
internal class SampleVibrations(
    private val enabled: suspend () -> Boolean,
    private val analyze: suspend (File) -> Haptic.Custom?,
) {
    private class Analysed(
        val haptic: Haptic.Custom?,
    )

    private val envelopes = ConcurrentHashMap<String, Analysed>()

    suspend fun of(
        haptic: Haptic?,
        sample: File,
    ): Haptic.Custom? {
        if (haptic == null || !enabled()) return null
        return when (haptic) {
            is Haptic.Custom -> haptic
            Haptic.Auto -> envelope(sample)
            Haptic.Keep, Haptic.Off -> null
        }
    }

    private suspend fun envelope(sample: File): Haptic.Custom? {
        envelopes[sample.path]?.let { return it.haptic }
        val haptic =
            try {
                analyze(sample)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A sample the decoder can't read plays without vibration, like a clip auto haptics skipped.
                null
            }
        envelopes[sample.path] = Analysed(haptic)
        return haptic
    }
}

/**
 * [SampleHaptics] with the vibrator, through `:core:haptics`' public-API path ([PublicHapticEffects], ADR 0026) and
 * notification vibration attributes, like the picker's voice preview. Auto envelopes come from the same analyser and
 * device budget as at install ([AutoHaptics]), so the preview feels like a message with the character's first clip.
 * Everything is created on the first tap.
 */
@Singleton
class DeviceSampleHaptics
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        settings: UiFeedbackSettings,
    ) : SampleHaptics {
        private val vibrator: Vibrator by lazy {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        }
        private val capabilities: HapticCapabilities by lazy { PublicHapticEffects.capabilities(vibrator) }
        private val autoHaptics by lazy { AutoHaptics(capabilities) }
        private val vibrations =
            SampleVibrations(
                enabled = { settings.hapticsEnabled.first() && vibrator.hasVibrator() },
                analyze = { autoHaptics.analyze(it) },
            )

        @Volatile private var started = false

        override suspend fun prepare(
            haptic: Haptic?,
            sample: File,
        ): Haptic.Custom? = vibrations.of(haptic, sample)

        override fun play(haptic: Haptic.Custom) {
            val effect = PublicHapticEffects.create(haptic, capabilities) ?: return
            started = runCatching { vibrator.vibrate(effect, NOTIFICATION_VIBRATION) }.isSuccess
        }

        override fun stop() {
            // Only after a preview vibrated: cancel() would also cut the app's own UI haptics.
            if (!started) return
            started = false
            runCatching { vibrator.cancel() }
        }

        private companion object {
            val NOTIFICATION_VIBRATION: VibrationAttributes =
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION)
        }
    }
