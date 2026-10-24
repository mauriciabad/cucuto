package app.mauri.cucuto.daynight

import java.text.Normalizer
import java.util.Locale

/**
 * A place in the built-in city list. Coordinates are rounded to two decimals: a degree of longitude moves sunrise
 * by 4 minutes, so this is far finer than needed.
 *
 * @property country ISO 3166 region code; the screen shows the country's name in the user's language.
 */
data class City(
    val name: String,
    val country: String,
    val latitude: Double,
    val longitude: Double,
)

/**
 * About 160 cities, bundled so that picking a place needs no network, no geocoder and no permission (ADR 0037).
 * Spain and Catalonia are well covered, plus the largest cities of every continent; anyone else types coordinates
 * or uses one location fix.
 */
object Cities {
    val all: List<City> =
        listOf(
            // Spain and Andorra
            City("Madrid", "ES", 40.42, -3.70),
            City("Barcelona", "ES", 41.39, 2.17),
            City("Valencia", "ES", 39.47, -0.38),
            City("Sevilla", "ES", 37.39, -5.98),
            City("Zaragoza", "ES", 41.65, -0.89),
            City("Málaga", "ES", 36.72, -4.42),
            City("Murcia", "ES", 37.99, -1.13),
            City("Palma", "ES", 39.57, 2.65),
            City("Las Palmas de Gran Canaria", "ES", 28.12, -15.43),
            City("Santa Cruz de Tenerife", "ES", 28.46, -16.25),
            City("Bilbao", "ES", 43.26, -2.93),
            City("Alicante", "ES", 38.35, -0.48),
            City("Córdoba", "ES", 37.89, -4.78),
            City("Valladolid", "ES", 41.65, -4.72),
            City("Vigo", "ES", 42.24, -8.72),
            City("Gijón", "ES", 43.54, -5.66),
            City("A Coruña", "ES", 43.36, -8.41),
            City("Granada", "ES", 37.18, -3.60),
            City("Vitoria-Gasteiz", "ES", 42.85, -2.67),
            City("Oviedo", "ES", 43.36, -5.85),
            City("Pamplona", "ES", 42.81, -1.64),
            City("Donostia / San Sebastián", "ES", 43.32, -1.98),
            City("Santander", "ES", 43.46, -3.81),
            City("Girona", "ES", 41.98, 2.82),
            City("Lleida", "ES", 41.62, 0.62),
            City("Tarragona", "ES", 41.12, 1.25),
            City("Reus", "ES", 41.16, 1.11),
            City("Sabadell", "ES", 41.55, 2.11),
            City("Terrassa", "ES", 41.56, 2.01),
            City("Manresa", "ES", 41.73, 1.83),
            City("Vic", "ES", 41.93, 2.25),
            City("Figueres", "ES", 42.27, 2.96),
            City("Castelló de la Plana", "ES", 39.99, -0.05),
            City("Salamanca", "ES", 40.97, -5.66),
            City("Cádiz", "ES", 36.53, -6.29),
            City("Toledo", "ES", 39.86, -4.03),
            City("Badajoz", "ES", 38.88, -6.97),
            City("Logroño", "ES", 42.47, -2.45),
            City("Santiago de Compostela", "ES", 42.88, -8.54),
            City("Eivissa / Ibiza", "ES", 38.91, 1.43),
            City("Maó / Mahón", "ES", 39.89, 4.27),
            City("Andorra la Vella", "AD", 42.51, 1.52),
            // Rest of Europe
            City("Lisboa", "PT", 38.72, -9.14),
            City("Porto", "PT", 41.15, -8.61),
            City("Paris", "FR", 48.86, 2.35),
            City("Marseille", "FR", 43.30, 5.37),
            City("Lyon", "FR", 45.76, 4.84),
            City("Toulouse", "FR", 43.60, 1.44),
            City("Perpignan", "FR", 42.70, 2.90),
            City("Bordeaux", "FR", 44.84, -0.58),
            City("London", "GB", 51.51, -0.13),
            City("Manchester", "GB", 53.48, -2.24),
            City("Edinburgh", "GB", 55.95, -3.19),
            City("Dublin", "IE", 53.35, -6.26),
            City("Amsterdam", "NL", 52.37, 4.90),
            City("Brussels", "BE", 50.85, 4.35),
            City("Luxembourg", "LU", 49.61, 6.13),
            City("Berlin", "DE", 52.52, 13.40),
            City("Munich", "DE", 48.14, 11.58),
            City("Hamburg", "DE", 53.55, 9.99),
            City("Frankfurt", "DE", 50.11, 8.68),
            City("Zürich", "CH", 47.38, 8.54),
            City("Genève", "CH", 46.20, 6.14),
            City("Wien", "AT", 48.21, 16.37),
            City("Roma", "IT", 41.90, 12.50),
            City("Milano", "IT", 45.46, 9.19),
            City("Napoli", "IT", 40.85, 14.27),
            City("Praha", "CZ", 50.08, 14.44),
            City("Warszawa", "PL", 52.23, 21.01),
            City("Budapest", "HU", 47.50, 19.04),
            City("København", "DK", 55.68, 12.57),
            City("Stockholm", "SE", 59.33, 18.07),
            City("Oslo", "NO", 59.91, 10.75),
            City("Tromsø", "NO", 69.65, 18.96),
            City("Helsinki", "FI", 60.17, 24.94),
            City("Reykjavík", "IS", 64.15, -21.94),
            City("Athína", "GR", 37.98, 23.73),
            City("İstanbul", "TR", 41.01, 28.98),
            City("București", "RO", 44.43, 26.10),
            City("Kyiv", "UA", 50.45, 30.52),
            City("Moskva", "RU", 55.76, 37.62),
            // Americas
            City("New York", "US", 40.71, -74.01),
            City("Los Angeles", "US", 34.05, -118.24),
            City("Chicago", "US", 41.88, -87.63),
            City("San Francisco", "US", 37.77, -122.42),
            City("Miami", "US", 25.76, -80.19),
            City("Houston", "US", 29.76, -95.37),
            City("Seattle", "US", 47.61, -122.33),
            City("Toronto", "CA", 43.65, -79.38),
            City("Montréal", "CA", 45.50, -73.57),
            City("Vancouver", "CA", 49.28, -123.12),
            City("Ciudad de México", "MX", 19.43, -99.13),
            City("Guadalajara", "MX", 20.66, -103.35),
            City("Monterrey", "MX", 25.69, -100.32),
            City("La Habana", "CU", 23.11, -82.37),
            City("Santo Domingo", "DO", 18.49, -69.93),
            City("San Juan", "PR", 18.47, -66.11),
            City("Ciudad de Guatemala", "GT", 14.63, -90.51),
            City("San José", "CR", 9.93, -84.08),
            City("Ciudad de Panamá", "PA", 8.98, -79.52),
            City("Bogotá", "CO", 4.71, -74.07),
            City("Medellín", "CO", 6.24, -75.58),
            City("Caracas", "VE", 10.48, -66.90),
            City("Quito", "EC", -0.18, -78.47),
            City("Lima", "PE", -12.05, -77.04),
            City("La Paz", "BO", -16.49, -68.12),
            City("Santiago", "CL", -33.45, -70.67),
            City("Buenos Aires", "AR", -34.60, -58.38),
            City("Córdoba", "AR", -31.42, -64.18),
            City("Montevideo", "UY", -34.90, -56.16),
            City("Asunción", "PY", -25.26, -57.58),
            City("São Paulo", "BR", -23.55, -46.63),
            City("Rio de Janeiro", "BR", -22.91, -43.17),
            // Africa and the Middle East
            City("Casablanca", "MA", 33.57, -7.59),
            City("Marrakech", "MA", 31.63, -8.01),
            City("Alger", "DZ", 36.75, 3.06),
            City("Tunis", "TN", 36.81, 10.18),
            City("Cairo", "EG", 30.04, 31.24),
            City("Dakar", "SN", 14.72, -17.47),
            City("Lagos", "NG", 6.52, 3.38),
            City("Malabo", "GQ", 3.75, 8.78),
            City("Nairobi", "KE", -1.29, 36.82),
            City("Johannesburg", "ZA", -26.20, 28.05),
            City("Cape Town", "ZA", -33.92, 18.42),
            City("Tel Aviv", "IL", 32.09, 34.78),
            City("Riyadh", "SA", 24.71, 46.68),
            City("Dubai", "AE", 25.20, 55.27),
            City("Tehran", "IR", 35.69, 51.39),
            // Asia and Oceania
            City("Delhi", "IN", 28.61, 77.21),
            City("Mumbai", "IN", 19.08, 72.88),
            City("Bengaluru", "IN", 12.97, 77.59),
            City("Karachi", "PK", 24.86, 67.01),
            City("Bangkok", "TH", 13.76, 100.50),
            City("Hà Nội", "VN", 21.03, 105.85),
            City("Kuala Lumpur", "MY", 3.14, 101.69),
            City("Singapore", "SG", 1.35, 103.82),
            City("Jakarta", "ID", -6.21, 106.85),
            City("Manila", "PH", 14.60, 120.98),
            City("Hong Kong", "HK", 22.32, 114.17),
            City("Taipei", "TW", 25.03, 121.57),
            City("Shanghai", "CN", 31.23, 121.47),
            City("Beijing", "CN", 39.90, 116.41),
            City("Seoul", "KR", 37.57, 126.98),
            City("Tokyo", "JP", 35.68, 139.69),
            City("Osaka", "JP", 34.69, 135.50),
            City("Sydney", "AU", -33.87, 151.21),
            City("Melbourne", "AU", -37.81, 144.96),
            City("Brisbane", "AU", -27.47, 153.03),
            City("Perth", "AU", -31.95, 115.86),
            City("Auckland", "NZ", -36.85, 174.76),
            City("Wellington", "NZ", -41.29, 174.78),
        )

    /**
     * Cities matching [query] (accents and case ignored; names starting with it first), or, for an empty query,
     * the cities of [region] (the phone's country) as suggestions. At most [limit].
     */
    fun search(
        query: String,
        region: String?,
        limit: Int = 8,
    ): List<City> {
        val q = fold(query.trim())
        if (q.isEmpty()) return all.filter { it.country.equals(region, ignoreCase = true) }.take(limit)
        val matches = all.filter { fold(it.name).contains(q) }
        return matches.sortedBy { if (fold(it.name).startsWith(q)) 0 else 1 }.take(limit)
    }

    /** The country's name in the user's language ("Espanya", "Spain"). */
    fun countryName(
        country: String,
        locale: Locale = Locale.getDefault(),
    ): String =
        Locale
            .Builder()
            .setRegion(country)
            .build()
            .getDisplayCountry(locale)
            .ifEmpty { country }

    private fun fold(text: String): String =
        Normalizer
            .normalize(text, Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .replace('ı', 'i')
            .lowercase(Locale.ROOT)

    private val DIACRITICS = Regex("\\p{Mn}+")
}
