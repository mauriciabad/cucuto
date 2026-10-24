package app.mauri.cucuto.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.VibrationAttributes
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import app.mauri.cucuto.core.data.voices.VoiceCharacter
import app.mauri.cucuto.core.haptics.HapticCapabilities
import app.mauri.cucuto.core.haptics.HapticEffects
import app.mauri.cucuto.core.haptics.hapticCapabilities
import app.mauri.cucuto.core.routing.Haptic
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** Plays a character the way its notifications will sound and feel, for the character picker. */
interface VoicePreview {
    /**
     * Plays one of [character]'s clips (never the same one twice in a row) and, with [withHaptic], the character's
     * vibration. Stops the previous preview. With [atNanos] (a `System.nanoTime()` timestamp, such as
     * `FeedbackBeat.characterClipAtNanos`) the clip waits for that moment.
     *
     * @return The clip's duration in milliseconds, or null when nothing could play.
     */
    suspend fun play(
        character: VoiceCharacter,
        withHaptic: Boolean = true,
        atNanos: Long? = null,
    ): Long?

    /**
     * Plays one of [character]'s clips with [haptic] instead of the character's own vibration: a per-contact override
     * or the pattern in the haptic editor. [Haptic.Auto] plays the chosen clip's own envelope.
     *
     * @return The clip's duration in milliseconds, or null when nothing could play.
     */
    suspend fun play(
        character: VoiceCharacter,
        haptic: Haptic,
    ): Long?

    /** Plays [haptic] alone, with no clip. Stops the previous preview. Returns false when nothing could play. */
    fun vibrate(haptic: Haptic.Custom): Boolean

    /** Stops the current clip and vibration. */
    fun stop()
}

/**
 * [VoicePreview] with `MediaPlayer` and the vibrator. Clips play with notification audio attributes, like Android's
 * own notification sound picker, so the preview follows the notification volume and the ringer mode: what you hear
 * is what a message will sound like. The vibration uses the same `:core:haptics` builder as the module, with
 * notification vibration attributes. Files come from the installed pack folders (ADR 0008), never from the network.
 */
@Singleton
class MediaVoicePreview
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : VoicePreview {
        private var player: MediaPlayer? = null
        private var lastClip: File? = null
        private val vibrator: Vibrator by lazy {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        }
        private val capabilities: HapticCapabilities by lazy { vibrator.hapticCapabilities() }

        override suspend fun play(
            character: VoiceCharacter,
            withHaptic: Boolean,
            atNanos: Long?,
        ): Long? = playClip(character, if (withHaptic) character.haptic else null, atNanos)

        override suspend fun play(
            character: VoiceCharacter,
            haptic: Haptic,
        ): Long? = playClip(character, haptic, atNanos = null)

        override fun vibrate(haptic: Haptic.Custom): Boolean {
            stop()
            return vibrate(haptic, clipEnvelope = null)
        }

        private suspend fun playClip(
            character: VoiceCharacter,
            haptic: Haptic?,
            atNanos: Long?,
        ): Long? {
            stop()
            val clip = pickClip(character.clips, lastClip) ?: return null
            lastClip = clip
            val prepared = MediaPlayer()
            try {
                withContext(Dispatchers.IO) {
                    prepared.setAudioAttributes(NOTIFICATION_AUDIO)
                    prepared.setDataSource(clip.path)
                    prepared.prepare()
                }
                if (atNanos != null) {
                    val waitMs = (atNanos - System.nanoTime()) / NANOS_PER_MILLI
                    if (waitMs > 0) delay(waitMs)
                }
            } catch (e: CancellationException) {
                prepared.release()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Preview clip failed to load (${e.javaClass.simpleName})")
                prepared.release()
                return null
            }
            stop()
            player = prepared
            prepared.setOnCompletionListener { finished ->
                if (player === finished) player = null
                finished.release()
            }
            prepared.start()
            if (haptic != null) vibrate(haptic, clipEnvelope = character.autoHapticOf(clip))
            return prepared.duration.toLong().takeIf { it > 0 }
        }

        override fun stop() {
            player?.let {
                runCatching { it.stop() }
                it.release()
            }
            player = null
            runCatching { vibrator.cancel() }
        }

        /** Plays [haptic] like the module would ([Haptic.Auto] is the clip's [clipEnvelope]). */
        private fun vibrate(
            haptic: Haptic,
            clipEnvelope: Haptic.Custom?,
        ): Boolean {
            // Keep and Off leave the vibration to WhatsApp or silence it; Auto without an envelope behaves like Keep.
            val custom =
                when (haptic) {
                    is Haptic.Custom -> haptic
                    Haptic.Auto -> clipEnvelope
                    Haptic.Keep, Haptic.Off -> null
                } ?: return false
            val effect = HapticEffects.create(custom, capabilities) ?: return false
            return runCatching { vibrator.vibrate(effect, NOTIFICATION_VIBRATION) }.isSuccess
        }

        private companion object {
            const val TAG = "cucuto"
            const val NANOS_PER_MILLI = 1_000_000L

            val NOTIFICATION_AUDIO: AudioAttributes =
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()

            val NOTIFICATION_VIBRATION: VibrationAttributes =
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION)
        }
    }

/** A random clip, avoiding [last] when there is a choice (the module's shuffle bag does the same). */
internal fun pickClip(
    clips: List<File>,
    last: File?,
    random: Random = Random.Default,
): File? {
    if (clips.isEmpty()) return null
    val choices = if (clips.size > 1) clips.filter { it != last } else clips
    return choices[random.nextInt(choices.size)]
}

@Module
@InstallIn(SingletonComponent::class)
interface VoicePreviewModule {
    @Binds fun voicePreview(preview: MediaVoicePreview): VoicePreview
}
