package app.mauri.cucuto.core.audio

/**
 * Mono 16-bit PCM: a recording or a decoded file the user cuts clips from. 16-bit keeps a six-minute song at 48 kHz
 * under 35 MB; the clip pipeline converts only the selected part to floats.
 */
class PcmAudio(
    val samples: ShortArray,
    val sampleRate: Int,
) {
    init {
        require(sampleRate > 0) { "Sample rate must be positive" }
    }

    val frames: Int get() = samples.size

    val durationMs: Long get() = samples.size * MILLIS / sampleRate

    /** The frame at [ms], clamped to the audio. */
    fun frameAt(ms: Long): Int = (ms * sampleRate / MILLIS).coerceIn(0L, samples.size.toLong()).toInt()

    /** Samples from [startMs] to [endMs] as floats in −1..1. */
    fun slice(
        startMs: Long,
        endMs: Long,
    ): FloatArray {
        val from = frameAt(startMs)
        val to = frameAt(endMs).coerceAtLeast(from)
        return FloatArray(to - from) { samples[from + it] / SHORT_SCALE }
    }

    companion object {
        const val MILLIS = 1_000L
        const val SHORT_SCALE = 32_768f

        /** Floats in −1..1 to 16-bit samples, clipping anything outside. */
        fun toShorts(samples: FloatArray): ShortArray =
            ShortArray(samples.size) { i ->
                (samples[i] * Short.MAX_VALUE)
                    .toInt()
                    .coerceIn(
                        Short.MIN_VALUE.toInt(),
                        Short.MAX_VALUE.toInt(),
                    ).toShort()
            }
    }
}
