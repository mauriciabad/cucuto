package app.mauri.cucuto.xposed.route

import app.mauri.cucuto.core.routing.Moment
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

    /**
     * Records that clip [index] just played outside [next] (a special clip, ADR 0059), so the next draw does not
     * repeat it. Allocation-free.
     */
    fun played(index: Int) {
        if (index in copies.indices) last = index
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
 * Each speaker also keeps its **rhythm** while its voice has special clips ([Voice.hasMoments], ADR 0059): when its
 * last message alerted, on which local day, and how many came in a row. Special clips never join the bag; they play
 * instead of a draw when their moment comes (see [next]).
 *
 * The notification path ([next] with a [Voice]) allocates nothing in steady state: speakers are looked up with a
 * reusable probe key, the playable clips are listed in a scratch array, and an entry built for the same [Voice]
 * object and the same playable clips is reused as is (#207). URI lists are only built when that check fails. A
 * speaker's entry, with its random source and rhythm, lives until it is evicted, and only its bag is rebuilt.
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
        val random: Random,
    ) {
        var clipUris: List<String> = emptyList()
        var weights: IntArray = IntArray(0)
        var bag: ShuffleBag? = null

        /** The voice [indices] point into, when drawn through [next] with a [Voice]. */
        var voice: Voice? = null

        /** Clip index in [voice] of each of the bag's clips. */
        var indices: IntArray = IntArray(0)

        /** When the speaker's last message alerted (epoch ms), or [NEVER]. Tracked while the voice has moments. */
        var lastAlertMs: Long = NEVER

        /** The local day of [lastAlertMs] ([RoutingTable.dayOf]), or [NEVER]. */
        var lastDay: Long = NEVER

        /** Messages in a row, each within [Moment.BURST_GAP_MS] of the one before; 1 for a message after a pause. */
        var run: Int = 0

        /** The rare clip played last (index in [voice]), so it never plays twice in a row; -1 when none. */
        var lastRare: Int = -1

        /**
         * The clip played last (index in the voice), whichever way it was picked, tracked while the voice has moments:
         * no special clip but a greeting follows itself, and the bag does not repeat a special clip it also holds (a
         * voice whose ordinary clips are all switched off). -1 when none.
         */
        var lastClip: Int = -1
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
    ): Int = synchronized(entries) { bag(entry("", speaker, seed), clipUris, milliWeights).next() }

    /**
     * Picks the next clip of [voice] for the speaker [key] of [packageName] at [nowMs]. Returns an index into the
     * voice's clips.
     *
     * 1. **Moment clips** ([Moment.isMoment]): when this message is the speaker's first of the local day, or the nth
     *    of a burst, the moment clips that match (and whose time condition and [Moment.oneIn] roll allow it) play
     *    instead of the bag, one of them by weight.
     * 2. **Rare clips** (only [Moment.oneIn]): otherwise each rare clip that may play now rolls its chance, in table
     *    order; the first hit plays. A rare clip never plays twice in a row.
     * 3. **The bag**: otherwise a draw among the ordinary clips that [table] lets play at [nowMs]
     *    ([RoutingTable.isPlayable]); all ordinary clips when every one may play or none may (a pack whose conditions
     *    leave a gap still plays something), and every clip when the voice has no ordinary clip left.
     *
     * @param seed The route's stable seed, mixed into the speaker's random source.
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
            probe.packageName = packageName
            probe.key = key
            var existing = entries[probe]
            val moments = voice.hasMoments
            if (moments) {
                val entry = existing ?: entry(packageName, key, seed)
                existing = entry
                val special = special(entry, voice, table, nowMs)
                if (special >= 0) {
                    entry.lastClip = special
                    return special
                }
            }
            val count = playable(voice, table, nowMs)
            if (existing != null) {
                val bag = existing.bag
                if (bag != null && existing.voice === voice && sameIndices(existing.indices, count)) {
                    return draw(existing, bag, moments)
                }
            }
            // A new speaker, a republished table or another set of playable clips.
            val indices = scratch.copyOf(count)
            val uris = indices.map { voice.clips[it].uri }
            val weights = IntArray(count) { voice.clips[indices[it]].milliWeight }
            val entry = existing ?: entry(packageName, key, seed)
            val bag = bag(entry, uris, weights)
            entry.voice = voice
            entry.indices = indices
            draw(entry, bag, moments)
        }

    /**
     * Draws from [entry]'s [bag] and returns the clip index in the voice. With [moments], a special clip that just
     * played and is also in the bag (a voice of only special clips) counts as the bag's last, so it does not repeat.
     */
    private fun draw(
        entry: Entry,
        bag: ShuffleBag,
        moments: Boolean,
    ): Int {
        val indices = entry.indices
        if (!moments) return indices[bag.next()]
        val last = entry.lastClip
        if (last >= 0) {
            for (position in indices.indices) {
                if (indices[position] == last) {
                    bag.played(position)
                    break
                }
            }
        }
        return indices[bag.next()].also { entry.lastClip = it }
    }

    /**
     * Updates [entry]'s rhythm with a message at [nowMs] and returns the special clip it plays, or -1 for a draw from
     * the bag. Uses [scratch]; allocates nothing.
     */
    private fun special(
        entry: Entry,
        voice: Voice,
        table: RoutingTable,
        nowMs: Long,
    ): Int {
        val day = table.dayOf(nowMs)
        val firstOfDay = entry.lastDay != day
        val gap = nowMs - entry.lastAlertMs
        val run = if (entry.lastAlertMs != NEVER && gap >= 0 && gap <= Moment.BURST_GAP_MS) entry.run + 1 else 1
        entry.lastAlertMs = nowMs
        entry.lastDay = day
        entry.run = run

        val clips = voice.clips
        if (scratch.size < clips.size) scratch = IntArray(clips.size)
        // 1. Moment clips that match this message.
        var count = 0
        var total = 0
        for (i in clips.indices) {
            val clip = clips[i]
            val moment = clip.moment
            // A burst clip the bag just played (a voice of only special clips) waits for the next burst. A greeting
            // may follow itself: a chat's only message on two days.
            if (!moment.isMoment || (i == entry.lastClip && !moment.firstOfDay)) continue
            if (moment.firstOfDay && !firstOfDay) continue
            if (moment.burst != 0 && moment.burst != run) continue
            if (!table.isPlayable(clip, nowMs)) continue
            if (moment.oneIn != 0 && entry.random.nextInt(moment.oneIn) != 0) continue
            scratch[count++] = i
            total += clip.milliWeight.coerceAtLeast(1)
        }
        if (count > 0) {
            var target = entry.random.nextInt(total)
            for (k in 0 until count) {
                target -= clips[scratch[k]].milliWeight.coerceAtLeast(1)
                if (target < 0) return scratch[k]
            }
            return scratch[count - 1]
        }
        // 2. Rare clips.
        for (i in clips.indices) {
            val clip = clips[i]
            val moment = clip.moment
            if (moment.isMoment || moment.oneIn == 0 || i == entry.lastRare || i == entry.lastClip) continue
            if (!table.isPlayable(clip, nowMs)) continue
            if (entry.random.nextInt(moment.oneIn) == 0) {
                entry.lastRare = i
                return i
            }
        }
        entry.lastRare = -1
        return -1
    }

    /** The speaker's entry, created (with its random source) when it has none. */
    private fun entry(
        packageName: String,
        key: String,
        seed: Int,
    ): Entry {
        probe.packageName = packageName
        probe.key = key
        return entries[probe] ?: Entry(randomFor(seed)).also { entries[Speaker(packageName, key)] = it }
    }

    /** [entry]'s bag for these clips: the current one when they did not change, else a new one. */
    private fun bag(
        entry: Entry,
        clipUris: List<String>,
        milliWeights: IntArray,
    ): ShuffleBag {
        val existing = entry.bag
        if (existing != null && entry.clipUris == clipUris && entry.weights.contentEquals(milliWeights)) {
            return existing
        }
        val lastUri = existing?.last?.takeIf { it >= 0 }?.let { entry.clipUris[it] }
        val lastIndex = if (lastUri == null) ShuffleBag.NONE else clipUris.indexOf(lastUri)
        val bag = ShuffleBag(milliWeights, entry.random, lastIndex)
        entry.clipUris = clipUris
        entry.weights = milliWeights
        entry.bag = bag
        return bag
    }

    /** Lists in [scratch] the indices of [voice]'s ordinary clips that may play at [nowMs]; returns how many. */
    private fun playable(
        voice: Voice,
        table: RoutingTable,
        nowMs: Long,
    ): Int {
        val clips = voice.clips
        if (scratch.size < clips.size) scratch = IntArray(clips.size)
        val moments = voice.hasMoments
        var count = 0
        var ordinary = clips.size
        if (moments) {
            ordinary = 0
            for (i in clips.indices) if (!clips[i].moment.isSpecial) ordinary++
        }
        if (voice.isConditional) {
            for (i in clips.indices) {
                val clip = clips[i]
                if ((!moments || !clip.moment.isSpecial) && table.isPlayable(clip, nowMs)) scratch[count++] = i
            }
        }
        if (count == 0 || count == ordinary) {
            count = 0
            for (i in clips.indices) if (ordinary == 0 || !moments || !clips[i].moment.isSpecial) scratch[count++] = i
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

        /** No message yet. */
        private const val NEVER: Long = Long.MIN_VALUE
    }
}
