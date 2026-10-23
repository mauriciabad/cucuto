package app.mauri.cucuto.xposed.events

/**
 * Bounded FIFO of [DecisionEvent]s waiting to be delivered to the app, plus the delivery schedule. Pure (time is
 * passed in), so the rate limiting is unit-tested on the JVM; [EventReporter] runs it on a background thread.
 *
 * Schedule:
 * - The first event after a quiet period is delivered [debounceMillis] later, so a burst goes out as one call.
 * - Two deliveries are at least [minIntervalMillis] apart: at most one app wake-up per interval, however busy the
 *   chats are.
 * - After a failed delivery (app not installed, provider refused, Binder error) the next attempt backs off,
 *   doubling from [initialBackoffMillis] up to [maxBackoffMillis]. Events wait in the queue meanwhile.
 * - The queue holds at most [capacity] events; the oldest are dropped first and counted in [dropped].
 *
 * Thread-safe: every method synchronises on the queue.
 */
class EventQueue(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val debounceMillis: Long = 2_000,
    private val minIntervalMillis: Long = 10_000,
    private val initialBackoffMillis: Long = 30_000,
    private val maxBackoffMillis: Long = 15 * 60_000,
) {
    private val events = ArrayDeque<DecisionEvent>()
    private var lastAttemptAt = Long.MIN_VALUE
    private var backoffMillis = 0L

    /** Events dropped since boot. */
    var dropped: Long = 0
        private set

    val size: Int get() = synchronized(events) { events.size }

    /** Queues [event]; returns true when the caller should schedule a delivery (the queue was empty). */
    fun offer(event: DecisionEvent): Boolean =
        synchronized(events) {
            val wasEmpty = events.isEmpty()
            events.addLast(event)
            while (events.size > capacity) {
                events.removeFirst()
                dropped++
            }
            wasEmpty
        }

    /** When the next delivery may run, given that something is queued and the time is [now]. */
    fun nextDeliveryAt(now: Long): Long =
        synchronized(events) {
            val earliest =
                if (lastAttemptAt ==
                    Long.MIN_VALUE
                ) {
                    now
                } else {
                    lastAttemptAt + minIntervalMillis + backoffMillis
                }
            maxOf(now + debounceMillis, earliest)
        }

    /** Takes up to [max] events, oldest first, for one delivery attempt at [now]. */
    fun take(
        now: Long,
        max: Int = EventContract.MAX_EVENTS_PER_CALL,
    ): List<DecisionEvent> =
        synchronized(events) {
            if (events.isEmpty()) return@synchronized emptyList()
            lastAttemptAt = now
            val n = minOf(max, events.size)
            List(n) { events.removeFirst() }
        }

    /** Reports the outcome of the attempt that took [batch]; a failed batch goes back to the front. */
    fun completed(
        batch: List<DecisionEvent>,
        delivered: Boolean,
    ) = synchronized(events) {
        if (delivered) {
            backoffMillis = 0
            return@synchronized
        }
        backoffMillis = if (backoffMillis == 0L) initialBackoffMillis else minOf(backoffMillis * 2, maxBackoffMillis)
        for (i in batch.indices.reversed()) events.addFirst(batch[i])
        while (events.size > capacity) {
            events.removeFirst()
            dropped++
        }
    }

    val isEmpty: Boolean get() = synchronized(events) { events.isEmpty() }

    companion object {
        const val DEFAULT_CAPACITY: Int = 200
    }
}
