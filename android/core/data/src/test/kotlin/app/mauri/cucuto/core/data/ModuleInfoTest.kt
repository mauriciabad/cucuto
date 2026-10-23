package app.mauri.cucuto.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleInfoTest {
    @Test
    fun gradlePathMatchesPackage() {
        assertEquals(":core:data", ModuleInfo.GRADLE_PATH)
    }
}
