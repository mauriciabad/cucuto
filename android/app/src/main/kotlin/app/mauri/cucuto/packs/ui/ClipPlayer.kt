package app.mauri.cucuto.packs.ui

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.io.File
import java.io.FileInputStream

/**
 * Plays one character clip at a time for previews (tap a character to hear it). [playingKey] tells which character
 * is "speaking", so its avatar can morph while the clip plays. Clips are a few seconds long and live in app
 * storage; MediaPlayer prepares them off the main thread.
 */
@Stable
class ClipPlayer internal constructor() {
    /** The key of the character whose clip is playing, or null. */
    var playingKey: String? by mutableStateOf(null)
        private set

    private var player: MediaPlayer? = null
    private val turns = mutableMapOf<String, Int>()

    /**
     * Plays the next of [clips] for [key] (taps cycle through the pool), stopping whatever was playing. [onStart] runs
     * right after the clip starts, for a vibration that starts with it (Discover's haptic previews, ADR 0069).
     */
    fun play(
        key: String,
        clips: List<File>,
        onStart: (() -> Unit)? = null,
    ) {
        stop()
        if (clips.isEmpty()) return
        val turn = turns[key] ?: 0
        turns[key] = turn + 1
        val clip = clips[turn % clips.size]
        val media = MediaPlayer()
        try {
            media.setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            FileInputStream(clip).use { media.setDataSource(it.fd) }
            media.setOnPreparedListener {
                it.start()
                onStart?.invoke()
            }
            media.setOnCompletionListener { if (player === it) stop() }
            media.setOnErrorListener { mp, _, _ ->
                if (player === mp) stop()
                true
            }
            player = media
            playingKey = key
            media.prepareAsync()
        } catch (e: Exception) {
            media.release()
            if (player === media) {
                player = null
                playingKey = null
            }
        }
    }

    fun stop() {
        player?.release()
        player = null
        playingKey = null
    }
}

/** A [ClipPlayer] that stops when it leaves the composition. */
@Composable
fun rememberClipPlayer(): ClipPlayer {
    val player = remember { ClipPlayer() }
    DisposableEffect(player) { onDispose { player.stop() } }
    return player
}
