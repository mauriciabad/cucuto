package app.mauri.cucuto.ui.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.provider.Settings
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Plays the brand UI sounds through one [SoundPool] stream (brand/sound-and-haptics.md):
 * - `USAGE_ASSISTANCE_SONIFICATION`, so the system's volume for UI sounds applies;
 * - one sound at a time, a new one cuts the previous;
 * - nothing in silent or vibrate ringer mode;
 * - touch sounds (tap, toggles) also need the system "Touch sounds" setting.
 *
 * [load] decodes all six clips (under 300 ms each) once; call it off the main thread.
 */
internal class UiSoundPlayer(
    private val context: Context,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val pool =
        SoundPool
            .Builder()
            .setMaxStreams(1)
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            ).build()
    private val soundIds = EnumMap<UiSound, Int>(UiSound::class.java)
    private val loaded = ConcurrentHashMap.newKeySet<Int>()

    @Volatile private var streamId = 0

    fun load() {
        pool.setOnLoadCompleteListener { _, sampleId, status -> if (status == 0) loaded += sampleId }
        val ids = UiSound.entries.associateWith { pool.load(context, it.res, 1) }
        synchronized(soundIds) { soundIds.putAll(ids) }
    }

    /** Plays [sound] at [volume] (0–1) unless the system says not to. Returns whether it started. */
    fun play(
        sound: UiSound,
        volume: Float,
    ): Boolean {
        if (volume <= 0f || !systemAllows(sound)) return false
        val id = synchronized(soundIds) { soundIds[sound] } ?: return false
        if (id !in loaded) return false
        if (streamId != 0) pool.stop(streamId)
        streamId = pool.play(id, volume, volume, 1, 0, 1f)
        return streamId != 0
    }

    private fun systemAllows(sound: UiSound): Boolean {
        if (audioManager?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return false
        if (!sound.isTouchSound) return true
        return Settings.System.getInt(context.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) != 0
    }
}
