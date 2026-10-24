package app.mauri.cucuto.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** The character pickers that fold their pack sections (ADR 0072); each remembers its own fold state. */
enum class PickerKind(
    internal val key: String,
) {
    /** The character picker: message voices. */
    Voice("voice"),

    /** The ringtone picker. */
    Ringtone("ringtone"),
}

/** The packs the user last unfolded ([expanded]) or folded ([folded]) in one picker. Packs in neither set: untouched. */
data class RememberedFolds(
    val expanded: Set<String> = emptySet(),
    val folded: Set<String> = emptySet(),
)

/** Remembers which pack sections the user folded in each picker (ADR 0072). */
interface PickerFoldSettings {
    fun observe(picker: PickerKind): Flow<RememberedFolds>

    suspend fun remember(
        picker: PickerKind,
        packId: String,
        expanded: Boolean,
    )
}

/**
 * [PickerFoldSettings] in the app's settings DataStore. A device preference: backups leave it out (they carry only the
 * settings keys they list explicitly).
 */
@Singleton
class DataStorePickerFoldSettings
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) : PickerFoldSettings {
        override fun observe(picker: PickerKind): Flow<RememberedFolds> =
            dataStore.data
                .map { RememberedFolds(it[expandedKey(picker)].orEmpty(), it[foldedKey(picker)].orEmpty()) }
                .distinctUntilChanged()

        override suspend fun remember(
            picker: PickerKind,
            packId: String,
            expanded: Boolean,
        ) {
            dataStore.edit { prefs ->
                val open = prefs[expandedKey(picker)].orEmpty()
                val closed = prefs[foldedKey(picker)].orEmpty()
                prefs[expandedKey(picker)] = if (expanded) open + packId else open - packId
                prefs[foldedKey(picker)] = if (expanded) closed - packId else closed + packId
            }
        }

        private fun expandedKey(picker: PickerKind) = stringSetPreferencesKey("picker_${picker.key}_expanded_packs")

        private fun foldedKey(picker: PickerKind) = stringSetPreferencesKey("picker_${picker.key}_folded_packs")
    }
