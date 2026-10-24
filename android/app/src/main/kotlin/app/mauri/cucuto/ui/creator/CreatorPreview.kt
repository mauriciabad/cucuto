package app.mauri.cucuto.ui.creator

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.VibrationAttributes
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import app.mauri.cucuto.core.creator.CreatorHaptics
import app.mauri.cucuto.core.creator.DraftCharacter
import app.mauri.cucuto.core.creator.HapticMode
import app.mauri.cucuto.core.haptics.HapticCapabilities
import app.mauri.cucuto.core.haptics.PublicHapticEffects
import app.mauri.cucuto.core.haptics.auto.AutoHaptics
import app.mauri.cucuto.core.routing.Haptic
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Live preview with real haptics" (brief): plays a clip of the pack being made the way a notification will, with
 * notification audio attributes (it follows the notification volume and ringer mode) and the character's vibration:
 * auto haptics computed from the clip by `:core:haptics` (as the importer will), the custom pattern, or none. Same
 * public-API path as the picker's [app.mauri.cucuto.voice.VoicePreview].
 */
@Singleton
class CreatorPreview
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        private val vibrator: Vibrator by lazy { context.getSystemService(VibratorManager::class.java).defaultVibrator }
        private val capabilities: HapticCapabilities by lazy { PublicHapticEffects.capabilities(vibrator) }
        private val autoHaptics: AutoHaptics by lazy { AutoHaptics(capabilities) }
        private var player: MediaPlayer? = null

        /** Plays [file] with [character]'s vibration, stopping any earlier preview. */
        suspend fun play(
            file: File,
            character: DraftCharacter,
        ) {
            stop()
            val haptic = hapticFor(file, character)
            val media = MediaPlayer()
            try {
                withContext(Dispatchers.IO) {
                    media.setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    media.setDataSource(file.path)
                    media.prepare()
                }
            } catch (e: CancellationException) {
                media.release()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Creator preview failed to load (${e.javaClass.simpleName})")
                media.release()
                return
            }
            stop()
            player = media
            media.setOnCompletionListener { done ->
                if (player === done) player = null
                done.release()
            }
            media.start()
            haptic?.let { custom ->
                PublicHapticEffects.create(custom, capabilities)?.let { effect ->
                    runCatching {
                        vibrator.vibrate(
                            effect,
                            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION),
                        )
                    }
                }
            }
        }

        fun stop() {
            player?.let {
                runCatching { it.stop() }
                it.release()
            }
            player = null
            runCatching { vibrator.cancel() }
        }

        private suspend fun hapticFor(
            file: File,
            character: DraftCharacter,
        ): Haptic.Custom? =
            try {
                when (character.haptic) {
                    HapticMode.NONE -> {
                        null
                    }

                    HapticMode.AUTO -> {
                        autoHaptics.analyze(file)
                    }

                    HapticMode.CUSTOM -> {
                        CreatorHaptics.fromPack(character.customHaptic) ?: autoHaptics.analyze(file)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }

        private companion object {
            const val TAG = "cucuto"
        }
    }
