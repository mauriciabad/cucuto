package app.mauri.cucuto.core.routing

import app.mauri.cucuto.core.routing.RoutingFixtures.WHATSAPP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Size and speed for a heavy user: 500 contacts, each with a phone JID and an `@lid` key, 50 groups, 120
 * characters of 12 clips each, and 60 contacts with their own clip toggles or haptic override.
 *
 * Limits are loose enough for a slow CI runner; the point is to catch an order-of-magnitude regression. The
 * notification path only ever runs [RoutingTable.resolve]; decoding happens when the app republishes.
 */
class RoutingScaleTest {
    private val table: RoutingTable = buildLargeTable()

    @Test
    fun encodedSizeStaysSmall() {
        val encoded = RoutingCodec.encode(table)
        println(
            "Routing table for 500 contacts: ${encoded.length} chars, ${table.routes.size} routes, ${table.voices.size} voices",
        )
        assertTrue("Encoded table is ${encoded.length} chars", encoded.length < 160 * 1024)
        assertTrue(encoded.length < RoutingContract.MAX_ENCODED_CHARS * 2 / 3)
    }

    @Test
    fun decodesQuickly() {
        val encoded = RoutingCodec.encode(table)
        repeat(20) { RoutingCodec.decode(encoded) } // warm up the JIT
        val runs = 20
        val start = System.nanoTime()
        repeat(runs) { assertEquals(table, RoutingCodec.decode(encoded)) }
        val perDecodeMs = (System.nanoTime() - start) / runs / 1_000_000.0
        println("Decode: %.2f ms".format(perDecodeMs))
        assertTrue("Decoding took $perDecodeMs ms", perDecodeMs < 50)
    }

    @Test
    fun lookupsStayFarUnderTheHookBudget() {
        val keys = table.routes.map { it.key }
        repeat(10_000) { i -> table.resolve(WHATSAPP, keys[i % keys.size], keys[(i * 7) % keys.size]) }
        val lookups = 100_000
        val start = System.nanoTime()
        var hits = 0
        for (i in 0 until lookups) {
            if (table.resolve(WHATSAPP, keys[i % keys.size], keys[(i * 7) % keys.size]) != null) hits++
        }
        val perLookupUs = (System.nanoTime() - start) / lookups / 1000.0
        println("Lookup: %.3f µs".format(perLookupUs))
        assertTrue(hits > 0)
        // The hook's whole budget is 5 ms; a lookup must be a tiny fraction of it.
        assertTrue("A lookup took $perLookupUs µs", perLookupUs < 50)
    }

    @Test
    fun everyContactResolves() {
        for (route in table.routes) {
            if (!route.isGroup) assertNotNull(route.key, table.resolve(route.packageName, route.key))
        }
    }

    private fun buildLargeTable(): RoutingTable {
        val characters = 120
        val voices =
            (0 until characters)
                .map { c ->
                    RoutingFixtures.voice("io.github.creator$c.pack", "character-$c", clips = 12)
                }.toMutableList()
        val routes = ArrayList<Route>()
        for (contact in 0 until 500) {
            val isGroup = contact % 10 == 0
            var voice = contact % characters
            if (contact % 9 == 0) {
                // Personal clip toggles or a haptic override make a voice of their own.
                val base = voices[voice]
                voices += base.copy(clips = base.clips.drop(2), haptic = RoutingFixtures.frogCustom)
                voice = voices.lastIndex
            }
            val seed = contact * 31
            if (isGroup) {
                val flags = Route.FLAG_GROUP or (if (contact % 20 == 0) Route.FLAG_PER_SENDER else 0)
                routes +=
                    Route(WHATSAPP, "1203630000${"%08d".format(contact)}@g.us", voice, seed, flags, contact.toLong())
            } else {
                routes +=
                    Route(WHATSAPP, "3460${"%07d".format(contact)}@s.whatsapp.net", voice, seed, 0, contact.toLong())
                routes += Route(WHATSAPP, "1${"%014d".format(contact)}@lid", voice, seed, 0, contact.toLong())
            }
        }
        return RoutingTable(generation = 1, enabled = true, voices = voices, routes = routes)
    }
}
