package app.mauri.cucuto.home

import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.chats.ChatVoice
import app.mauri.cucuto.testing.TestData
import app.mauri.cucuto.testing.TestData.ADA
import app.mauri.cucuto.testing.TestData.BEA
import app.mauri.cucuto.testing.TestData.CLUB
import app.mauri.cucuto.testing.TestData.CRITTERS
import app.mauri.cucuto.testing.TestData.KITCHEN
import app.mauri.cucuto.testing.TestData.NOW
import app.mauri.cucuto.testing.TestData.chat
import app.mauri.cucuto.testing.TestData.packs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeModelTest {
    private val chats =
        listOf(
            chat(ADA, "Ada", lastSeenAt = NOW - 60_000),
            chat(CLUB, "Book club", lastSeenAt = NOW - 120_000),
            chat(BEA, "Bea", voice = ChatVoice(CRITTERS, "frog"), lastSeenAt = NOW - 180_000),
        )

    private fun build(
        query: String = "",
        filter: String? = null,
        input: List<app.mauri.cucuto.core.data.chats.Chat> = chats,
    ) = buildHomeState(input, packs, query, filter, NOW)

    @Test
    fun recentSendersAreRecentChatsWithoutAVoice() {
        val state = build()
        assertFalse(state.loading)
        assertEquals(listOf(ADA, CLUB), state.recent.map { it.key })
        assertEquals(listOf(ADA, CLUB, BEA), state.chats.map { it.key })
        assertEquals(listOf(ADA, CLUB), state.unassigned.map { it.key })
        assertTrue(state.showRecent)
    }

    @Test
    fun theStripForgetsSendersAfterAWeekAndNeverSeenChats() {
        val old = chat(ADA, "Ada", lastSeenAt = NOW - HomeRules.RECENT_WINDOW_MS - 1)
        val edge = chat(BEA, "Bea", lastSeenAt = NOW - HomeRules.RECENT_WINDOW_MS)
        val restored = chat(CLUB, "Book club", lastSeenAt = null)
        val state = build(input = listOf(edge, old, restored))
        assertEquals(listOf(BEA), state.recent.map { it.key })
        // They are still chats, and still offered by the FAB sheet.
        assertEquals(3, state.unassigned.size)
    }

    @Test
    fun theStripIsCapped() {
        val many = (1..30).map { chat(ChatKey(TestData.WA, "1000$it@lid"), "P$it", lastSeenAt = NOW - it) }
        assertEquals(HomeRules.RECENT_LIMIT, build(input = many).recent.size)
    }

    @Test
    fun rowsCarryTheirCharacterAndPack() {
        val bea = build().chats.single { it.key == BEA }
        assertEquals("Bleep the frog", bea.character?.name)
        assertEquals("Chiptune critters", bea.packName)
        assertFalse(bea.voiceMissing)
    }

    @Test
    fun aVoiceFromARemovedPackIsMissing() {
        val gone = chat(BEA, "Bea", voice = ChatVoice("app.mauri.demo.removed", "ghost"))
        val row = build(input = listOf(gone)).chats.single()
        assertNull(row.character)
        assertTrue(row.voiceMissing)
        // Assigned, even if broken: it is not a recent sender waiting for a voice.
        assertTrue(build(input = listOf(gone)).recent.isEmpty())
    }

    @Test
    fun searchMatchesNamesCharactersAndPacksIgnoringCaseAndAccents() {
        val accented = listOf(chat(ADA, "José"), chat(CLUB, "Book club"), chats[2])
        assertEquals(listOf(ADA), build(query = "jose", input = accented).chats.map { it.key })
        assertEquals(listOf(BEA), build(query = "FROG").chats.map { it.key })
        assertEquals(listOf(BEA), build(query = " critters ").chats.map { it.key })
        assertEquals(emptyList<ChatKey>(), build(query = "zebra").chats.map { it.key })
        assertTrue(build(query = "zebra").filtering)
        assertFalse(build(query = "a").showRecent)
    }

    @Test
    fun thePackFilterKeepsChatsVoicedByThatPack() {
        assertEquals(listOf(BEA), build(filter = CRITTERS).chats.map { it.key })
        assertEquals(emptyList<ChatKey>(), build(filter = KITCHEN).chats.map { it.key })
        assertFalse(build(filter = CRITTERS).showRecent)
    }

    @Test
    fun aFilterForAnUninstalledPackIsIgnored() {
        val state = build(filter = "app.mauri.demo.removed")
        assertNull(state.packFilter)
        assertEquals(3, state.chats.size)
    }

    @Test
    fun countsTellNoChatsFromNoMatches() {
        assertEquals(0, build(input = emptyList()).totalChats)
        assertEquals(3, build(query = "zebra").totalChats)
        assertEquals(listOf(CRITTERS, KITCHEN), build().packs.map { it.packId })
        assertFalse(buildHomeState(chats, emptyList(), "", null, NOW).hasPacks)
    }

    @Test
    fun searchableStripsAccentsAndCase() {
        assertEquals("aeiou n", "ÁÉÍÓÚ Ñ".searchable())
    }
}
