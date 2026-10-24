package app.mauri.cucuto.core.routing

/**
 * Which local day an instant belongs to, worked out by the app from the phone's time zone (ADR 0059), so the module
 * can tell a chat's first message of the day without time zone rules in `system_server`.
 *
 * A day starts at [RoutingTable]'s day start (04:00 local time, so a message at 01:00 still belongs to the evening
 * before). The app publishes the *shift*: the UTC offset minus the day start, in seconds. The local day of an instant
 * is then `floor((instant + shift) / 1 day)`. [shiftAtStart] applies until the first transition; each transition (a
 * daylight saving change) sets a new shift from its instant on. Before the first or after the last transition the
 * nearest shift applies, so a table the app has not refreshed for a long time is at most an hour off at a missed
 * daylight saving change.
 *
 * [dayOf] is O(number of transitions), a handful over the 400 days the app publishes, and allocation-free.
 *
 * @param transitionsMs Strictly increasing instants (epoch milliseconds, whole seconds).
 * @param shiftsSeconds The shift from each transition on, one per transition.
 */
public class LocalDays(
    public val shiftAtStart: Int,
    transitionsMs: LongArray = LongArray(0),
    shiftsSeconds: IntArray = IntArray(0),
) {
    private val transitions: LongArray = transitionsMs.copyOf()
    private val shifts: IntArray = shiftsSeconds.copyOf()

    init {
        require(transitions.size == shifts.size) { "One shift per transition" }
        require(transitions.size <= MAX_TRANSITIONS) { "Too many transitions: ${transitions.size}" }
        require(shiftAtStart in -MAX_SHIFT_SECONDS..MAX_SHIFT_SECONDS) { "Shift $shiftAtStart s out of range" }
        for (i in transitions.indices) {
            require(i == 0 || transitions[i] > transitions[i - 1]) { "Transitions must increase strictly" }
            require(shifts[i] in -MAX_SHIFT_SECONDS..MAX_SHIFT_SECONDS) { "Shift ${shifts[i]} s out of range" }
        }
    }

    /** Number of transitions. */
    public val transitionCount: Int get() = transitions.size

    /** The [index]th transition instant. */
    public fun transition(index: Int): Long = transitions[index]

    /** The shift from the [index]th transition on, in seconds. */
    public fun shift(index: Int): Int = shifts[index]

    /** The shift in seconds at [nowMs]. */
    public fun shiftAt(nowMs: Long): Int {
        var shift = shiftAtStart
        for (i in transitions.indices) {
            if (nowMs < transitions[i]) break
            shift = shifts[i]
        }
        return shift
    }

    /** The local day number of [nowMs]: equal for two instants of the same day, one more for the next day. */
    public fun dayOf(nowMs: Long): Long = Math.floorDiv(nowMs + shiftAt(nowMs) * MILLIS_PER_SECOND, DAY_MS)

    override fun equals(other: Any?): Boolean =
        other is LocalDays &&
            shiftAtStart == other.shiftAtStart &&
            transitions.contentEquals(other.transitions) &&
            shifts.contentEquals(other.shifts)

    override fun hashCode(): Int = (shiftAtStart * 31 + transitions.contentHashCode()) * 31 + shifts.contentHashCode()

    override fun toString(): String = "LocalDays(shiftAtStart=$shiftAtStart, transitions=${transitions.size})"

    public companion object {
        public const val DAY_MS: Long = 86_400_000L
        private const val MILLIS_PER_SECOND = 1000L

        /** UTC offsets span -12 to +14 hours; with the day start the shift stays within a day either way. */
        public const val MAX_SHIFT_SECONDS: Int = 86_400

        /** A bad table cannot make [dayOf] slow. Real zones change at most a few times a year. */
        public const val MAX_TRANSITIONS: Int = 64

        /** Days in UTC, with no day start: what the module uses when the table has no [LocalDays]. */
        public val UTC: LocalDays = LocalDays(0)
    }
}
