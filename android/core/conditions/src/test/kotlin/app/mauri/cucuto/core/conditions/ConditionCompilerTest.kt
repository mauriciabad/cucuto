package app.mauri.cucuto.core.conditions

import app.mauri.cucuto.core.routing.TimeCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay
import java.time.ZoneId
import java.time.ZonedDateTime

class ConditionCompilerTest {
    private val madrid = ZoneId.of("Europe/Madrid")
    private val barcelona = Place(41.3874, 2.1686, "Barcelona")

    private fun ms(text: String): Long = ZonedDateTime.parse(text).toInstant().toEpochMilli()

    private fun TimeCondition.at(text: String): Int = state(ms(text))

    @Test
    fun dayFollowsSunriseAndSunset() {
        val compiler = ConditionCompiler(barcelona, madrid, LocalDate.parse("2024-06-21"))
        val day = compiler.compile(ClipWhen(TimeOfDay.Day))
        val night = compiler.compile(ClipWhen(TimeOfDay.Night))
        // Sunrise 06:18:21, sunset 21:28:07 (CEST).
        assertEquals(TimeCondition.INACTIVE, day.at("2024-06-21T06:17:00+02:00"))
        assertEquals(TimeCondition.ACTIVE, day.at("2024-06-21T06:20:00+02:00"))
        assertEquals(TimeCondition.ACTIVE, day.at("2024-06-21T21:27:00+02:00"))
        assertEquals(TimeCondition.INACTIVE, day.at("2024-06-21T21:30:00+02:00"))
        assertEquals(TimeCondition.ACTIVE, night.at("2024-06-21T23:59:00+02:00"))
        assertEquals(TimeCondition.ACTIVE, night.at("2024-06-22T03:00:00+02:00"))
        assertEquals(TimeCondition.INACTIVE, night.at("2024-06-22T12:00:00+02:00"))
        // Months later, after the clocks change, it still follows the sun.
        assertEquals(TimeCondition.INACTIVE, day.at("2024-12-21T08:13:00+01:00"))
        assertEquals(TimeCondition.ACTIVE, day.at("2024-12-21T08:16:00+01:00"))
        assertEquals(TimeCondition.ACTIVE, night.at("2024-12-21T17:26:00+01:00"))
    }

    @Test
    fun dayAndNightAreComplementsEverywhereInTheSpan() {
        val compiler = ConditionCompiler(barcelona, madrid, LocalDate.parse("2024-10-20"), horizonDays = 30)
        val day = compiler.compile(ClipWhen(TimeOfDay.Day))
        val night = compiler.compile(ClipWhen(TimeOfDay.Night))
        var t = compiler.fromMs
        while (t < compiler.untilMs) {
            assertTrue(day.state(t) != night.state(t))
            assertEquals(compiler.isDay(t), day.state(t) == TimeCondition.ACTIVE)
            t += 7 * 60_000
        }
        // About two toggles a day.
        assertTrue(day.toggleCount in 60..62)
    }

    @Test
    fun withoutAPlaceDayIsSevenToSeven() {
        val compiler = ConditionCompiler(null, madrid, LocalDate.parse("2024-03-30"))
        val day = compiler.compile(ClipWhen(TimeOfDay.Day))
        assertEquals(TimeCondition.INACTIVE, day.at("2024-03-30T06:59:00+01:00"))
        assertEquals(TimeCondition.ACTIVE, day.at("2024-03-30T07:00:00+01:00"))
        assertEquals(TimeCondition.INACTIVE, day.at("2024-03-30T19:00:00+01:00"))
        // The clocks go forward on the 31st; still 07:00 local.
        assertEquals(TimeCondition.ACTIVE, day.at("2024-03-31T07:00:00+02:00"))
        assertEquals(TimeCondition.INACTIVE, day.at("2024-03-31T06:59:00+02:00"))
    }

