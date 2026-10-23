package app.mauri.cucuto.core.repo

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleInfoTest {
    @Test
    fun gradlePathMatchesPackage() {
        assertEquals(":core:repo", ModuleInfo.GRADLE_PATH)
    }
}
