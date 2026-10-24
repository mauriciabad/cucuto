package app.mauri.cucuto.daynight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CitiesTest {
    @Test
    fun searchIgnoresAccentsAndCaseAndPutsPrefixesFirst() {
        assertEquals("Málaga", Cities.search("malaga", null).first().name)
        assertEquals("Córdoba", Cities.search("CORDOBA", null).first().name)
        assertEquals(2, Cities.search("cordoba", null).size)
        assertEquals("Barcelona", Cities.search("barc", null).single().name)
        // Names starting with "la" (La Paz, Lagos...) come before names that only contain it (Málaga, Palma...).
        val la = Cities.search("la", null)
        val prefixes = la.takeWhile { it.name.lowercase().startsWith("la") }
        assertTrue(prefixes.size >= 4)
        assertTrue(la.drop(prefixes.size).none { it.name.lowercase().startsWith("la") })
        assertTrue(Cities.search("zzzz", null).isEmpty())
    }

    @Test
    fun anEmptyQuerySuggestsThePhonesCountry() {
        val suggestions = Cities.search("", "ES")
        assertEquals(8, suggestions.size)
        assertTrue(suggestions.all { it.country == "ES" })
        assertTrue(Cities.search("", null).isEmpty())
    }

    @Test
    fun everyCityHasValidUniqueCoordinates() {
        for (city in Cities.all) {
            assertTrue(city.name, city.latitude in -90.0..90.0 && city.longitude in -180.0..180.0)
            assertEquals(city.name, 2, city.country.length)
        }
        assertEquals(
            Cities.all.size,
            Cities.all
                .map { it.name to it.country }
                .toSet()
                .size,
        )
    }

    @Test
    fun typedDegreesAcceptDotsCommasAndMinusSigns() {
        assertEquals(41.39, DayNightViewModel.parseDegrees(" 41.39 ")!!, 0.0)
        assertEquals(41.39, DayNightViewModel.parseDegrees("41,39")!!, 0.0)
        assertEquals(-3.7, DayNightViewModel.parseDegrees("−3.7")!!, 0.0)
        assertNull(DayNightViewModel.parseDegrees("north"))
        assertNull(DayNightViewModel.parseDegrees("NaN"))
    }
}
