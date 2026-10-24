package app.mauri.cucuto.core.conditions

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/** Sunrise and sunset of one local date at one place. */
public sealed interface SunTimes {
    /** The sun rises at [sunrise] and sets at [sunset]. */
    public data class Regular(
        val sunrise: Instant,
        val sunset: Instant,
    ) : SunTimes

    /** The sun stays above the horizon all day (midnight sun). */
    public data object PolarDay : SunTimes

    /** The sun stays below the horizon all day. */
    public data object PolarNight : SunTimes
}

/**
 * Sunrise and sunset from NOAA's solar calculator (the equations of its spreadsheet, after Jean Meeus,
 * *Astronomical Algorithms*): solar declination and the equation of time from the date, then the hour angle at
 * which the sun's centre is 0.833° below the horizon (refraction plus the sun's radius, the official definition).
 * Each event is computed twice, the second time with the sun's position at the first estimate, which keeps the
 * error within about a minute below the polar circles. Pure arithmetic: no library, no network.
 *
 * See https://gml.noaa.gov/grad/solcalc/calcdetails.html.
 */
public object SolarCalculator {
    /** The sun's zenith at sunrise and sunset: 90° plus 50′ (34′ of refraction and 16′ of the sun's radius). */
    private const val ZENITH_DEGREES = 90.833
    private const val MINUTES_PER_DAY = 1440.0
    private const val MILLIS_PER_MINUTE = 60_000.0
    private const val JULIAN_UNIX_EPOCH = 2440587.5
    private const val JULIAN_J2000 = 2451545.0
    private const val DAYS_PER_CENTURY = 36525.0

    /**
     * Sunrise and sunset on the local [date] in [zone] at [latitude] (degrees north, -90 to 90) and [longitude]
     * (degrees east, -180 to 180). The events are the ones around the solar noon that falls on [date] locally.
     */
    public fun sunTimes(
        date: LocalDate,
        latitude: Double,
        longitude: Double,
        zone: ZoneId,
    ): SunTimes {
        require(latitude in -90.0..90.0) { "Latitude $latitude out of range" }
        require(longitude in -180.0..180.0) { "Longitude $longitude out of range" }
        // Pick the UTC day whose solar noon falls on the local date (time zones far from their meridian).
        var utcDay = date.toEpochDay()
        for (attempt in 0 until 2) {
            val localDate = Instant.ofEpochMilli(noonMillis(utcDay, longitude)).atZone(zone).toLocalDate()
            if (localDate == date) break
            utcDay += date.toEpochDay() - localDate.toEpochDay()
        }
        val noonMinutes = solarNoonMinutes(utcDay, longitude)
        val rise = event(utcDay, latitude, longitude, noonMinutes, rising = true)
        val set = event(utcDay, latitude, longitude, noonMinutes, rising = false)
        if (rise.isNaN() || set.isNaN()) {
            val declination = declination(centuries(utcDay, noonMinutes))
            return if (isSunUp(latitude, declination)) SunTimes.PolarDay else SunTimes.PolarNight
        }
        return SunTimes.Regular(instant(utcDay, rise), instant(utcDay, set))
    }

    private fun noonMillis(
        utcDay: Long,
        longitude: Double,
    ): Long =
        (
            utcDay * MINUTES_PER_DAY * MILLIS_PER_MINUTE + solarNoonMinutes(
                utcDay,
                longitude,
            ) * MILLIS_PER_MINUTE
        ).toLong()

    private fun instant(
        utcDay: Long,
        minutes: Double,
    ): Instant =
        Instant.ofEpochMilli(floor(utcDay * MINUTES_PER_DAY * MILLIS_PER_MINUTE + minutes * MILLIS_PER_MINUTE).toLong())

    /** Solar noon in minutes after 00:00 UTC of [utcDay], refined once with the sun's position at noon. */
    private fun solarNoonMinutes(
        utcDay: Long,
        longitude: Double,
    ): Double {
        val first = 720.0 - 4.0 * longitude - equationOfTime(centuries(utcDay, 720.0 - 4.0 * longitude))
        return 720.0 - 4.0 * longitude - equationOfTime(centuries(utcDay, first))
    }

