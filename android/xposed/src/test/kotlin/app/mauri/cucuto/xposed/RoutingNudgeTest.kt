package app.mauri.cucuto.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingNudgeTest {
    @Test
    fun onlyTheModulesOwnAppMayNudge() {
        assertTrue(acceptsNudge("app.mauri.cucuto", "app.mauri.cucuto"))
        assertFalse(acceptsNudge("com.example.spoof", "app.mauri.cucuto"))
        assertFalse(acceptsNudge(null, "app.mauri.cucuto")) // identity not shared
        assertFalse(acceptsNudge("app.mauri.cucuto", null))
        assertFalse(acceptsNudge("", ""))
    }

    @Test
    fun burstsOfNudgesCoalesceIntoOneRead() {
        val posted = ArrayDeque<() -> Unit>()
        var reads = 0
        val coalescer = Coalescer(post = { posted.addLast(it) }, task = { reads++ })

        repeat(5) { coalescer.request() }
        assertEquals(1, posted.size)
        posted.removeFirst()()
        assertEquals(1, reads)

        coalescer.request()
        assertEquals(1, posted.size)
    }

    @Test
    fun aNudgeDuringTheReadSchedulesOneMore() {
        val posted = ArrayDeque<() -> Unit>()
        lateinit var coalescer: Coalescer
        var reads = 0
        coalescer =
            Coalescer(post = { posted.addLast(it) }, task = {
                reads++
                if (reads == 1) coalescer.request() // the app published again while we were reading
            })

        coalescer.request()
        posted.removeFirst()()
        assertEquals(1, posted.size)
        posted.removeFirst()()
        assertEquals(2, reads)
        assertTrue(posted.isEmpty())
    }

    @Test
    fun aFailedPostDoesNotBlockLaterNudges() {
        var fail = true
        val posted = ArrayDeque<() -> Unit>()
        val coalescer =
            Coalescer(post = {
                if (fail) error("handler gone")
                posted.addLast(it)
            }, task = {})

        coalescer.request()
        fail = false
        coalescer.request()
        assertEquals(1, posted.size)
    }
}
