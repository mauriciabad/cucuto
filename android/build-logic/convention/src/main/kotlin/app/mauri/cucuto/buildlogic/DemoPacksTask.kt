package app.mauri.cucuto.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * Builds the CC0 demo packs (`packs/<name>/pack.yaml`) into `<name>.soundpack.zip` with the real packer
 * (`tools/packer`, its own Gradle build), for the app's end-to-end smoke tests (`app/src/androidTest`,
 * docs/maintaining.md). Built archives never enter the source tree (media guard); they only exist as test APK assets.
 * Runs only when the test APK is assembled.
 */
abstract class DemoPacksTask
    @Inject
    constructor(
        private val exec: ExecOperations,
    ) : DefaultTask() {
        /** The demo pack sources: every folder with a `pack.yaml`. */
        @get:InputFiles
        @get:IgnoreEmptyDirectories
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val packSources: ConfigurableFileCollection

        /** The packer and pack-spec sources, so a change to the pack format rebuilds the archives. */
        @get:InputFiles
        @get:IgnoreEmptyDirectories
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val packerSources: ConfigurableFileCollection

        /** `packs/`. */
        @get:Internal
        abstract val packsDirectory: DirectoryProperty

        /** `tools/packer/`, with its Gradle wrapper. */
        @get:Internal
        abstract val packerDirectory: DirectoryProperty

        @get:OutputDirectory
        abstract val outputDirectory: DirectoryProperty

        @TaskAction
        fun build() {
            val packer = packerDirectory.get().asFile
            val out = outputDirectory.get().asFile
            out.deleteRecursively()
            out.mkdirs()
            exec.exec {
                workingDir = packer
                commandLine(File(packer, "gradlew").absolutePath, "--quiet", "installDist")
            }
            val cli = File(packer, "cli/build/install/packer/bin/packer")
            val sources =
                packsDirectory
                    .get()
                    .asFile
                    .listFiles()
                    .orEmpty()
                    .filter { File(it, "pack.yaml").isFile }
                    .sortedBy { it.name }
            check(sources.isNotEmpty()) { "No demo packs in ${packsDirectory.get().asFile}" }
            sources.forEach { source ->
                exec.exec {
                    commandLine(
                        cli.absolutePath,
                        "build",
                        source.absolutePath,
                        "-o",
                        "$out/${source.name}.soundpack.zip",
                    )
                }
            }
        }
    }
