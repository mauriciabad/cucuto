package app.mauri.cucuto.xposed.events

import app.mauri.cucuto.xposed.CucutoModule
import app.mauri.cucuto.xposed.ModuleLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventQueueTest {
    private fun event(n: Int) =
        DecisionEvent(
            timeMillis = n.toLong(),
            packageName = "com.whatsapp",
            conversationKey = "$n@lid",
            displayName = "Name $n",
            isGroup = false,
            senderKey = null,
            senderName = null,
            contactId = n.toLong(),
            outcome = "SWAPPED",
            clipUri = "content://app.mauri.cucuto.clips/p.q/c/$n.ogg",
            detail = null,
            hookMicros = 120,
        )

    private val queue =
        EventQueue(
            capacity = 5,
            debounceMillis = 2_000,
            minIntervalMillis = 10_000,
            initialBackoffMillis = 30_000,
            maxBackoffMillis = 100_000,
        )

    @Test
    fun firstEventIsDebounced() {
        assertTrue(queue.offer(event(1)))
        assertFalse(queue.offer(event(2)))
        assertEquals(1_002_000, queue.nextDeliveryAt(1_000_000))
    }

    @Test
    fun deliveriesAreAtLeastTheMinimumIntervalApart() {
        queue.offer(event(1))
        queue.take(now = 1_000_000)
        queue.completed(listOf(event(1)), delivered = true)
        queue.offer(event(2))
        assertEquals(1_010_000, queue.nextDeliveryAt(1_001_000))
        // Long after the interval only the debounce applies.
        assertEquals(1_102_000, queue.nextDeliveryAt(1_100_000))
    }

    @Test
    fun failuresBackOffExponentiallyAndKeepTheEvents() {
        queue.offer(event(1))
        queue.offer(event(2))
        var now = 0L
        val expectedBackoff = listOf(30_000L, 60_000L, 100_000L, 100_000L)
        for (backoff in expectedBackoff) {
            val batch = queue.take(now)
            assertEquals(listOf(1L, 2L), batch.map { it.timeMillis })
            queue.completed(batch, delivered = false)
            assertEquals(now + 10_000 + backoff, queue.nextDeliveryAt(now))
            now += 1_000_000
        }
        val batch = queue.take(now)
        queue.completed(batch, delivered = true)
        assertTrue(queue.isEmpty)
        queue.offer(event(3))
        assertEquals(now + 10_000, queue.nextDeliveryAt(now))
    }

    @Test
    fun batchesAreCapped() {
        repeat(5) { queue.offer(event(it)) }
        assertEquals(listOf(0L, 1L), queue.take(0, max = 2).map { it.timeMillis })
        assertEquals(3, queue.size)
    }

    @Test
    fun overflowDropsTheOldestAndCountsThem() {
        repeat(8) { queue.offer(event(it)) }
        assertEquals(3, queue.dropped)
        assertEquals((3L..7L).toList(), queue.take(0).map { it.timeMillis })
    }

    @Test
    fun failedBatchGoesBackInFrontOfNewerEvents() {
        repeat(3) { queue.offer(event(it)) }
        val batch = queue.take(0)
        queue.offer(event(10))
        queue.offer(event(11))
        queue.offer(event(12))
        queue.completed(batch, delivered = false)
        // 6 events for 5 slots: the oldest goes.
        assertEquals(1, queue.dropped)
        assertEquals(listOf(1L, 2L, 10L, 11L, 12L), queue.take(0).map { it.timeMillis })
    }

    @Test
    fun takingFromAnEmptyQueueDoesNotCountAsAnAttempt() {
        assertTrue(queue.take(5_000).isEmpty())
        queue.offer(event(1))
        assertEquals(7_000, queue.nextDeliveryAt(5_000))
    }

    @Test
    fun eventsSurviveTheWireFormat() {
        val original = event(4)
        val map = original.toMap()
        assertEquals(original, DecisionEvent.fromMap(map::get))
        val noContact = original.copy(contactId = null, conversationKey = null, clipUri = null)
        val noContactMap = noContact.toMap()
        assertEquals(noContact, DecisionEvent.fromMap(noContactMap::get))
        assertNull(DecisionEvent.fromMap { key -> if (key == EventContract.F_PACKAGE) null else map[key] })
    }

    @Test
    fun statusSurvivesTheWireFormat() {
        val status = ModuleStatus(1, listOf("playSound", "playVibration"), emptyList(), 7, 3, 9, 1, 4_200, 0)
        val map = status.toMap()
        assertEquals(status, ModuleStatus.fromMap(map::get))
    }

    @Test
    fun logLinesNeverContainKeysOrNames() {
        val line =
            CucutoModule.describe(
                event(123_456_789).copy(contactId = 7, clipUri = "content://app.mauri.cucuto.clips/p.q/c/a.ogg"),
            )
        assertFalse(line, line.contains("123456789"))
        assertFalse(line, line.contains("Name"))
        assertTrue(line, line.contains("key=@lid"))
    }

    @Test
    fun keyShapeKeepsOnlyTheJidDomain() {
        assertEquals("@lid", ModuleLog.keyShape("123@lid"))
        assertEquals("@g.us", ModuleLog.keyShape("120363@g.us"))
        assertEquals("<title>", ModuleLog.keyShape("Mum"))
        assertEquals("<title>", ModuleLog.keyShape("Ana @ work"))
        assertEquals("<none>", ModuleLog.keyShape(null))
    }
}
