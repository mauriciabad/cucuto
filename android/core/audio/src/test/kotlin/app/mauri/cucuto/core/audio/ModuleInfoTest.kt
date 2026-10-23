package app.mauri.cucuto.core.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleInfoTest {
    @Test
    fun gradlePathMatchesPackage() {
        assertEquals(":core:audio", ModuleInfo.GRADLE_PATH)
    }
}
