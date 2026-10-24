package app.mauri.cucuto.core.data.routing

import app.mauri.cucuto.core.data.di.WallClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A local date in a time zone: what clip conditions are compiled from (ADR 0037). */
data class LocalDay(
    val date: LocalDate,
    val zone: ZoneId,
)

/** Today's date and time zone, as a flow, so the routing table's clip conditions move forward with the calendar. */
fun interface LocalCalendar {
    fun days(): Flow<LocalDay>
}

/**
 * The system calendar: emits today at once, then again after each local midnight or time zone change, checked at
 * least hourly while collected (only while the app process runs; a coroutine delay never wakes the phone).
 */
class SystemCalendar(
    private val clock: WallClock,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : LocalCalendar {
    override fun days(): Flow<LocalDay> =
        flow {
            while (true) {
                val zone = zone()
                val date = LocalDate.ofInstant(Instant.ofEpochMilli(clock.now()), zone)
                emit(LocalDay(date, zone))
                val nextMidnight =
                    date
                        .plusDays(1)
                        .atStartOfDay(zone)
                        .toInstant()
                        .toEpochMilli()
                delay((nextMidnight - clock.now()).coerceIn(MIN_WAIT_MS, MAX_WAIT_MS))
            }
        }.distinctUntilChanged()

    private companion object {
        const val MIN_WAIT_MS = 1_000L
        const val MAX_WAIT_MS = 3_600_000L
    }
}