    /** Sunrise or sunset in minutes after 00:00 UTC of [utcDay], or NaN when the sun does not cross the horizon. */
    private fun event(
        utcDay: Long,
        latitude: Double,
        longitude: Double,
        noonMinutes: Double,
        rising: Boolean,
    ): Double {
        var minutes = noonMinutes
        repeat(2) {
            val t = centuries(utcDay, minutes)
            val hourAngle = hourAngle(latitude, declination(t))
            if (hourAngle.isNaN()) return Double.NaN
            val noon = 720.0 - 4.0 * longitude - equationOfTime(t)
            minutes = if (rising) noon - 4.0 * hourAngle else noon + 4.0 * hourAngle
        }
        return minutes
    }

    /** Julian centuries since J2000.0 at [minutes] after 00:00 UTC of [utcDay]. */
    private fun centuries(
        utcDay: Long,
        minutes: Double,
    ): Double = (utcDay + JULIAN_UNIX_EPOCH + minutes / MINUTES_PER_DAY - JULIAN_J2000) / DAYS_PER_CENTURY

    /** Hour angle of sunrise in degrees, or NaN for polar day or night. */
    private fun hourAngle(
        latitude: Double,
        declination: Double,
    ): Double {
        val lat = Math.toRadians(latitude)
        val cosH = cos(Math.toRadians(ZENITH_DEGREES)) / (cos(lat) * cos(declination)) - tan(lat) * tan(declination)
        if (cosH < -1.0 || cosH > 1.0 || cosH.isNaN()) return Double.NaN
        return Math.toDegrees(acos(cosH))
    }

    /** Whether the sun is above the sunrise altitude at solar noon (used only when it never crosses it). */
    private fun isSunUp(
        latitude: Double,
        declination: Double,
    ): Boolean {
        val noonZenith = abs(latitude - Math.toDegrees(declination))
        return noonZenith < ZENITH_DEGREES
    }

    private class Sun(
        val meanLongitude: Double,
        val meanAnomaly: Double,
        val eccentricity: Double,
        val obliquity: Double,
        val apparentLongitude: Double,
    )

    private fun sun(t: Double): Sun {
        val l0 = Math.toRadians((280.46646 + t * (36000.76983 + t * 0.0003032)).mod(360.0))
        val m = Math.toRadians(357.52911 + t * (35999.05029 - 0.0001537 * t))
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val center =
            sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
                sin(2 * m) * (0.019993 - 0.000101 * t) +
                sin(3 * m) * 0.000289
        val trueLongitude = Math.toDegrees(l0) + center
        val omega = Math.toRadians(125.04 - 1934.136 * t)
        val apparent = Math.toRadians(trueLongitude - 0.00569 - 0.00478 * sin(omega))
        val meanObliquity = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val obliquity = Math.toRadians(meanObliquity + 0.00256 * cos(omega))
        return Sun(l0, m, e, obliquity, apparent)
    }

    /** Solar declination in radians. */
    private fun declination(t: Double): Double {
        val sun = sun(t)
        return asin(sin(sun.obliquity) * sin(sun.apparentLongitude))
    }

    /** Equation of time in minutes (true solar time minus mean solar time). */
    private fun equationOfTime(t: Double): Double {
        val sun = sun(t)
        val y = tan(sun.obliquity / 2).let { it * it }
        val l0 = sun.meanLongitude
        val m = sun.meanAnomaly
        val e = sun.eccentricity
        val radians =
            y * sin(2 * l0) -
                2 * e * sin(m) +
                4 * e * y * sin(m) * cos(2 * l0) -
                0.5 * y * y * sin(4 * l0) -
                1.25 * e * e * sin(2 * m)
        return 4.0 * Math.toDegrees(radians)
    }
}
