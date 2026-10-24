package app.mauri.cucuto.ui.ringtone

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Immutable
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Rings ringtones for previews (#53, #246), one at a time. */
interface RingtonePreview {
    /**
     * Stops the previous preview and rings [sound] the way a call rings it ([RingTimeline]): from 0 ms, the intro once,
     * the loop repeated, round and round until [stop] (owner update 2026-10-24, #242).
     *
     * @return The length of one ring cycle in milliseconds (for progress), or null when it could not play.
     */
    suspend fun ring(sound: RingSound): Long?

    fun pause()

    fun resume()

    fun stop()

    /** [listener] runs when the preview stops by itself (another app took the audio focus); null forgets it. */
    fun onInterrupted(listener: (() -> Unit)?) {}
}

/**
 * The preview that is ringing: [key] says what (a version of a character), [cycleMs] how long one ring file lasts once
 * loaded (null while loading), and whether it is [paused]. It has rung [playedMs] before [startedAtMs] (clock time of
 * the last start or resume), so its place in the ring cycle is [positionAt] any moment, without a ticking coroutine.
 */
@Immutable
data class PreviewPlaying<K>(
    val key: K,
    val cycleMs: Long? = null,
    val paused: Boolean = false,
    val startedAtMs: Long = 0,
    val playedMs: Long = 0,
    internal val id: Long = 0,
) {
    /** How long it has rung at [nowMs] (clock time), pauses left out. */
    fun elapsedAt(nowMs: Long): Long =
        playedMs + if (paused || cycleMs == null) 0 else (nowMs - startedAtMs).coerceAtLeast(0)

    /** Where it is in the ring cycle at [nowMs]. */
    fun positionAt(nowMs: Long): Long = cycleMs?.takeIf { it > 0 }?.let { elapsedAt(nowMs) % it } ?: 0

    /** How far round the ring cycle it is at [nowMs], 0 to 1 (0 while loading). */
    fun progressAt(nowMs: Long): Float = cycleMs?.takeIf { it > 0 }?.let { positionAt(nowMs).toFloat() / it } ?: 0f
}

/**
 * One ringtone preview at a time, ringing like the call until stopped, with pause and resume and its state for the
 * play/pause/stop buttons and the progress ring. Used by the ringtone picker, contact detail and the Packs screens.
 * [clock] is in milliseconds and only measures how long it rang (the progress ring reads [PreviewClock]).
 */
class RingtonePlayback<K>(
    private val scope: CoroutineScope,
    private val preview: RingtonePreview,
    private val clock: () -> Long = PreviewClock::nowMs,
) {
    private val state = MutableStateFlow<PreviewPlaying<K>?>(null)
    private var job: Job? = null
    private var next = 0L

    val playing: StateFlow<PreviewPlaying<K>?> = state.asStateFlow()

    /** Whether [key] is ringing or paused. */
    fun isActive(key: K): Boolean = state.value?.key == key

    /** Rings [sound] as [key]; for the [key] already active, pauses or resumes it. */
    fun toggle(
        key: K,
        sound: RingSound,
    ) {
        val current = state.value
        when {
            current?.key != key -> play(key, sound)
            current.paused -> resume()
            else -> pause()
        }
    }

    fun play(
        key: K,
        sound: RingSound,
    ) {
        job?.cancel()
        val id = ++next
        state.value = PreviewPlaying(key, id = id)
        preview.onInterrupted {
            if (state.value?.id == id) {
                job?.cancel()
                state.value = null
            }
        }
        job =
            scope.launch {
                var started = false
                try {
                    val cycle = preview.ring(sound)
                    if (cycle != null) {
                        started = true
                        state.update { if (it?.id == id) it.copy(cycleMs = cycle, startedAtMs = clock()) else it }
                    }
                } finally {
                    if (!started) state.update { if (it?.id == id) null else it }
                }
            }
    }

    fun pause() {
        val current = state.value ?: return
        if (current.paused) return
        preview.pause()
        val now = clock()
        state.value = current.copy(paused = true, playedMs = current.elapsedAt(now))
    }

    fun resume() {
        val current = state.value ?: return
        if (!current.paused) return
        preview.resume()
        state.value = current.copy(paused = false, startedAtMs = clock())
    }

    fun stop() {
        job?.cancel()
        job = null
        preview.stop()
        state.value = null
    }
}

/** The clock ringtone previews measure with (monotonic, milliseconds). */
object PreviewClock {
    fun nowMs(): Long = System.nanoTime() / 1_000_000
}

/**
 * [RingtonePreview] with `MediaPlayer` and ringtone audio attributes, like Android's own ringtone picker: the preview
 * follows the ring volume and the ringer mode. It plays the pack's file along its [RingTimeline], so what you hear is
 * what a call will sound like: the intro, then the loop over and over (seeking back at each loop end), the silence
 * after a `loop: false` ringtone, and the fade-in at the start of each ring file. It holds transient audio focus while
 * it rings and stops when another app takes it (ADR 0057's owner update). Files come from the installed pack folders,
 * never from the network.
 */
@Singleton
class MediaRingtonePreview
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : RingtonePreview {
        private val audio = context.getSystemService(AudioManager::class.java)
        private val handler = Handler(Looper.getMainLooper())
        private var interrupted: (() -> Unit)? = null
        private val focus: AudioFocusRequest =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(RINGTONE_AUDIO)
                .setOnAudioFocusChangeListener({ change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        stop()
                        interrupted?.invoke()
                    }
                }, handler)
                .build()
        private val advance = Runnable { onBoundary() }
        private var player: MediaPlayer? = null
        private var timeline: RingTimeline? = null
        private var index = 0
        private var paused = false
        private var silenceEndsAt = 0L
        private var silenceLeft = 0L

        override suspend fun ring(sound: RingSound): Long? {
            stop()
            val prepared = MediaPlayer()
            try {
                withContext(Dispatchers.IO) {
                    prepared.setAudioAttributes(RINGTONE_AUDIO)
                    prepared.setDataSource(sound.file.path)
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
            val duration = prepared.duration.toLong()
            if (duration <= 0) {
                prepared.release()
                return null
            }
            stop()
            val ring = RingTimeline.of(sound, duration)
            if (!requestFocus()) {
                prepared.release()
                return null
            }
            player = prepared
            timeline = ring
            paused = false
            // The end of the file is a boundary too (a whole-file loop, or the end of a loop: false ringtone).
            prepared.setOnCompletionListener { if (player === it && !paused) onBoundary() }
            enter(0)
            return ring.cycleMs
        }

        override fun pause() {
            val p = player ?: return
            if (paused) return
            paused = true
            handler.removeCallbacksAndMessages(null)
            abandonFocus()
            if (current() is RingTimeline.Segment.Silence) {
                silenceLeft = (silenceEndsAt - SystemClock.uptimeMillis()).coerceAtLeast(0)
            } else {
                runCatching { p.pause() }
            }
        }

        override fun resume() {
            val p = player ?: return
            if (!paused) return
            if (!requestFocus()) return
            paused = false
            when (val segment = current()) {
                is RingTimeline.Segment.Silence -> {
                    silenceEndsAt = SystemClock.uptimeMillis() + silenceLeft
                    handler.postDelayed(advance, silenceLeft)
                }

                is RingTimeline.Segment.Play -> {
                    runCatching { p.start() }
                    schedule(segment.toMs - p.currentPosition)
                }

                null -> {}
            }
        }

        override fun stop() {
            handler.removeCallbacksAndMessages(null)
            player?.let {
                runCatching { it.stop() }
                it.release()
            }
            player = null
            timeline = null
            paused = false
            abandonFocus()
        }

        override fun onInterrupted(listener: (() -> Unit)?) {
            interrupted = listener
        }

        private fun requestFocus(): Boolean =
            runCatching { audio?.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED }
                .getOrDefault(false)

        private fun abandonFocus() {
            runCatching { audio?.abandonAudioFocusRequest(focus) }
        }

        private fun current(): RingTimeline.Segment? = timeline?.segments?.getOrNull(index)

        /** Starts segment [i] of the ring file. */
        private fun enter(i: Int) {
            val p = player ?: return
            val ring = timeline ?: return
            handler.removeCallbacks(advance)
            index = i
            try {
                when (val segment = ring.segments[i]) {
                    is RingTimeline.Segment.Play -> {
                        if (i == 0) fadeIn(p, ring.plan.fadeInMs)
                        val seek = ring.seeksAt(i) && !(i == 0 && p.currentPosition <= SEEK_SLACK_MS && !p.isPlaying)
                        if (seek) p.seekTo(segment.fromMs, MediaPlayer.SEEK_CLOSEST)
                        if (!p.isPlaying) p.start()
                        schedule(if (seek) segment.lengthMs else segment.toMs - p.currentPosition)
                    }

                    is RingTimeline.Segment.Silence -> {
                        if (p.isPlaying) p.pause()
                        silenceEndsAt = SystemClock.uptimeMillis() + segment.lengthMs
                        handler.postDelayed(advance, segment.lengthMs)
                    }
                }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Ringtone preview stopped (${e.javaClass.simpleName})")
                stop()
            }
        }

        private fun schedule(remainingMs: Long) {
            handler.removeCallbacks(advance)
            handler.postDelayed(advance, remainingMs.coerceAtLeast(0))
        }

        /** The segment should be over: go on, or wait a little more if the player is behind the clock. */
        private fun onBoundary() {
            val p = player ?: return
            val ring = timeline ?: return
            if (paused) return
            val segment = ring.segments[index]
            if (segment is RingTimeline.Segment.Play && runCatching { p.isPlaying }.getOrDefault(false)) {
                val left = segment.toMs - p.currentPosition
                if (left > SEEK_SLACK_MS) {
                    schedule(left)
                    return
                }
            }
            enter((index + 1) % ring.segments.size)
        }

        /** The ring file's fade-in, rebuilt with volume steps: Telecom plays it at the start of every ring file. */
        private fun fadeIn(
            p: MediaPlayer,
            fadeInMs: Long,
        ) {
            if (fadeInMs <= 0) {
                p.setVolume(1f, 1f)
                return
            }
            val steps = (fadeInMs / FADE_STEP_MS).coerceAtLeast(1)
            p.setVolume(0f, 0f)
            for (step in 1..steps) {
                handler.postDelayed({
                    if (player === p) {
                        val v = step.toFloat() / steps
                        runCatching { p.setVolume(v, v) }
                    }
                }, step * FADE_STEP_MS)
            }
        }

        private companion object {
            const val TAG = "cucuto"
            const val SEEK_SLACK_MS = 20L
            const val FADE_STEP_MS = 50L

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
