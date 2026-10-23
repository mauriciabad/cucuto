package app.mauri.cucuto.navigation

import app.mauri.cucuto.core.data.chats.ChatKey
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
                CucutoDestination.Picker(ChatKey("com.whatsapp", "100000000000001@lid"))
        all.forEach { destination ->
            val json = Json.encodeToString(CucutoDestination.serializer(), destination)
            assertEquals(destination, Json.decodeFromString(CucutoDestination.serializer(), json))
        }
    }
}
