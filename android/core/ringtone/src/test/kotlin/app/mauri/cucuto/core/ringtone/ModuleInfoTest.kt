package app.mauri.cucuto.core.ringtone

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleInfoTest {
    @Test
    fun gradlePathMatchesPackage() {
        assertEquals(":core:ringtone", ModuleInfo.GRADLE_PATH)
    }
}
