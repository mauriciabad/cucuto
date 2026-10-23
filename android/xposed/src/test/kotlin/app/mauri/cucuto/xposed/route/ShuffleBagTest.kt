package app.mauri.cucuto.xposed.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ShuffleBagTest {
    private fun bag(
        vararg milliWeights: Int,
        seed: Long = 42,
        last: Int = ShuffleBag.NONE,
    ) = ShuffleBag(milliWeights, Random(seed), last)

    @Test
    fun neverPlaysTheSameClipTwiceInARow() {
        for (clips in 2..6) {
            for (seed in 0L until 50L) {
                val bag = bag(*IntArray(clips) { 1000 }, seed = seed)
                var previous = -1
                repeat(500) {
                    val next = bag.next()
                    assertNotEquals("clips=$clips seed=$seed draw=$it", previous, next)
                    previous = next
                }
            }
        }
    }

    @Test
    fun neverRepeatsEvenWithHeavyWeights() {
        val bag = bag(100_000, 1000, 500)
        var previous = -1
        repeat(2_000) {
            val next = bag.next()
            assertNotEquals(previous, next)
            previous = next
        }
    }

    @Test
    fun equalWeightsPlayEveryClipOncePerRound() {
        val bag = bag(1000, 1000, 1000, 1000, seed = 7)
        // Every window of four draws aligned to a refill holds each clip once, except where the no-repeat rule
        // carries one clip over. Over many rounds the counts must stay level.
        val counts = IntArray(4)
        repeat(4_000) { counts[bag.next()]++ }
        counts.forEach { assertTrue("counts=${counts.toList()}", it in 990..1010) }
    }

    @Test
    fun weightsSkewTheMixWithinTheNoRepeatRule() {
        // Weight 2 versus two clips of weight 1: the heavy clip plays about half the time.
        val bag = bag(2000, 1000, 1000, seed = 3)
        val counts = IntArray(3)
        repeat(8_000) { counts[bag.next()]++ }
        assertTrue("counts=${counts.toList()}", counts[0] in 3_700..4_300)
        assertTrue("counts=${counts.toList()}", counts[1] in 1_700..2_300)
    }

    @Test
    fun aSingleClipAlwaysPlays() {
        val bag = bag(1000)
        repeat(10) { assertEquals(0, bag.next()) }
    }

    @Test
    fun honoursTheClipPlayedBeforeARebuild() {
        repeat(50) { seed ->
            val bag = bag(1000, 1000, seed = seed.toLong(), last = 1)
            assertNotEquals(1, bag.next())
        }
    }

    @Test
    fun copiesFollowWeightsAndAreClamped() {
        assertEquals(1, ShuffleBag.copiesFor(1))
        assertEquals(2, ShuffleBag.copiesFor(500))
        assertEquals(4, ShuffleBag.copiesFor(1000))
        assertEquals(400, ShuffleBag.copiesFor(100_000))
        assertEquals(400, ShuffleBag.copiesFor(Int.MAX_VALUE))
        assertEquals(1, ShuffleBag.copiesFor(-5))
    }

    @Test
    fun bagsRememberTheLastClipAcrossARepublish() {
        val bags = ShuffleBags(randomFor = { Random(it.toLong()) })
        val before = listOf("content://x/a.ogg", "content://x/b.ogg")
        repeat(100) { round ->
            val first = bags.next("speaker", before, intArrayOf(1000, 1000), seed = round)
            // The table is republished with a third clip in front: the bag is rebuilt, but must not repeat.
            val after = listOf("content://x/c.ogg") + before
            val second = bags.next("speaker", after, intArrayOf(1000, 1000, 1000), seed = round)
            assertNotEquals(before[first], after[second])
            bags.clear()
        }
    }

    @Test
    fun bagsAreBounded() {
        val bags = ShuffleBags(capacity = 3, randomFor = { Random(1) })
        val clips = listOf("a.ogg", "b.ogg")
        // Fill four speakers; the oldest is evicted, so its no-repeat memory is gone, but nothing grows past 3.
        for (speaker in listOf("s1", "s2", "s3", "s4")) bags.next(speaker, clips, intArrayOf(1000, 1000), 1)
        val field = ShuffleBags::class.java.getDeclaredField("entries").apply { isAccessible = true }
        assertEquals(3, (field.get(bags) as Map<*, *>).size)
    }
}
