package app.mauri.cucuto.core.pack

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleInfoTest {
    @Test
    fun gradlePathMatchesPackage() {
        assertEquals(":core:pack", ModuleInfo.GRADLE_PATH)
    }
}
