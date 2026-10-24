package app.mauri.cucuto.core.conditions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.abs

/**
 * Reference times come from an independent implementation of NOAA's algorithm (the Python library `astral` 3.2).
 * The tolerance is one minute.
 */
class SolarCalculatorTest {
    private fun check(
        latitude: Double,
        longitude: Double,
        zone: String,
        date: String,
        sunrise: String,
        sunset: String,
        toleranceMs: Long = 60_000,
    ) {
        val times = SolarCalculator.sunTimes(LocalDate.parse(date), latitude, longitude, ZoneId.of(zone))
        assertTrue("$date at $latitude,$longitude: $times", times is SunTimes.Regular)
        times as SunTimes.Regular
        val expectedRise = OffsetDateTime.parse(sunrise).toInstant().toEpochMilli()
        val expectedSet = OffsetDateTime.parse(sunset).toInstant().toEpochMilli()
        assertClose("sunrise $date", expectedRise, times.sunrise.toEpochMilli(), toleranceMs)
        assertClose("sunset $date", expectedSet, times.sunset.toEpochMilli(), toleranceMs)
    }

    private fun assertClose(
        what: String,
        expected: Long,
        actual: Long,
        toleranceMs: Long,
    ) {
        assertTrue("$what off by ${(actual - expected) / 1000} s", abs(actual - expected) <= toleranceMs)
    }

    @Test
    fun londonSolsticesAndDstDays() {
        check(51.5074, -0.1278, "Europe/London", "2024-06-21", "2024-06-21T04:43:33+01:00", "2024-06-21T21:21:17+01:00")
        check(51.5074, -0.1278, "Europe/London", "2024-12-21", "2024-12-21T08:04:21Z", "2024-12-21T15:53:18Z")
        // The days the clocks change.
        check(51.5074, -0.1278, "Europe/London", "2024-03-31", "2024-03-31T06:37:29+01:00", "2024-03-31T19:32:34+01:00")
        check(51.5074, -0.1278, "Europe/London", "2024-10-27", "2024-10-27T06:46:13Z", "2024-10-27T16:41:35Z")
    }

    @Test
    fun spain() {
        check(41.3874, 2.1686, "Europe/Madrid", "2024-06-21", "2024-06-21T06:18:21+02:00", "2024-06-21T21:28:07+02:00")
        check(41.3874, 2.1686, "Europe/Madrid", "2024-12-21", "2024-12-21T08:14:27+01:00", "2024-12-21T17:24:50+01:00")
        check(41.3874, 2.1686, "Europe/Madrid", "2025-03-30", "2025-03-30T07:37:51+02:00", "2025-03-30T20:14:14+02:00")
        check(40.4168, -3.7038, "Europe/Madrid", "2024-06-21", "2024-06-21T06:45:07+02:00", "2024-06-21T21:48:20+02:00")
        check(40.4168, -3.7038, "Europe/Madrid", "2024-12-21", "2024-12-21T08:34:51+01:00", "2024-12-21T17:51:25+01:00")
    }

    @Test
    fun westernAndSouthernHemispheres() {
        check(
            40.7128,
            -74.0060,
            "America/New_York",
            "2024-03-20",
            "2024-03-20T06:58:42-04:00",
            "2024-03-20T19:08:28-04:00",
        )
        check(
            40.7128,
            -74.0060,
            "America/New_York",
            "2024-09-22",
            "2024-09-22T06:44:20-04:00",
            "2024-09-22T18:52:01-04:00",
        )
        check(
            -33.8688,
            151.2093,
            "Australia/Sydney",
            "2024-12-21",
            "2024-12-21T05:41:07+11:00",
            "2024-12-21T20:05:26+11:00",
        )
        check(
            -33.8688,
            151.2093,
            "Australia/Sydney",
            "2024-06-21",
            "2024-06-21T07:00:18+10:00",
            "2024-06-21T16:53:41+10:00",
        )
        check(
            -0.1807,
            -78.4678,
            "America/Guayaquil",
            "2024-01-01",
            "2024-01-01T06:13:26-05:00",
            "2024-01-01T18:21:09-05:00",
        )
    }

    @Test
    fun timeZonesFarFromTheirMeridian() {
        // Samoa and Kiritimati keep clocks 13 and 14 hours ahead of UTC at about 170 and 157 degrees west.
        check(
            -13.8333,
            -171.7667,
            "Pacific/Apia",
            "2024-07-01",
            "2024-07-01T06:51:29+13:00",
            "2024-07-01T18:10:28+13:00",
        )
        check(
            1.8721,
            -157.4278,
            "Pacific/Kiritimati",
            "2024-07-01",
            "2024-07-01T06:26:54+14:00",
            "2024-07-01T18:40:16+14:00",
        )
    }

    @Test
    fun nearThePolarCircleTheSunSetsAfterMidnight() {
        // The sun grazes the horizon this far north, so a few seconds of declination move the event: 2 minutes.
        check(
            64.1466,
            -21.9426,
            "Atlantic/Reykjavik",
            "2024-06-21",
            "2024-06-21T02:56:25Z",
            "2024-06-22T00:02:52Z",
            120_000,
        )
        check(
            64.1466,
            -21.9426,
            "Atlantic/Reykjavik",
            "2024-12-21",
            "2024-12-21T11:23:27Z",
            "2024-12-21T15:28:46Z",
            120_000,
        )
    }

    @Test
    fun polarDayAndNight() {
        val tromso = ZoneId.of("Europe/Oslo")
        assertEquals(
            SunTimes.PolarDay,
            SolarCalculator.sunTimes(LocalDate.parse("2024-06-21"), 69.6492, 18.9553, tromso),
        )
        assertEquals(
            SunTimes.PolarNight,
            SolarCalculator.sunTimes(LocalDate.parse("2024-12-21"), 69.6492, 18.9553, tromso),
        )
        val mcmurdo = ZoneId.of("Antarctica/McMurdo")
        assertEquals(
            SunTimes.PolarDay,
            SolarCalculator.sunTimes(LocalDate.parse("2024-12-21"), -77.85, 166.67, mcmurdo),
        )
        assertEquals(
            SunTimes.PolarNight,
            SolarCalculator.sunTimes(LocalDate.parse("2024-06-21"), -77.85, 166.67, mcmurdo),
        )
    }
}
