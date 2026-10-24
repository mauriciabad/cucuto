package app.mauri.cucuto.navigation

import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.packspec.ControlPoint
import app.mauri.cucuto.packspec.HapticEvent
import app.mauri.cucuto.ui.haptics.HapticTarget
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CucutoDestinationTest {
    @Test
    fun everyPreviewDestinationHasPlaceholderInfo() {
        PreviewDestinations.forEach { assertNotNull("$it has no screen info", it.screenInfo()) }
        assertNull(CucutoDestination.Status.screenInfo())
    }

    @Test
    fun statusPlusPreviewsCoverTheEightScreensOfTheBrief() {
        val screens = (PreviewDestinations + CucutoDestination.Status).map { it::class }.toSet()
        assertEquals(8, screens.size)
    }

    @Test
    fun destinationsSurviveSerialization() {
        val all =
            PreviewDestinations + CucutoDestination.Status +
                CucutoDestination.Picker(ChatKey("com.whatsapp", "100000000000001@lid")) +
                CucutoDestination.HapticEditor(HapticTarget.Chat(ChatKey("com.whatsapp", "100000000000001@lid"))) +
                CucutoDestination.HapticEditor(
                    HapticTarget.CreatorCharacter(
                        characterKey = "k1",
                        name = "Kettle",
                        pattern =
                            listOf(
                                HapticEvent(at = 0, preset = "CLICK", scale = 0.8),
                                HapticEvent(
                                    at = 100,
                                    envelope = listOf(ControlPoint(1.0, 0.5, 50), ControlPoint(0.0, 0.5, 50)),
                                ),
                            ),
                        clip = "/data/clip.ogg",
                    ),
                )
        all.forEach { destination ->
            val json = Json.encodeToString(CucutoDestination.serializer(), destination)
            assertEquals(destination, Json.decodeFromString(CucutoDestination.serializer(), json))
        }
    }
}
