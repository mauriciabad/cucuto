package app.mauri.cucuto.core.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleInfoTest {
    @Test
    fun gradlePathMatchesPackage() {
        assertEquals(":core:backup", ModuleInfo.GRADLE_PATH)
    }
}
