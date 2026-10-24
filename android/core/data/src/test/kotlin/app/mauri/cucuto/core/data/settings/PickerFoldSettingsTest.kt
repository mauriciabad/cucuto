package app.mauri.cucuto.core.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Each picker remembers, per pack, whether the user last folded or unfolded it (ADR 0072). */
class PickerFoldSettingsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun TestScope.settings() =
        DataStorePickerFoldSettings(
            PreferenceDataStoreFactory.create(scope = backgroundScope) {
                folder.newFile("settings.preferences_pb").also { it.delete() }
            },
        )

    @Test
    fun nothingIsRememberedAtFirst() =
        runTest {
            assertEquals(RememberedFolds(), settings().observe(PickerKind.Voice).first())
        }

    @Test
    fun theLastChoicePerPackWinsAndEachPickerKeepsItsOwn() =
        runTest {
            val settings = settings()
            settings.remember(PickerKind.Voice, "creatures", expanded = false)
            settings.remember(PickerKind.Voice, "critters", expanded = true)
            settings.remember(PickerKind.Voice, "creatures", expanded = true)
            settings.remember(PickerKind.Ringtone, "songs", expanded = false)

            assertEquals(
                RememberedFolds(expanded = setOf("critters", "creatures")),
                settings.observe(PickerKind.Voice).first(),
            )
            assertEquals(RememberedFolds(folded = setOf("songs")), settings.observe(PickerKind.Ringtone).first())
        }
}
