package app.mauri.cucuto.core.haptics

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleInfoTest {
    @Test
    fun gradlePathMatchesPackage() {
        assertEquals(":core:haptics", ModuleInfo.GRADLE_PATH)
    }
}
