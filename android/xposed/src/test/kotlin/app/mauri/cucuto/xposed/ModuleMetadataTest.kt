package app.mauri.cucuto.xposed

import io.github.libxposed.api.XposedModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Properties

/** Guards the `META-INF/xposed` metadata that LSPosed reads from the APK. */
class ModuleMetadataTest {
    private fun resourceLines(name: String): List<String> {
        val stream =
            checkNotNull(javaClass.classLoader?.getResourceAsStream("META-INF/xposed/$name")) {
                "META-INF/xposed/$name is missing"
            }
        return stream.bufferedReader().use { reader -> reader.readLines().map(String::trim).filter(String::isNotEmpty) }
    }

    @Test
    fun entryClassExtendsXposedModule() {
        val entries = resourceLines("java_init.list")
        assertEquals(listOf(CucutoModule::class.java.name), entries)
        assertTrue(XposedModule::class.java.isAssignableFrom(Class.forName(entries.single())))
    }

    @Test
    fun targetsApi102() {
        val props = Properties().apply { load(resourceLines("module.prop").joinToString("\n").reader()) }
        assertEquals("102", props.getProperty("minApiVersion"))
        assertEquals("102", props.getProperty("targetApiVersion"))
        assertEquals("true", props.getProperty("staticScope"))
    }

    @Test
    fun scopedToSystemServerOnly() {
        // LSPosed names system_server "system" for modern modules; WhatsApp must never be in scope.
        assertEquals(listOf("system"), resourceLines("scope.list"))
    }
}
