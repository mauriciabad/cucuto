package app.mauri.cucuto.daynight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.cucuto.core.conditions.ConditionCompiler
import app.mauri.cucuto.core.conditions.Place
import app.mauri.cucuto.core.conditions.SunTimes
import app.mauri.cucuto.core.data.di.WallClock
import app.mauri.cucuto.core.data.settings.DayNightSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * What the Day and night screen shows.
 *
 * @property place The chosen place, or null (day is then 07:00 to 19:00).
 * @property today Today's sunrise and sunset there.
 * @property isDay Whether day clips play right now.
 * @property zone The phone's time zone, for formatting.
 */
data class DayNightUiState(
    val place: Place?,
    val today: SunTimes,
    val isDay: Boolean,
    val zone: ZoneId,
)

/** Progress of "Use my location once". */
sealed interface LocateStatus {
    data object Idle : LocateStatus

    data object Locating : LocateStatus

    data object Found : LocateStatus

    data object Denied : LocateStatus

    data object Off : LocateStatus

    data object Unavailable : LocateStatus
}

/** Why typed coordinates were refused. */
enum class CoordinateError { LATITUDE, LONGITUDE }

/**
 * The Day and night screen (ADR 0037): where sunrise and sunset are computed for. The place comes from the city
 * list, typed coordinates, or one approximate location fix the user asks for. Saving it republishes the routing
 * table (the source observes [DayNightSettings]).
 */
@HiltViewModel
class DayNightViewModel
    @Inject
    constructor(
        private val settings: DayNightSettings,
        private val location: DeviceLocation,
        private val clock: WallClock,
    ) : ViewModel() {
        private val minutes =
            flow {
                while (true) {
                    emit(clock.now())
                    delay(MINUTE_MS - clock.now() % MINUTE_MS)
                }
            }

        val state: StateFlow<DayNightUiState?> =
            combine(settings.place, minutes) { place, now -> describe(place, now) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

        private val _locate = MutableStateFlow<LocateStatus>(LocateStatus.Idle)
        val locate: StateFlow<LocateStatus> = _locate.asStateFlow()

        fun choose(city: City) {
            viewModelScope.launch { settings.setPlace(Place(city.latitude, city.longitude, city.name)) }
        }

        /** Saves typed coordinates (dot or comma decimals); returns what is wrong with them, or null when saved. */
        fun saveCoordinates(
            latitude: String,
            longitude: String,
        ): CoordinateError? {
            val lat = parseDegrees(latitude)?.takeIf { it in -90.0..90.0 } ?: return CoordinateError.LATITUDE
            val lon = parseDegrees(longitude)?.takeIf { it in -180.0..180.0 } ?: return CoordinateError.LONGITUDE
            viewModelScope.launch { settings.setPlace(Place(lat, lon)) }
            return null
        }

        fun forgetPlace() {
            viewModelScope.launch { settings.setPlace(null) }
            _locate.value = LocateStatus.Idle
        }

        /** After the permission dialog: one approximate fix, saved as the place. */
        fun useDeviceLocation() {
            if (_locate.value == LocateStatus.Locating) return
            _locate.value = LocateStatus.Locating
            viewModelScope.launch {
                _locate.value =
                    when (val fix = location.approximateFix()) {
                        is LocationFix.Found -> {
                            settings.setPlace(Place(fix.latitude, fix.longitude))
                            LocateStatus.Found
                        }

                        LocationFix.NoPermission -> {
                            LocateStatus.Denied
                        }

                        LocationFix.Off -> {
                            LocateStatus.Off
                        }

                        LocationFix.Unavailable -> {
                            LocateStatus.Unavailable
                        }
                    }
            }
        }

        private fun describe(
            place: Place?,
            now: Long,
        ): DayNightUiState {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.ofInstant(Instant.ofEpochMilli(now), zone)
            val compiler = ConditionCompiler(place, zone, today, horizonDays = 2)
            return DayNightUiState(place, compiler.sunTimes(today), compiler.isDay(now), zone)
        }

        companion object {
            private const val MINUTE_MS = 60_000L
            private const val STOP_TIMEOUT_MS = 5_000L

            /** "41.39", "41,39", "-3.7" or "−3.7"; null when not a number. */
            fun parseDegrees(text: String): Double? =
                text
                    .trim()
                    .replace(',', '.')
                    .replace('−', '-')
                    .toDoubleOrNull()
                    ?.takeIf { it.isFinite() }
        }
    }
