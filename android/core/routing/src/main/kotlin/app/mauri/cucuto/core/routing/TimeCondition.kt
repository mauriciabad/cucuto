package app.mauri.cucuto.core.routing

/**
 * When a conditional clip may play, worked out by the app ahead of time (a pack's `when`: day or night from
 * sunrise and sunset, hour ranges, seasons, weekdays) and published as plain instants, so the module only compares
 * the current time against a sorted array: no time zones, calendars or solar maths in `system_server`.
 *
 * The condition is known between [fromMs] (inclusive) and [untilMs] (exclusive). It starts [activeAtFrom] and
 * flips at every instant in [toggles]. Outside that span, [state] returns [UNKNOWN] and the module ignores the
 * condition (the clip plays as if it had none), so a table the app has not refreshed for a long time degrades to
 * the plain clip pool instead of going silent.
 *
 * [state] is O(1) and allocation-free: a per-day bucket index, built once when the table is decoded, points at
 * the first toggle of each day, and a day has only a handful of toggles.
 *
 * @param toggles Strictly increasing instants (epoch milliseconds) inside the open span ([fromMs], [untilMs]).
 */
public class TimeCondition(
    public val fromMs: Long,
    public val untilMs: Long,
    public val activeAtFrom: Boolean,
    toggles: LongArray,
) {
    private val toggles: LongArray = toggles.copyOf()

    /** Index of the first toggle at or after the start of each [BUCKET_MS] bucket, plus one sentinel. */
    private val buckets: IntArray

    init {
        require(untilMs > fromMs) { "Empty span $fromMs..$untilMs" }
        require((untilMs - fromMs) / BUCKET_MS < MAX_BUCKETS) { "Span too long: ${untilMs - fromMs} ms" }
        var previous = fromMs
        for (t in this.toggles) {
            require(t > previous && t < untilMs) { "Toggles must increase strictly inside the span" }
            previous = t
        }
        val count = ((untilMs - fromMs - 1) / BUCKET_MS).toInt() + 1
        buckets = IntArray(count + 1)
        var i = 0
        for (b in 0..count) {
            val start = fromMs + b * BUCKET_MS
            while (i < this.toggles.size && this.toggles[i] < start) i++
            buckets[b] = i
        }
    }

    /** Number of toggles. */
    public val toggleCount: Int get() = toggles.size

    /** The [index]th toggle instant. */
    public fun toggle(index: Int): Long = toggles[index]

    /** A copy of the toggle instants. */
    public fun toggles(): LongArray = toggles.copyOf()

    /** [ACTIVE], [INACTIVE], or [UNKNOWN] when [nowMs] is outside the published span. */
    public fun state(nowMs: Long): Int {
        if (nowMs < fromMs || nowMs >= untilMs) return UNKNOWN
        var i = buckets[((nowMs - fromMs) / BUCKET_MS).toInt()]
        while (i < toggles.size && toggles[i] <= nowMs) i++
        val active = activeAtFrom != (i and 1 == 1)
        return if (active) ACTIVE else INACTIVE
    }

    /** The first toggle after [nowMs], or null when none is published (for scheduling, never on the module path). */
    public fun nextToggleAfter(nowMs: Long): Long? = toggles.firstOrNull { it > nowMs }

    override fun equals(other: Any?): Boolean =
        other is TimeCondition &&
            fromMs == other.fromMs &&
            untilMs == other.untilMs &&
            activeAtFrom == other.activeAtFrom &&
            toggles.contentEquals(other.toggles)

    override fun hashCode(): Int {
        var h = fromMs.hashCode()
        h = 31 * h + untilMs.hashCode()
        h = 31 * h + activeAtFrom.hashCode()
        return 31 * h + toggles.contentHashCode()
    }

    override fun toString(): String =
        "TimeCondition(from=$fromMs, until=$untilMs, activeAtFrom=$activeAtFrom, toggles=${toggles.size})"

    public companion object {
        public const val ACTIVE: Int = 1
        public const val INACTIVE: Int = 0
        public const val UNKNOWN: Int = -1

        /** Bucket size of the lookup index: one day. */
        public const val BUCKET_MS: Long = 86_400_000L

        /** Longest span accepted, in buckets (a little over two years), so a bad table cannot allocate much. */
        public const val MAX_BUCKETS: Long = 800
    }
}
