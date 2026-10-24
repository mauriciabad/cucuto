package app.mauri.cucuto.picker

import app.mauri.cucuto.core.data.settings.RememberedFolds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Folding, search and "Surprise me" rules of the pickers (ADR 0072, #289, #290). */
class PackSectionsTest {
    private data class Pack(
        val id: String,
        val name: String,
        val characters: List<String>,
    )

    // Made-up packs: a small one, a large creature pack and a large song pack, like the owner's 268 and 98.
    private val critters = Pack("critters", "Chiptune critters", listOf("Bleep the frog", "Hoot the owl"))
    private val creatures = Pack("creatures", "Pocket creatures", List(268) { "Creature $it" } + "Élan the elk")
    private val songs = Pack("songs", "Island songs", List(98) { "Song $it" })
    private val packs = listOf(critters, creatures, songs)

    private fun sections(
        folds: PackFolds = PackFolds(),
        current: String? = null,
        chip: String? = null,
        query: String = "",
    ) = packSections(packs, { it.id }, { it.name }, { it.characters }, { it }, chip, query, folds, current)

    private fun List<PackSection<String>>.expanded() = filter { it.expanded }.map { it.packId }

    @Test
    fun smallPacksStartUnfoldedAndLargeOnesFolded() {
        val sections = sections()
        assertEquals(listOf("critters"), sections.expanded())
        assertTrue(sections.all { it.foldable })
        assertEquals(listOf(2, 269, 98), sections.map { it.total })
        assertEquals(emptyList<String>(), sections.first { it.packId == "creatures" }.shown)
    }

    @Test
    fun theContactsCurrentPackIsAlwaysUnfolded() {
        val remembered = PackFolds(RememberedFolds(folded = setOf("songs")))
        assertEquals(listOf("critters", "songs"), sections(remembered, current = "songs").expanded())
    }

    @Test
    fun whatTheUserChoseLastTimeIsRemembered() {
        val remembered = PackFolds(RememberedFolds(expanded = setOf("creatures"), folded = setOf("critters")))
        assertEquals(listOf("creatures"), sections(remembered).expanded())
    }

    @Test
    fun aFoldOnThisScreenWinsOverEverything() {
        val folds = PackFolds(RememberedFolds(expanded = setOf("songs"))).with("songs", false).with("critters", false)
        assertEquals(emptyList<String>(), sections(folds, current = "critters").expanded())
        assertEquals(listOf("creatures"), sections(folds.with("creatures", true)).expanded())
    }

    @Test
    fun aPacksChipJumpsToItUnfoldedWithNoFoldControl() {
        val sections = sections(chip = "songs")
        assertEquals(listOf("songs"), sections.map { it.packId })
        assertTrue(sections.single().expanded)
        assertFalse(sections.single().foldable)
        assertEquals(98, sections.single().shown.size)
    }

    @Test
    fun searchFindsCharactersInFoldedPacksAndUnfoldsOnlyTheMatches() {
        val folded = PackFolds(RememberedFolds(folded = setOf("critters", "creatures", "songs")))
        val sections = sections(folded, query = "  ELAN ")
        assertEquals(listOf("creatures"), sections.map { it.packId })
        assertEquals(listOf("Élan the elk"), sections.single().shown)
        assertFalse(sections.single().foldable)
        assertEquals(269, sections.single().total)
        // Clearing the search brings the fold state back.
        assertEquals(emptyList<String>(), sections(folded).expanded())
    }

    @Test
    fun searchOnAPackNameShowsTheWholePack() {
        val sections = sections(query = "island")
        assertEquals(listOf("songs"), sections.map { it.packId })
        assertEquals(98, sections.single().shown.size)
    }

    @Test
    fun searchWorksInsideTheChosenChipAndCanFindNothing() {
        assertEquals(emptyList<PackSection<String>>(), sections(chip = "critters", query = "song"))
        assertEquals(listOf("Hoot the owl"), sections(chip = "critters", query = "owl").single().shown)
    }

    @Test
    fun theGridIndexCountsHeadersAndUnfoldedCharactersOnly() {
        val sections = sections(PackFolds().with("songs", true))
        // 2 leading items, critters header (2), frog (3), owl (4), creatures header (5, folded), songs header (6).
        assertEquals(7, sections.gridIndexOf(2) { it == "Song 0" })
        assertEquals(4, sections.gridIndexOf(2) { it == "Hoot the owl" })
        assertNull(sections.gridIndexOf(2) { it == "Creature 7" })
    }

    @Test
    fun surprisePicksOnlyFromThePoolAndNeverThePreviousPick() {
        val pool = listOf("a", "b", "c")
        val random = Random(42)
        var previous: String? = null
        repeat(200) {
            val pick = SurprisePick.pick(pool, previous, random)
            assertTrue(pick in pool)
            assertNotEquals(previous, pick)
            previous = pick
        }
    }

    @Test
    fun surpriseReachesEveryEligibleCharacter() {
        val pool = listOf("a", "b", "c", "d")
        val random = Random(7)
        val seen = mutableSetOf<String>()
        var previous: String? = null
        repeat(100) {
            previous = SurprisePick.pick(pool, previous, random)
            seen += previous!!
        }
        assertEquals(pool.toSet(), seen)
    }

    @Test
    fun aSingleCharacterIsPickedAgainAndAnEmptyPoolPicksNothing() {
        assertEquals("solo", SurprisePick.pick(listOf("solo"), "solo"))
        assertNull(SurprisePick.pick(emptyList<String>(), null))
    }
}
