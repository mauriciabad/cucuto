package app.mauri.cucuto.core.pack

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** spec/fixtures, packaged as the test APK's assets (see build.gradle.kts). */
object DeviceFixtures {
    private val assets get() = InstrumentationRegistry.getInstrumentation().context.assets

    val targetContext: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** A fresh folder in the app's cache for one test. */
    fun newDir(name: String): File = File(targetContext.cacheDir, "test-$name-${System.nanoTime()}").apply { mkdirs() }

    /** Copies one fixture file to [dir] (decoders need a real file). */
    fun file(
        path: String,
        dir: File,
    ): File =
        File(dir, path.substringAfterLast('/')).also { out ->
            assets.open(path).use { input -> out.outputStream().use { input.copyTo(it) } }
        }

    /** Zips a fixture folder to [dir], or copies a fixture archive. */
    fun archive(
        path: String,
        dir: File,
    ): File {
        val children = assets.list(path).orEmpty()
        if (children.isEmpty()) return file(path, dir)
        val out = File(dir, path.replace('/', '_') + ".zip")
        ZipOutputStream(out.outputStream()).use { zip -> addFolder(zip, path, "") }
        return out
    }

    private fun addFolder(
        zip: ZipOutputStream,
        assetPath: String,
        prefix: String,
    ) {
        for (name in assets.list(assetPath).orEmpty().sorted()) {
            val child = "$assetPath/$name"
            if (assets.list(child).orEmpty().isNotEmpty()) {
                addFolder(zip, child, "$prefix$name/")
            } else {
                zip.putNextEntry(ZipEntry(prefix + name))
                assets.open(child).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}