    @Test
    fun hourRangesWrapMidnightAndRespectWeekdays() {
        val compiler = ConditionCompiler(barcelona, madrid, LocalDate.parse("2024-10-24"))
        val late = compiler.compile(ClipWhen(TimeOfDay.Range(LocalTime.of(22, 0), LocalTime.of(6, 0))))
        assertEquals(TimeCondition.ACTIVE, late.at("2024-10-24T23:00:00+02:00"))
        assertEquals(TimeCondition.ACTIVE, late.at("2024-10-25T05:59:00+02:00"))
        assertEquals(TimeCondition.INACTIVE, late.at("2024-10-25T06:00:00+02:00"))
        assertEquals(TimeCondition.INACTIVE, late.at("2024-10-25T21:59:00+02:00"))

        val lateRange = TimeOfDay.Range(LocalTime.of(22, 0), LocalTime.of(6, 0))
        val saturdayNights = compiler.compile(ClipWhen(lateRange, weekdays = setOf(DayOfWeek.SATURDAY)))
        // 2024-10-26 is a Saturday: its early hours (the tail of Friday's range) and its late evening match.
        assertEquals(TimeCondition.ACTIVE, saturdayNights.at("2024-10-26T05:00:00+02:00"))
        assertEquals(TimeCondition.INACTIVE, saturdayNights.at("2024-10-26T12:00:00+02:00"))
        assertEquals(TimeCondition.ACTIVE, saturdayNights.at("2024-10-26T23:00:00+02:00"))
        // Sunday 01:00 is Sunday: every key must match.
        assertEquals(TimeCondition.INACTIVE, saturdayNights.at("2024-10-27T01:00:00+02:00"))
        assertEquals(TimeCondition.INACTIVE, saturdayNights.at("2024-10-25T23:00:00+02:00"))
    }

    @Test
    fun seasonsAreMirroredInTheSouth() {
        val today = LocalDate.parse("2024-11-30")
        val winter = ClipWhen(seasons = setOf(Season.WINTER))
        val north = ConditionCompiler(barcelona, madrid, today).compile(winter)
        assertEquals(TimeCondition.INACTIVE, north.at("2024-11-30T23:59:00+01:00"))
        assertEquals(TimeCondition.ACTIVE, north.at("2024-12-01T00:00:00+01:00"))
        assertEquals(TimeCondition.INACTIVE, north.at("2025-03-01T00:00:00+01:00"))

        val sydney = ZoneId.of("Australia/Sydney")
        val south = ConditionCompiler(Place(-33.8688, 151.2093), sydney, today).compile(winter)
        assertEquals(TimeCondition.INACTIVE, south.at("2025-01-15T12:00:00+11:00"))
        assertEquals(TimeCondition.ACTIVE, south.at("2025-07-15T12:00:00+10:00"))
    }

    @Test
    fun polarDayHasNoNight() {
        val tromso = Place(69.6492, 18.9553)
        val compiler =
            ConditionCompiler(tromso, ZoneId.of("Europe/Oslo"), LocalDate.parse("2024-06-21"), horizonDays = 10)
        val night = compiler.compile(ClipWhen(TimeOfDay.Night))
        assertEquals(0, night.toggleCount)
        assertEquals(false, night.activeAtFrom)
        assertEquals(TimeCondition.INACTIVE, night.at("2024-06-22T01:00:00+02:00"))
    }

    @Test
    fun outsideTheSpanTheStateIsUnknown() {
        val compiler = ConditionCompiler(barcelona, madrid, LocalDate.parse("2024-06-21"), horizonDays = 5)
        val day = compiler.compile(ClipWhen(TimeOfDay.Day))
        assertEquals(ms("2024-06-20T00:00:00+02:00"), compiler.fromMs)
        assertEquals(ms("2024-06-26T00:00:00+02:00"), compiler.untilMs)
        assertEquals(TimeCondition.UNKNOWN, day.state(compiler.fromMs - 1))
        assertEquals(TimeCondition.UNKNOWN, day.state(compiler.untilMs))
        // Every toggle is a whole second, as the routing table requires.
        for (i in 0 until day.toggleCount) assertEquals(0L, day.toggle(i) % 1000)
    }

    @Test
    fun parsesPackValues() {
        assertEquals(ClipWhen(TimeOfDay.Night), ClipWhen.of(time = "night"))
        assertEquals(
            ClipWhen(
                TimeOfDay.Range(LocalTime.of(22, 0), LocalTime.of(6, 30)),
                weekdays = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
            ),
            ClipWhen.of(from = "22:00", to = "06:30", weekdays = listOf("sat", "sun")),
        )
        assertEquals(ClipWhen(seasons = setOf(Season.AUTUMN)), ClipWhen.of(seasons = listOf("autumn")))
        assertEquals(null, ClipWhen.of(time = "dusk"))
        assertEquals(null, ClipWhen.of(from = "25:00", to = "06:00"))
        assertEquals(null, ClipWhen.of())
        assertEquals(
            ClipWhen(
                dates =
                    listOf(
                        DateSpan(MonthDay.of(12, 24), MonthDay.of(1, 6)),
                        DateSpan(MonthDay.of(2, 29), MonthDay.of(2, 29)),
                    ),
            ),
            ClipWhen.of(dates = listOf("12-24" to "01-06", "02-29" to "02-29")),
        )
        assertEquals(null, ClipWhen.of(dates = listOf("02-30" to "02-30")))
        assertEquals(null, ClipWhen.of(dates = listOf("13-01" to "13-02")))
    }

