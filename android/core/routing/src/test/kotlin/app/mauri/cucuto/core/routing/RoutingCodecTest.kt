package app.mauri.cucuto.core.routing

import app.mauri.cucuto.core.routing.RoutingFixtures.BUSINESS
import app.mauri.cucuto.core.routing.RoutingFixtures.WHATSAPP
import app.mauri.cucuto.core.routing.RoutingFixtures.sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RoutingCodecTest {
    @Test
    fun roundTripsEveryFeature() {
        val encoded = RoutingCodec.encode(sample)
        assertEquals(sample, RoutingCodec.decode(encoded))
        // Encoding is deterministic.
        assertEquals(encoded, RoutingCodec.encode(RoutingCodec.decode(encoded)))
    }

    @Test
    fun roundTripsEmptyAndDisabledTables() {
        assertEquals(RoutingTable.EMPTY, RoutingCodec.decode(RoutingCodec.encode(RoutingTable.EMPTY)))
        val disabled = sample.copy(enabled = false)
        assertEquals(disabled, RoutingCodec.decode(RoutingCodec.encode(disabled)))
    }

    @Test
    fun encodingIsLineBasedAndEscaped() {
        val lines = RoutingCodec.encode(sample).trimEnd('\n').split('\n')
        assertEquals("cucuto-routing\t1\t1761000000123\t1\t2\t4\t7", lines[0])
        assertEquals("P\tcom.whatsapp", lines[1])
        assertEquals("P\tcom.whatsapp.w4b", lines[2])
        assertTrue(lines.any { it.startsWith("R\t1\tTab%09here, 100%25 new%0Aline%0D\t1\t") })
        // Clip URIs share their folder once per voice.
        val frog = lines.first { it.startsWith("V\tapp.mauri.demo.critters\tfrog\t") }
        val folder = "content://app.mauri.cucuto.clips/app.mauri.demo.critters/frog/characters/frog/clips/"
        assertTrue(
            frog,
            frog.contains("\t$folder\t3\tclip-1.ogg\t1000\t-\tclip-2.ogg\t2000\tce0,,700,500,80,0,300,40\t"),
        )
        assertTrue(
            frog,
            frog.contains("\tcp0,C,800;p120,T,1000;e220,300,1000,800,120,0,400,180;e600,,0,0,50;p900,L,0\t"),
        )
    }

    @Test
    fun peeksGenerationWithoutDecoding() {
        assertEquals(1_761_000_000_123, RoutingCodec.peekGeneration(RoutingCodec.encode(sample)))
        assertNull(RoutingCodec.peekGeneration("nope"))
        assertNull(RoutingCodec.peekGeneration(null))
    }

    @Test
    fun rejectsNewerFormatVersions() {
        val newer = RoutingCodec.encode(sample).replaceFirst("cucuto-routing\t1\t", "cucuto-routing\t2\t")
        assertDecodeFails(newer, "newer")
        assertNull(RoutingCodec.decodeOrNull(newer))
    }

    @Test
    fun skipsUnknownLineTypesAndTrailingFields() {
        val encoded = RoutingCodec.encode(sample)
        val extended =
            encoded
                .replaceFirst("\nP\t", "\nX\tsomething a newer writer added\nP\t")
                .replace(Regex("(?m)^(R\t.*)$"), "$1\tfuture-field")
        assertEquals(sample, RoutingCodec.decode(extended))
    }

    @Test
    fun rejectsMalformedTables() {
        val valid = RoutingCodec.encode(sample)
        val broken =
            listOf(
                "",
                "hello",
                "cucuto-routing\t1",
                "cucuto-routing\t0\t1\t1\t0\t0\t0\n",
                // Header counts must match.
                valid.replaceFirst("\t4\t7\n", "\t4\t8\n"),
                // A route pointing past the voices.
                valid.replace("R\t0\t34600000002@s.whatsapp.net\t1\t", "R\t0\t34600000002@s.whatsapp.net\t99\t"),
                // A route pointing at an unknown package.
                valid.replace("R\t0\t34600000002@s.whatsapp.net\t", "R\t5\t34600000002@s.whatsapp.net\t"),
                // Bad haptics and escapes.
                valid.replace("\tcp0,C,800;", "\tcp0,X,800;"),
                valid.replace("\tcp0,C,800;", "\tz"),
                valid.replace("100%25", "100%2"),
                // Truncation anywhere in the body.
                valid.substring(0, valid.length / 2),
            )
        for (input in broken) assertDecodeFails(input)
        for (input in broken) assertNull(RoutingCodec.decodeOrNull(input))
    }

    @Test
    fun resolvesConversationsAndGroupPolicy() {
        val table = RoutingCodec.decode(RoutingCodec.encode(sample))

        // 1:1 chat, by phone JID or by @lid.
        assertEquals("frog", table.resolve(WHATSAPP, "34600000001@s.whatsapp.net")?.voice?.characterId)
        assertEquals("frog", table.resolve(WHATSAPP, "123456789012345@lid")?.voice?.characterId)
        // Keys are per package.
        assertNull(table.resolve(BUSINESS, "34600000001@s.whatsapp.net"))
        assertNull(table.resolve(WHATSAPP, "unknown@s.whatsapp.net"))

        // One voice per group (default): the sender does not matter.
        val group = table.resolve(WHATSAPP, "120363000000000002@g.us", senderKey = "34600000001@s.whatsapp.net")
        assertEquals("bell", group?.voice?.characterId)
        assertEquals(4L, group?.speaker?.contactId)

        // Per-sender group without its own voice: the sender's voice, or nothing.
        val perSender = table.resolve(WHATSAPP, "120363000000000001@g.us", senderKey = "34600000002@s.whatsapp.net")
        assertEquals("owl", perSender?.voice?.characterId)
        assertEquals(3L, perSender?.conversation?.contactId)
        assertEquals(2L, perSender?.speaker?.contactId)
        assertNull(table.resolve(WHATSAPP, "120363000000000001@g.us", senderKey = "stranger@s.whatsapp.net"))
        assertNull(table.resolve(WHATSAPP, "120363000000000001@g.us"))

        // The global switch.
        assertNull(table.copy(enabled = false).resolve(WHATSAPP, "34600000001@s.whatsapp.net"))
    }

    @Test
    fun routeFlags() {
        val route = Route(WHATSAPP, "g@g.us", 0, 0, Route.FLAG_GROUP or Route.FLAG_PER_SENDER, 1)
        assertTrue(route.isGroup)
        assertTrue(route.isPerSender)
        assertFalse(route.copy(flags = 0).isGroup)
    }

    @Test
    fun clipUriFollowsTheProviderScheme() {
        assertEquals(
            "content://app.mauri.cucuto.clips/app.mauri.demo.critters/frog/characters/frog/clips/ribbit.ogg",
            RoutingContract.clipUri("app.mauri.demo.critters", "frog", "characters/frog/clips/ribbit.ogg"),
        )
    }

    private fun assertDecodeFails(
        input: String,
        messagePart: String? = null,
    ) {
        try {
            RoutingCodec.decode(input)
            fail("Expected a RoutingFormatException for: ${input.take(80)}")
        } catch (e: RoutingFormatException) {
            if (messagePart != null) assertTrue(e.message, e.message.orEmpty().contains(messagePart))
        }
    }
}
