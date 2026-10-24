package app.mauri.cucuto.core.ringtone.render

import app.mauri.cucuto.packspec.RingFilePlan
import app.mauri.cucuto.packspec.RingPiece
import kotlin.math.roundToInt

/** Decoded source audio: interleaved 16-bit frames at the ring file rate. */
interface PcmSource {
    val frames: Long
    val channels: Int

    /** Reads `count` frames from frame [from] into [into] (interleaved, from index 0). Never asked past [frames]. */
    fun read(
        from: Long,
        into: ShortArray,
        count: Int,
    )
}

/**
 * Streams a ring file from [source] following [plan] (docs/pack-format.md#ring-files): its pieces at [rate] in order,
 * frames past the end of the source as silence, and a linear fade-in over the first `plan.fadeInMs`. The same splice
 * as packer's `Ringtones.splice` (ADR 0033), pulled in chunks so an encoder can take it buffer by buffer.
 */
class RingFileReader(
    private val source: PcmSource,
    plan: RingFilePlan,
    rate: Int,
) {
    private val pieces: List<RingPiece> = plan.pieces(rate)
    private val fadeInFrames: Long = RingFilePlan.frame(plan.fadeInMs, rate)
    private val channels = source.channels

    /** Frames the ring file has in total. */
    val totalFrames: Long = pieces.sumOf { it.frames }

    private var pieceIndex = 0
    private var inPiece = 0L
    private var written = 0L

    /** Fills [into] with up to [maxFrames] frames; returns how many, 0 at the end. */
    fun read(
        into: ShortArray,
        maxFrames: Int,
    ): Int {
        var filled = 0
        while (filled < maxFrames && pieceIndex < pieces.size) {
            val piece = pieces[pieceIndex]
            val n = minOf((maxFrames - filled).toLong(), piece.frames - inPiece).toInt()
            val offset = filled * channels
            into.fill(0, offset, offset + n * channels)
            if (piece is RingPiece.Copy) {
                val from = piece.from + inPiece
                val available = (source.frames - from).coerceIn(0, n.toLong()).toInt()
                if (available > 0) {
                    if (offset == 0) {
                        source.read(from, into, available)
                    } else {
                        val tmp = ShortArray(available * channels)
                        source.read(from, tmp, available)
                        tmp.copyInto(into, offset)
                    }
                }
            }
            filled += n
            inPiece += n
            if (inPiece >= piece.frames) {
                pieceIndex++
                inPiece = 0
            }
        }
        applyFade(into, filled)
        written += filled
        return filled
    }

    private fun applyFade(
        into: ShortArray,
        frames: Int,
    ) {
        if (written >= fadeInFrames) return
        for (i in 0 until frames) {
            val position = written + i
            if (position >= fadeInFrames) break
            val gain = position.toFloat() / fadeInFrames
            for (c in 0 until channels) {
                val at = i * channels + c
                into[at] = (into[at] * gain).roundToInt().toShort()
            }
        }
    }
}
