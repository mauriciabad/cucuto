package app.mauri.cucuto.diagnostics

import app.mauri.cucuto.core.data.routing.PublishState
import app.mauri.cucuto.xposed.events.ModuleStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleTableSyncTest {
    private val boot = 1_800_000_000_000L

    private fun status(
        generation: Long,
        bootTime: Long = boot,
    ) = ModuleStatus(bootTime, listOf("playSound"), emptyList(), generation, 3, 0, 0, 0, 0)

    private fun published(generation: Long) = PublishState.Published(generation, 3, 1, 100)

    @Test
    fun comparesGenerationsWithinTheSameBoot() {
        assertEquals(ModuleTableSync.SAME, moduleTableSync(status(7), published(7), boot))
        assertEquals(ModuleTableSync.BEHIND, moduleTableSync(status(6), published(7), boot))
        // Boot times computed on both sides differ by milliseconds.
        assertEquals(ModuleTableSync.SAME, moduleTableSync(status(7, bootTime = boot + 40), published(7), boot))
    }

    @Test
    fun aReportFromAnotherBootSaysNothing() {
        assertEquals(
            ModuleTableSync.PREVIOUS_BOOT,
            moduleTableSync(status(7, bootTime = boot - 3_600_000), published(7), boot),
        )
    }

    @Test
    fun nothingToCompareBeforeThisProcessPublished() {
        assertEquals(ModuleTableSync.UNKNOWN, moduleTableSync(status(7), PublishState.Idle, boot))
        assertEquals(ModuleTableSync.UNKNOWN, moduleTableSync(status(7), PublishState.Failed("x"), boot))
    }
}
