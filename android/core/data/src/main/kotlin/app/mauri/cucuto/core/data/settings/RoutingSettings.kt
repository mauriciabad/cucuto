package app.mauri.cucuto.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Settings that end up in the routing table. Stored in the app's settings DataStore. */
@Singleton
class RoutingSettings
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) {
        /** The global switch: when false the module leaves every notification untouched. Default on. */
        val enabled: Flow<Boolean> = dataStore.data.map { it[ENABLED] ?: true }.distinctUntilChanged()

        suspend fun setEnabled(enabled: Boolean) {
            dataStore.edit { it[ENABLED] = enabled }
        }

        private companion object {
            val ENABLED = booleanPreferencesKey("routing_enabled")
        }
    }
