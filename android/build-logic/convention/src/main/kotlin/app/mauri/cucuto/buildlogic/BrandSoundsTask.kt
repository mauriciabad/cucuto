package app.mauri.cucuto.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Copies the brand UI sounds (`brand/sounds/<name>.ogg`, CC0) into a generated `res/raw` as `ui_<name>.ogg`. Android
 * resource names cannot contain `-`, so `toggle-on.ogg` becomes `R.raw.ui_toggle_on`. Like the tokens and the icon,
 * nothing is copied into the source tree (docs/decisions/0003-brand-assets-by-reference.md). Wired in
 * `app/build.gradle.kts` with `variant.sources.res.addGeneratedSourceDirectory`.
 */
abstract class BrandSoundsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val sounds: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun copy() {
        val raw = outputDirectory.get().dir("raw").asFile
        raw.deleteRecursively()
        raw.mkdirs()
        sounds.files.forEach { it.copyTo(File(raw, "ui_" + it.name.replace('-', '_')), overwrite = true) }
    }
}