    @Test
    fun datesRepeatEveryYearAndWrapTheNewYear() {
        val compiler = ConditionCompiler(null, madrid, LocalDate.parse("2026-10-24"))
        val halloween = compiler.compile(ClipWhen(dates = listOf(DateSpan(MonthDay.of(10, 25), MonthDay.of(10, 31)))))
        assertEquals(TimeCondition.INACTIVE, halloween.at("2026-10-24T23:59:00+02:00"))
        assertEquals(TimeCondition.ACTIVE, halloween.at("2026-10-25T00:00:00+02:00"))
        assertEquals(TimeCondition.ACTIVE, halloween.at("2026-10-31T23:59:00+01:00"))
        assertEquals(TimeCondition.INACTIVE, halloween.at("2026-11-01T00:00:00+01:00"))
        // Next year too, within the 400 days.
        assertEquals(TimeCondition.ACTIVE, halloween.at("2027-10-28T12:00:00+02:00"))
        assertEquals(4, halloween.toggleCount)

        val holidays = compiler.compile(ClipWhen(dates = listOf(DateSpan(MonthDay.of(12, 24), MonthDay.of(1, 6)))))
        assertEquals(TimeCondition.ACTIVE, holidays.at("2026-12-31T23:00:00+01:00"))
        assertEquals(TimeCondition.ACTIVE, holidays.at("2027-01-06T23:00:00+01:00"))
        assertEquals(TimeCondition.INACTIVE, holidays.at("2027-01-07T00:00:00+01:00"))

        // A night on Halloween week: dates AND time.
        val spooky =
            compiler.compile(
                ClipWhen(TimeOfDay.Night, dates = listOf(DateSpan(MonthDay.of(10, 25), MonthDay.of(10, 31)))),
            )
        assertEquals(TimeCondition.ACTIVE, spooky.at("2026-10-26T22:00:00+01:00"))
        assertEquals(TimeCondition.INACTIVE, spooky.at("2026-10-26T12:00:00+01:00"))
        assertEquals(TimeCondition.INACTIVE, spooky.at("2026-11-02T22:00:00+01:00"))

        // 29 February only exists in leap years: none in the next 400 days from here.
        val leap = compiler.compile(ClipWhen(dates = listOf(DateSpan(MonthDay.of(2, 29), MonthDay.of(2, 29)))))
        assertEquals(0, leap.toggleCount)
        assertEquals(TimeCondition.INACTIVE, leap.at("2027-03-01T12:00:00+01:00"))
    }

    @Test
    fun localDaysStartAtFourAndFollowDaylightSaving() {
        val compiler = ConditionCompiler(null, madrid, LocalDate.parse("2026-10-24"))
        val days = compiler.localDays()
        // CEST until 25 October, then CET, CEST again on 28 March 2027 and CET on 31 October 2027.
        assertEquals(7200 - 4 * 3600, days.shiftAtStart)
        assertEquals(3, days.transitionCount)
        assertEquals(ms("2026-10-25T03:00:00+02:00"), days.transition(0))
        assertEquals(3600 - 4 * 3600, days.shift(0))
        val day = days.dayOf(ms("2026-10-26T04:00:00+01:00"))
        assertEquals(day, days.dayOf(ms("2026-10-27T03:59:59+01:00")))
        assertEquals(day + 1, days.dayOf(ms("2026-10-27T04:00:00+01:00")))
        assertEquals(day - 1, days.dayOf(ms("2026-10-26T03:59:59+01:00")))
        // The 25-hour day of the change is one day.
        assertEquals(days.dayOf(ms("2026-10-25T04:00:00+01:00")), days.dayOf(ms("2026-10-26T03:59:00+01:00")))
        assertEquals(day - 1, days.dayOf(ms("2026-10-25T04:00:00+01:00")))
        // Zones without daylight saving have no transitions.
        assertEquals(
            0,
            ConditionCompiler(null, ZoneId.of("Asia/Tokyo"), LocalDate.parse("2026-10-24")).localDays().transitionCount,
        )
    }
}
