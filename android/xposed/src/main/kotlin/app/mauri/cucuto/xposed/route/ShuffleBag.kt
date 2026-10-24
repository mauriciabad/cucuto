package app.mauri.cucuto.xposed.route

import app.mauri.cucuto.core.routing.RoutingTable
import app.mauri.cucuto.core.routing.Voice
import java.util.Random
import kotlin.math.roundToInt

/**
 * A weighted shuffle bag over `size` clips: each clip goes into the bag as many times as its weight asks for, draws
 * remove one entry at random, and an empty bag is refilled. Two guarantees:
 *
 * - **Never the same clip twice in a row** (brief, M1), as long as there are at least two clips. This also holds
 *   across a refill, and across a table republish when the caller passes the previous clip to [lastPlayed].
 * - Over a full bag every clip plays in proportion to its weight. The no-repeat rule wins when the two collide
 *   (two clips always alternate, whatever their weights). Copies left over because of it carry over to the next
 *   refill, at most one clip's set of them, so the bag never holds more than two full sets.
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

    /** Copies left in the bag until the next refill. */
    internal val pending: Int get() = count

    /** The next clip index. Requires at least one clip. */
    fun next(): Int {
        check(copies.isNotEmpty()) { "A shuffle bag needs at least one clip" }
        if (copies.size == 1) {
            last = 0
            return 0
        }
        var eligible = eligibleCount()
        if (eligible == 0) {
            // Only copies of the last clip are left. They carry over (so the clip still gets its share), but at most
            // one set of them: when one clip outweighs all the others together (weight 2 against one clip of weight
            // 1), they would otherwise pile up by a few copies on every refill, growing the bag and the time of
            // every draw for as long as system_server runs (#207).
            if (count > 0) count = minOf(count, copies[last])
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
 *
 * The notification path ([next] with a [Voice]) allocates nothing in steady state: speakers are looked up with a
 * reusable probe key, the playable clips are listed in a scratch array, and an entry built for the same [Voice]
 * object and the same playable clips is reused as is (#207). URI lists are only built when that check fails.
 */
class ShuffleBags(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val randomFor: (seed: Int) -> Random = { seed -> Random(seed.toLong() xor System.nanoTime()) },
) {
    /** `package + key`; mutable only for [probe], which is never stored. */
    private class Speaker(
        var packageName: String,
        var key: String,
    ) {
        override fun hashCode(): Int = packageName.hashCode() * 31 + key.hashCode()

        override fun equals(other: Any?): Boolean =
            other is Speaker && other.packageName == packageName && other.key == key
    }

    private class Entry(
        val clipUris: List<String>,
        val weights: IntArray,
        val bag: ShuffleBag,
    ) {
        /** The voice [indices] point into, when drawn through [next] with a [Voice]. */
        var voice: Voice? = null

        /** Clip index in [voice] of each of the bag's clips. */
        var indices: IntArray = IntArray(0)
    }

    private val entries =
        object : LinkedHashMap<Speaker, Entry>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Speaker, Entry>?): Boolean = size > capacity
        }

    // Guarded by entries.
    private val probe = Speaker("", "")
    private var scratch = IntArray(16)

    /** Number of speakers held. */
    internal val size: Int get() = synchronized(entries) { entries.size }

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
    ): Int = synchronized(entries) { entry("", speaker, clipUris, milliWeights, seed).bag.next() }

    /**
     * Draws the next clip of [voice] for the speaker [key] of [packageName], among the clips [table] lets play at
     * [nowMs] ([RoutingTable.isPlayable]); all of them when every clip may play, or none may (a pack whose conditions
     * leave a gap still plays something). Returns an index into the voice's clips.
     *
     * @param seed The route's stable seed, mixed into the bag's random source.
     */
    fun next(
        packageName: String,
        key: String,
        voice: Voice,
        table: RoutingTable,
        nowMs: Long,
        seed: Int,
    ): Int =
        synchronized(entries) {
            val count = playable(voice, table, nowMs)
            probe.packageName = packageName
            probe.key = key
            val existing = entries[probe]
            if (existing != null && existing.voice === voice && sameIndices(existing.indices, count)) {
                return existing.indices[existing.bag.next()]
            }
            // A new speaker, a republished table or another set of playable clips.
            val indices = scratch.copyOf(count)
            val uris = indices.map { voice.clips[it].uri }
            val weights = IntArray(count) { voice.clips[indices[it]].milliWeight }
            val entry = entry(packageName, key, uris, weights, seed)
            entry.voice = voice
            entry.indices = indices
            indices[entry.bag.next()]
        }

    /** The entry for a speaker with these clips: the current one when they did not change, else a new one. */
    private fun entry(
        packageName: String,
        key: String,
        clipUris: List<String>,
        milliWeights: IntArray,
        seed: Int,
    ): Entry {
        probe.packageName = packageName
        probe.key = key
        val existing = entries[probe]
        if (existing != null && existing.clipUris == clipUris && existing.weights.contentEquals(milliWeights)) {
            return existing
        }
        val lastUri =
            existing?.let { e ->
                e.bag.last
                    .takeIf { it >= 0 }
                    ?.let { e.clipUris[it] }
            }
        val lastIndex = if (lastUri == null) ShuffleBag.NONE else clipUris.indexOf(lastUri)
        return Entry(clipUris, milliWeights, ShuffleBag(milliWeights, randomFor(seed), lastIndex)).also {
            entries[Speaker(packageName, key)] = it
        }
    }

    /** Lists in [scratch] the indices of [voice]'s clips that may play at [nowMs]; returns how many. */
    private fun playable(
        voice: Voice,
        table: RoutingTable,
        nowMs: Long,
    ): Int {
        val clips = voice.clips
        if (scratch.size < clips.size) scratch = IntArray(clips.size)
        var count = 0
        if (voice.isConditional) {
            for (i in clips.indices) if (table.isPlayable(clips[i], nowMs)) scratch[count++] = i
        }
        if (count == 0 || count == clips.size) {
            for (i in clips.indices) scratch[i] = i
            count = clips.size
        }
        return count
    }

    private fun sameIndices(
        indices: IntArray,
        count: Int,
    ): Boolean {
        if (indices.size != count) return false
        for (i in 0 until count) if (indices[i] != scratch[i]) return false
        return true
    }

    fun clear() = synchronized(entries) { entries.clear() }

    companion object {
        const val DEFAULT_CAPACITY: Int = 512
    }
}
