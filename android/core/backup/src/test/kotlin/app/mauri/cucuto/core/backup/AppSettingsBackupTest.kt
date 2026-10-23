package app.mauri.cucuto.core.backup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.mauri.cucuto.core.data.settings.RoutingSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppSettingsBackupTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `the global switch round-trips and wrong types are ignored`() =
        runTest(timeout = kotlin.time.Duration.parse("30s")) {
            val folder = tmp.newFolder()
            val store =
                PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                ) { File(folder, "settings.preferences_pb") }
            val routing = RoutingSettings(store)
            val backup = AppSettingsBackup(routing)
            assertEquals(JsonObject(mapOf("enabled" to JsonPrimitive(true))), backup.export())

            val result =
                backup.restore(
                    JsonObject(
                        mapOf(
                            "enabled" to JsonPrimitive(false),
                            "uiSounds" to JsonPrimitive(true),
                        ),
                    ),
                )
            assertEquals(listOf("enabled"), result.applied)
            assertEquals(listOf("uiSounds"), result.ignored)
            assertEquals(false, routing.enabled.first())

            val wrong = backup.restore(JsonObject(mapOf("enabled" to JsonPrimitive("yes"))))
            assertEquals(listOf("enabled"), wrong.ignored)
            assertEquals(false, routing.enabled.first())
        }
}
