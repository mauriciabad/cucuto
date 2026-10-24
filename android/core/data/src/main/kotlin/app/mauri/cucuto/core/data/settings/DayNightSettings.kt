package app.mauri.cucuto.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.mauri.cucuto.core.conditions.Place
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

/**
 * Where day and night clips compute sunrise and sunset for (ADR 0037): a place the user picked, typed, or took from
 * one coarse location fix they asked for. Null until they choose one; day is then 07:00 to 19:00.
 *
 * Only the coordinates are kept, rounded to two decimals (about 1 km), which is all sunrise and sunset need. They
 * are not part of backups: a place is personal and belongs to the phone it was set on.
 */
@Singleton
class DayNightSettings
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) {
        val place: Flow<Place?> =
            dataStore.data
                .map { prefs ->
                    val latitude = prefs[LATITUDE] ?: return@map null
                    val longitude = prefs[LONGITUDE] ?: return@map null
                    runCatching { Place(latitude, longitude, prefs[LABEL]) }.getOrNull()
                }.distinctUntilChanged()

        /** Stores [place] (coordinates rounded to two decimals), or forgets the place when null. */
        suspend fun setPlace(place: Place?) {
            dataStore.edit { prefs ->
                if (place == null) {
                    prefs.remove(LATITUDE)
                    prefs.remove(LONGITUDE)
                    prefs.remove(LABEL)
                } else {
                    prefs[LATITUDE] = round(place.latitude)
                    prefs[LONGITUDE] = round(place.longitude)
                    val label = place.label
                    if (label.isNullOrBlank()) prefs.remove(LABEL) else prefs[LABEL] = label
                }
            }
        }

        companion object {
            private val LATITUDE = doublePreferencesKey("daynight_latitude")
            private val LONGITUDE = doublePreferencesKey("daynight_longitude")
            private val LABEL = stringPreferencesKey("daynight_label")

            /** Two decimals: about 1 km, far finer than sunrise needs (a degree of longitude is 4 minutes). */
            fun round(degrees: Double): Double = (degrees * 100).roundToLong() / 100.0
        }
    }
