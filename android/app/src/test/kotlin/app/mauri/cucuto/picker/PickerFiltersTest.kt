package app.mauri.cucuto.picker

import app.mauri.cucuto.core.data.voices.VoicePack
import app.mauri.cucuto.testing.TestData
import app.mauri.cucuto.testing.TestData.character
import app.mauri.cucuto.ui.components.PackChip
import app.mauri.cucuto.ui.components.packChips
import app.mauri.cucuto.ui.components.selectedChip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The capability filters of the pickers (ADR 0063, #245). */
class PickerFiltersTest {
    private val tweet = character("tweet", "Tweet the bird")
    private val bubblegum = character("bubblegum", "Bubblegum K.K.", packId = SONGS, clips = 0).copy(ringtoneCount = 2)
    private val pikachu = character("pikachu", "Pikachu", packId = CREATURES).copy(ringtoneCount = 1)
    private val creatures = VoicePack(CREATURES, "Creatures", listOf(pikachu))
    private val critters = VoicePack(TestData.CRITTERS, "Chiptune critters", listOf(tweet))
    private val songs = VoicePack(SONGS, "K.K. Slider", listOf(bubblegum))

    @Test
    fun whatACharacterOffersComesFromItsClipsAndRingtones() {
        assertTrue(tweet.forMessages)
        assertFalse(tweet.forCalls)
        assertFalse(bubblegum.forMessages)
        assertTrue(bubblegum.forCalls)
        assertTrue(pikachu.forMessages && pikachu.forCalls)
    }

    @Test
    fun theVoicePickerNeverListsRingtoneOnlyCharacters() {
        val mixed = VoicePack(SONGS, "K.K. Slider", listOf(bubblegum, tweet.copy(packId = SONGS)))

        val offered = voicePacks(listOf(critters, songs, mixed, creatures))

        assertEquals(listOf(TestData.CRITTERS, SONGS, CREATURES), offered.map { it.packId })
        assertEquals(listOf("tweet"), offered[1].characters.map { it.id })
        assertTrue(offered.flatMap { it.characters }.none { it.id == "bubblegum" })
        // Packs with only message characters come back as they are.
        assertSame(critters, offered[0])
    }

    @Test
    fun aPickerWithOnlySongsHasNothingForMessages() {
        assertEquals(emptyList<VoicePack>(), voicePacks(listOf(songs)))
    }

    @Test
    fun chipsShowOnlyPacksWithEligibleCharactersWithTheirCounts() {
        val packs = listOf(critters, VoicePack(SONGS, "K.K. Slider", emptyList()), creatures)

        val chips = packChips(packs, { it.packId }, { it.name }, { it.characters.size })

        assertEquals(
            listOf(PackChip(TestData.CRITTERS, "Chiptune critters", 1), PackChip(CREATURES, "Creatures", 1)),
            chips,
        )
    }

    @Test
    fun onePackNeedsNoChips() {
        assertEquals(
            emptyList<PackChip>(),
            packChips(listOf(critters), { it.packId }, { it.name }, { it.characters.size }),
        )
    }

    @Test
    fun aChosenChipThatIsGoneFallsBackToAll() {
        val chips = listOf(PackChip("a", "A", 1), PackChip("b", "B", 2))
        assertEquals("b", selectedChip(chips, "b"))
        assertNull(selectedChip(chips, "removed"))
        assertNull(selectedChip(chips, null))
    }

    @Test
    fun theStateFiltersByTheChosenPack() {
        val state = PickerState(loading = false, packs = voicePacks(listOf(critters, songs, creatures)))
        assertEquals(2, state.chips.size)
        assertEquals(listOf(CREATURES), state.copy(packFilter = CREATURES).visiblePacks.map { it.packId })
        assertEquals(2, state.copy(packFilter = SONGS).visiblePacks.size)
    }

    private companion object {
        const val SONGS = "com.example.songs"
        const val CREATURES = "com.example.creatures"
    }
}
