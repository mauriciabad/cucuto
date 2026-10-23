package app.mauri.cucuto.xposed.route

import java.util.Random
import kotlin.math.roundToInt

/**
 * A weighted shuffle bag over `size` clips: each clip goes into the bag as many times as its weight asks for, draws
 * remove one entry at random, and an empty bag is refilled. Two guarantees:
 *
 * - **Never the same clip twice in a row** (brief, M1), as long as there are at least two clips. This also holds
 *   across a refill, and across a table republish when the caller passes the previous clip to [lastPlayed].
 * - Over a full bag every clip plays in proportion to its weight. The no-repeat rule wins when the two collide
 *   (two clips always alternate, whatever their weights).
 *
 * Not thread-safe: [ShuffleBags] serialises access.
 *
 * @param milliWeights Each clip's weight in thousandths (pack weight × 1000, see `:core:routing`'s `Clip`).
 */
class ShuffleBag(
    milliWeights: IntArray,
    private val random: Random,
    lastPlayed: Int = NONE,
) {
    private val copies: IntArray = IntArray(milliWeights.size) { copiesFor(milliWeights[it]) }
    private var bag = IntArray(0)
    private var count = 0

    /** Index of the clip [next] returned last, or [NONE]. */
    var last: Int = if (lastPlayed in milliWeights.indices) lastPlayed else NONE
        private set

    val size: Int get() = copies.size

    /** The next clip index. Requires at least one clip. */
    fun next(): Int {
        check(copies.isNotEmpty()) { "A shuffle bag needs at least one clip" }
        if (copies.size == 1) {
            last = 0
            return 0
        }
        var eligible = eligibleCount()
        if (eligible == 0) {
            refill()
            eligible = eligibleCount()
        }
        var target = random.nextInt(eligible)
        var position = -1
        for (i in 0 until count) {
            if (bag[i] != last) {
                if (target == 0) {
                    position = i
                    break
                }
                target--
            }
        }
        val clip = bag[position]
        // Swap-remove: order inside the bag does not matter, draws are random.
        bag[position] = bag[count - 1]
        count--
        last = clip
        return clip
    }

    private fun eligibleCount(): Int {
        var n = 0
        for (i in 0 until count) if (bag[i] != last) n++
        return n
    }

    /** Adds one full set of copies to whatever is left. */
    private fun refill() {
        val total = count + copies.sum()
        if (bag.size < total) bag = bag.copyOf(total)
        for (clip in copies.indices) {
            repeat(copies[clip]) { bag[count++] = clip }
        }
    }

    companion object {
        const val NONE: Int = -1

        /** Weight resolution: one copy per quarter of the default weight. */
        private const val MILLI_PER_COPY = 250.0

        /** Pack weights top out at 100 (pack format), i.e. 400 copies. */
        private const val MAX_COPIES = 400

        internal fun copiesFor(milliWeight: Int): Int =
            (milliWeight / MILLI_PER_COPY).roundToInt().coerceIn(1, MAX_COPIES)
    }
}

/**
 * One [ShuffleBag] per speaker (`package + key`), bounded so a long-running `system_server` never grows without
 * limit. When the speaker's clip list changes (the app republished the table), the bag is rebuilt but remembers the
 * last clip by URI, so a republish cannot cause an immediate repeat.
 */
class ShuffleBags(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val randomFor: (seed: Int) -> Random = { seed -> Random(seed.toLong() xor System.nanoTime()) },
) {
    private class Entry(
        val clipUris: List<String>,
        val weights: IntArray,
        val bag: ShuffleBag,
    )

    private val entries =
        object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > capacity
        }

    /**
     * Draws the next clip index for [speaker].
     *
     * @param clipUris The speaker's clips, in table order.
     * @param milliWeights Matching weights.
     * @param seed The route's stable seed, mixed into the bag's random source.
     */
    fun next(
        speaker: String,
        clipUris: List<String>,
        milliWeights: IntArray,
        seed: Int,
    ): Int =
        synchronized(entries) {
            val existing = entries[speaker]
            val entry =
                if (existing != null && existing.clipUris == clipUris && existing.weights.contentEquals(milliWeights)) {
                    existing
                } else {
                    val lastUri =
                        existing?.let { e ->
                            e.bag.last
                                .takeIf { it >= 0 }
                                ?.let { e.clipUris[it] }
                        }
                    val lastIndex = if (lastUri == null) ShuffleBag.NONE else clipUris.indexOf(lastUri)
                    Entry(clipUris, milliWeights, ShuffleBag(milliWeights, randomFor(seed), lastIndex)).also {
                        entries[speaker] = it
                    }
                }
            entry.bag.next()
        }

    fun clear() = synchronized(entries) { entries.clear() }

    companion object {
        const val DEFAULT_CAPACITY: Int = 512
    }
}
