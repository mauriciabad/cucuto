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
        assertEquals("cucuto-routing\t1\t1761000000123\t1\t2\t4\t7\t1", lines[0])
        assertEquals("P\tcom.whatsapp", lines[1])
        assertEquals("P\tcom.whatsapp.w4b", lines[2])
        // The frog's and the bell's second clips share one envelope, stored once (off the grid: full syntax).
        assertEquals("H\tce0,,700,500,80,0,300,40", lines[3])
        assertTrue(lines.any { it.startsWith("R\t1\tTab%09here, 100%25 new%0Aline%0D\t1\t") })
        // Clip URIs share their folder once per voice; clip haptics are references in a trailing field.
        val frog = lines.first { it.startsWith("V\tapp.mauri.demo.critters\tfrog\t") }
        val folder = "content://app.mauri.cucuto.clips/app.mauri.demo.critters/frog/characters/frog/clips/"
        assertTrue(
            frog,
            frog.endsWith("\t$folder\t3\tclip-1.ogg\t1000\t-\tclip-2.ogg\t2000\t-\tclip-3.ogg\t3000\t-\th-,0,-"),
        )
        val owl = lines.first { it.startsWith("V\tapp.mauri.demo.critters\towl\t") }
        assertTrue(owl, owl.endsWith("\tclip-1.ogg\t1000\t-"))
        assertTrue(
            frog,
            frog.contains("\tcp0,C,800;p120,T,1000;e220,300,1000,800,120,0,400,180;e600,,0,0,50;p900,L,0\t"),
        )
    }

    @Test
    fun roundTripsMirroredClipsAndTheSelfTestClipAlongsideClipEnvelopes() {
        val mirror = "file:///storage/emulated/0/Android/media/app.mauri.cucuto/clips"
        val mirrored =
            sample.copy(
                voices =
                    sample.voices.map { voice ->
                        voice.copy(
                            clips =
                                voice.clips.map {
                                    it.copy(uri = it.uri.replace("content://app.mauri.cucuto.clips", "$mirror/x"))
                                },
                        )
                    },
                selfTestClip = "$mirror/selftest/chirp.wav",
            )
        val encoded = RoutingCodec.encode(mirrored)
        assertEquals(mirrored, RoutingCodec.decode(encoded))
        val lines = encoded.split('\n')
        // Right after the header, so a reader that predates it skips one unknown line.
        assertEquals("S\t$mirror/selftest/chirp.wav", lines[1])
        assertTrue(lines.any { it.contains("\t$mirror/x/app.mauri.demo.critters/frog/characters/frog/clips/\t3\t") })
        // The clip envelope dictionary (H lines, h fields) still carries the auto haptics of mirrored clips.
        assertTrue(mirrored.voices.any { voice -> voice.clips.any { it.haptic != null } })
        assertTrue(lines.any { it.startsWith("H\t") })
        assertTrue(lines.any { it.startsWith("V\t") && it.contains("\th") })
        // Without one, nothing is written and the module keeps the provider's chirp.
        assertFalse(RoutingCodec.encode(sample).contains("\nS\t"))
        assertNull(RoutingCodec.decode(RoutingCodec.encode(sample)).selfTestClip)
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
                .replace(Regex("(?m)^(V\t.*)$"), "$1\tfuture-field")
                .replaceFirst("\t4\t7\t1\n", "\t4\t7\t1\t0\tfuture-field\n")
        assertEquals(sample, RoutingCodec.decode(extended))
    }

    @Test
    fun storesEachClipEnvelopeOnceInTheCompactForm() {
        val envelope = HapticGrid.snap(RoutingFixtures.autoEnvelope(seed = 1, points = 16))
        val other = HapticGrid.snap(RoutingFixtures.autoEnvelope(seed = 2, points = 3))

        fun voice(
            id: String,
            clips: Int,
        ) = RoutingFixtures.voice("app.mauri.demo.critters", id, clips).let { v ->
            v.copy(clips = v.clips.mapIndexed { i, c -> c.copy(haptic = if (i == 0) envelope else other) })
        }
        val table = sample.copy(voices = listOf(voice("frog", 3), voice("frog", 2), voice("owl", 1)) + sample.voices)
        val encoded = RoutingCodec.encode(table)
        assertEquals(table, RoutingCodec.decode(encoded))

        val lines = encoded.split('\n')
        val dictionary = lines.filter { it.startsWith("H\t") }
        // Two grid envelopes (compact) plus the sample's off-grid one.
        assertEquals(3, dictionary.size)
        assertTrue(dictionary[0], dictionary[0].startsWith("H\tz") && dictionary[0].length < 60)
        assertTrue(lines[0].endsWith("\t3"))
        // Clips reference the dictionary; their inline haptic fields stay empty for older readers.
        assertTrue(lines.any { it.endsWith("\th0,1,1") })
        assertTrue(lines.any { it.endsWith("\th0,1") })
        assertTrue(lines.any { it.endsWith("\th0") })
    }

    @Test
    fun readersFromBeforeTheDictionaryGetTablesWithoutClipEnvelopes() {
        // What a version 1 reader without the dictionary sees: it skips H lines, trailing fields and extra header
        // fields. Simulate it by removing them, then compare with the table minus clip haptics.
        val oldView =
            RoutingCodec
                .encode(sample)
                .replace(Regex("(?m)^H\t.*\n"), "")
                .replace(Regex("\th[-0-9,]*(?=\n)"), "")
                .replaceFirst("\t4\t7\t1\n", "\t4\t7\n")
        val withoutClipHaptics =
            sample.copy(voices = sample.voices.map { v -> v.copy(clips = v.clips.map { it.copy(haptic = null) }) })
        assertEquals(withoutClipHaptics, RoutingCodec.decode(oldView))
    }

    @Test
    fun readsInlineClipHapticsFromWritersBeforeTheDictionary() {
        val old =
            "cucuto-routing\t1\t5\t1\t1\t1\t1\n" +
                "P\tcom.whatsapp\n" +
                "V\tp.a\tc\ta\tcontent://x/\t2\ta.ogg\t1000\tce0,,700,500,80,0,300,40\tb.ogg\t1000\t-\n" +
                "R\t0\tk@s.whatsapp.net\t0\t1\t0\t1\n"
        val clips = RoutingCodec.decode(old).voices[0].clips
        assertEquals(RoutingFixtures.voice("p.a", "c", 2).clips[1].haptic, clips[0].haptic)
        assertNull(clips[1].haptic)
    }

    @Test
    fun decodesTheDocumentedExample() {
        // docs/routing-table.md, "Encoding".
        val base = "content://app.mauri.cucuto.clips/app.mauri.demo.critters"
        val example =
            "cucuto-routing\t1\t1761000000123\t1\t1\t2\t2\t1\n" +
                "P\tcom.whatsapp\n" +
                "H\tz0,,_gCAgG\n" +
                "V\tapp.mauri.demo.critters\tfrog\tcp0,C,800;e220,,1000,800,120,0,400,180" +
                "\t$base/frog/characters/frog/clips/\t2\tribbit.ogg\t2000\t-\tcroak.ogg\t1000\t-\n" +
                "V\tapp.mauri.demo.critters\towl\ta\t$base/owl/characters/owl/clips/" +
                "\t2\thoot.ogg\t1000\t-\tsilence.ogg\t1000\t-\th0,-\n" +
                "R\t0\t34600000001@s.whatsapp.net\t0\t42\t0\t1\n" +
                "R\t0\t123456789012345@lid\t1\t42\t0\t1\n"
        val table = RoutingCodec.decode(example)
        val owl = table.resolve(WHATSAPP, "123456789012345@lid")!!.voice
        assertEquals(
            Haptic.Custom(
                listOf(HapticEvent.Envelope(0, listOf(ControlPoint(1000, 508, 40), ControlPoint(0, 508, 120)))),
            ),
            owl.clips[0].haptic,
        )
        assertNull(owl.clips[1].haptic)
        assertEquals(example, RoutingCodec.encode(table))
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
                valid.replaceFirst("\t4\t7\t1\n", "\t4\t8\t1\n"),
                valid.replaceFirst("\t4\t7\t1\n", "\t4\t7\t2\n"),
                // Clip haptic references: unknown index, wrong count, not a number.
                valid.replace("\th-,0,-", "\th-,1,-"),
                valid.replace("\th-,0,-", "\th-,0"),
                valid.replace("\th-,0,-", "\th-,x,-"),
                // A bad clip haptic in the dictionary.
                valid.replace("H\tce0,,700,500,80,0,300,40", "H\ta"),
                valid.replace("H\tce0,,700,500,80,0,300,40", "H\tz0,,AB"),
                valid.replace("H\tce0,,700,500,80,0,300,40", "H\tz0,,A*A"),
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
    fun rejectsCountsLargerThanTheTextBeforeAllocating() {
        // Without the bound these size lists of two billion entries (an OutOfMemoryError in system_server).
        val hugeHeader = "cucuto-routing\t1\t5\t1\t1\t2000000000\t0\nP\tcom.whatsapp\n"
        val hugeClipCount =
            "cucuto-routing\t1\t5\t1\t1\t1\t0\nP\tcom.whatsapp\nV\tp.a\tc\tk\tbase/\t2000000000\n"
        val hugeDictionary = "cucuto-routing\t1\t5\t1\t0\t0\t0\t2147483647\n"
        for (input in listOf(hugeHeader, hugeClipCount, hugeDictionary)) {
            assertDecodeFails(input)
            assertNull(RoutingCodec.decodeOrNull(input))
        }
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

    @Test
    fun encodesSingleHaptics() {
        assertEquals(
            "cp0,C,800;p120,T,1000;e220,300,1000,800,120,0,400,180;e600,,0,0,50;p900,L,0",
            RoutingCodec.encodeHaptic(RoutingFixtures.frogCustom),
        )
        for (haptic in listOf(Haptic.Keep, Haptic.Off, Haptic.Auto, RoutingFixtures.frogCustom)) {
            assertEquals(haptic, RoutingCodec.decodeHaptic(RoutingCodec.encodeHaptic(haptic)))
        }
        for (bad in listOf("", "c", "x", "cp0,C", "ce0,,1000", "cq1", "cp0,Z,1000", "cpx,C,1")) {
            try {
                RoutingCodec.decodeHaptic(bad)
                fail("Expected a RoutingFormatException for '$bad'")
            } catch (_: RoutingFormatException) {
                // Expected.
            }
        }
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
