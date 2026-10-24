package app.mauri.cucuto.ui.ringtone

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Plays ringtone variants in the ringtone picker (#53), one at a time. */
interface RingtonePreview {
    /**
     * Stops the previous preview and plays [file] from the start.
     *
     * @return The file's duration in milliseconds, or null when it could not play.
     */
    suspend fun play(file: File): Long?

    fun stop()
}

/**
 * [RingtonePreview] with `MediaPlayer` and ringtone audio attributes, like Android's own ringtone picker: the preview
 * follows the ring volume and the ringer mode, so what you hear is what a call will sound like. It plays the pack's
 * file as it is (the intro and the loop once); the ring file the phone rings with repeats the loop to 90 s (ADR 0033).
 * Files come from the installed pack folders, never from the network.
 */
@Singleton
class MediaRingtonePreview
    @Inject
    constructor() : RingtonePreview {
        private var player: MediaPlayer? = null

        override suspend fun play(file: File): Long? {
            stop()
            val prepared = MediaPlayer()
            try {
                withContext(Dispatchers.IO) {
                    prepared.setAudioAttributes(RINGTONE_AUDIO)
                    prepared.setDataSource(file.path)
                    prepared.prepare()
                }
            } catch (e: CancellationException) {
                prepared.release()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Ringtone preview failed to load (${e.javaClass.simpleName})")
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
            return prepared.duration.toLong().takeIf { it > 0 }
        }

        override fun stop() {
            player?.let {
                runCatching { it.stop() }
                it.release()
            }
            player = null
        }

        private companion object {
            const val TAG = "cucuto"

            val RINGTONE_AUDIO: AudioAttributes =
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
        }
    }

@Module
@InstallIn(SingletonComponent::class)
interface RingtonePreviewModule {
    @Binds fun ringtonePreview(preview: MediaRingtonePreview): RingtonePreview
}
