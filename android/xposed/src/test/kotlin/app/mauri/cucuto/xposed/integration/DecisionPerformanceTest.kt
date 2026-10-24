package app.mauri.cucuto.xposed.integration

import app.mauri.cucuto.core.routing.RoutingCodec
import app.mauri.cucuto.core.routing.RoutingTable
import app.mauri.cucuto.xposed.events.Outcome
import app.mauri.cucuto.xposed.route.AlertRouter
import app.mauri.cucuto.xposed.route.NotificationSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory

/**
 * The decision path on a real table (the newest golden, which has every feature): speed and allocation in steady
 * state, measured on the JVM with generous bounds so a slow CI runner does not flake (ADR 0047).
 */
class DecisionPerformanceTest {
    private val table: RoutingTable =
        RoutingCodec.decode(
            checkNotNull(javaClass.getResource("/integration/golden/${RoutingGoldenTest.GOLDENS.last()}")).readText(),
        )
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private val alerts =
        listOf(
            "1:1, day and night clips" to
                NotificationSnapshot(WHATSAPP, "individual_chat_defaults_5", shortcutId = "111111111111111@lid"),
            "1:1, auto envelopes" to
                NotificationSnapshot(WHATSAPP, "individual_chat_defaults_5", shortcutId = "222222222222222@lid"),
            "per-sender group" to
                NotificationSnapshot(
                    WHATSAPP,
                    "group_chat_defaults_6",
                    shortcutId = "120363000000000001@g.us",
                    senderKey = "222222222222222@lid",
                ),
            "Business" to
                NotificationSnapshot(BUSINESS, "individual_chat_defaults_5", shortcutId = "555555555555555@lid"),
            "unassigned" to
                NotificationSnapshot(WHATSAPP, "individual_chat_defaults_5", shortcutId = "999999999999999@lid"),
        )

    private fun bytes(): Long = threads.getThreadAllocatedBytes(Thread.currentThread().threadId())

    @Test
    fun decisionsAreFast() {
        val router = AlertRouter(now = { NOON })
        repeat(WARM_UP) { for ((_, s) in alerts) router.decide(s, table) }
        for ((name, snapshot) in alerts) {
            val samples = LongArray(RUNS)
            for (i in 0 until RUNS) {
                val start = System.nanoTime()
                router.decide(snapshot, table)
                samples[i] = System.nanoTime() - start
            }
            samples.sort()
            val median = samples[RUNS / 2]
            val p99 = samples[RUNS * 99 / 100]
            println("$name: median ${median / 1000.0} us, p99 ${p99 / 1000.0} us")
            // The brief's budget is 5 ms for the whole hook; the routing decision must be a small part of it.
            assertTrue("$name: median $median ns", median < 100_000)
            assertTrue("$name: p99 $p99 ns", p99 < 1_000_000)
        }
    }

    @Test
    fun decisionsDoNotAllocateInSteadyState() {
        val router = AlertRouter(now = { NOON })
        repeat(WARM_UP) { for ((_, s) in alerts) router.decide(s, table) }
        for ((name, snapshot) in alerts) {
            val decision = router.decide(snapshot, table)!!
            assertEquals(name, if (name == "unassigned") Outcome.UNASSIGNED else Outcome.SWAPPED, decision.outcome)
            val before = bytes()
            repeat(RUNS) { router.decide(snapshot, table) }
            val perDecision = (bytes() - before) / RUNS
            println("$name: $perDecision bytes per decision")
            assertTrue("$name: $perDecision bytes per decision", perDecision <= MAX_BYTES_PER_DECISION)
        }
    }

    private companion object {
        const val WHATSAPP = "com.whatsapp"
        const val BUSINESS = "com.whatsapp.w4b"
        const val WARM_UP = 20_000
        val NOON = Pipeline.at(12, 0)
        const val RUNS = 10_000

        /**
         * The result only: the [app.mauri.cucuto.xposed.route.Decision], the identity it reports (with its key list)
         * and the table's match, 128 bytes on a 64-bit JVM. Table lookups, the clip pool and the shuffle bag allocate
         * nothing (#207).
         */
        const val MAX_BYTES_PER_DECISION = 160L
    }
}
