// `:video`: the explainer video as source (#271, ADR 0070, docs/explainer-video.md). An illustrated, evergreen phone UI
// drawn for the video with Compose, from brand/ and the CC0 demo packs only. It never depends on the app's screens,
// view models or strings, so an app UI change can never change or break the video. How to add a scene: README.md.
//
// `./gradlew :video:renderExplainerFrames` records every frame on the JVM (Robolectric, no device); then
// tools/video/voice.main.kts speaks the script and tools/video/explainer.main.kts mixes and encodes the deliverables.
// `./gradlew :video:explainerVideo` runs all three.
plugins {
    alias(libs.plugins.cucuto.android.library)
    alias(libs.plugins.cucuto.android.compose)
}

val repositoryRoot: File = rootDir.parentFile

// Where the frames go, and whether this build renders them (the frame test skips itself otherwise).
val explainerOut: Provider<Directory> = layout.buildDirectory.dir("explainer")
val explainerFrames: Provider<Directory> = layout.buildDirectory.dir("explainer/frames")
val requestedTasks = gradle.startParameter.taskNames.map { it.substringAfterLast(':') }
val renderExplainer = requestedTasks.any { it in setOf("renderExplainerFrames", "explainerVideo") }
val writeCaptions = "writeExplainerCaptions" in requestedTasks

// `-Pexplainer.stills=6.5,32.8` renders only those frames (as still-<time>.png), to check a scene quickly.
val explainerStills: Provider<String> = providers.gradleProperty("explainer.stills").orElse("")

/**
 * Copies brand files into a generated source folder, as the app does with the brand sounds (ADR 0003: brand assets by
 * reference, nothing copied into the source tree). [replacements] rewrite text files on the way (the brand's Type.kt
 * imports the app's `R`; here it is this module's).
 */
abstract class BrandFilesTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val files: ConfigurableFileCollection

    @get:Input
    abstract val subfolder: Property<String>

    @get:Input
    abstract val replacements: MapProperty<String, String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDirectory.get().asFile
        out.deleteRecursively()
        val folder = File(out, subfolder.get()).apply { mkdirs() }
        files.files.forEach { file ->
            val target = File(folder, file.name)
            if (file.extension == "kt") {
                target.writeText(
                    replacements.get().entries.fold(file.readText()) { text, (from, to) -> text.replace(from, to) },
                )
            } else {
                file.copyTo(target, overwrite = true)
            }
        }
    }
}

// The brand's color scheme and type scale (brand/tokens), the same ones the app compiles.
val brandTokens =
    tasks.register<BrandFilesTask>("brandTokens") {
        files.from(repositoryRoot.resolve("brand/tokens/Color.kt"), repositoryRoot.resolve("brand/tokens/Type.kt"))
        subfolder = "app/mauri/cucuto/ui/theme"
        replacements = mapOf("import app.mauri.cucuto.R\n" to "import app.mauri.cucuto.video.R\n")
    }

// The brand typeface (Google Sans Flex, SIL OFL 1.1), from where the app keeps it.
val brandFont =
    tasks.register<BrandFilesTask>("brandFont") {
        files.from(rootDir.resolve("app/src/main/res/font/google_sans_flex.ttf"))
        subfolder = "font"
        replacements = emptyMap()
    }

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(brandTokens, BrandFilesTask::outputDirectory)
        variant.sources.res?.addGeneratedSourceDirectory(brandFont, BrandFilesTask::outputDirectory)
    }
}

android {
    testOptions {
        // Robolectric reads the brand font from the merged resources.
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric's Android 16+ runtime touches FileDescriptor internals that JDK 21 hides by default.
            it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
            // Draw like a device does (shadows, clipping), as the app's screenshot tests (ADR 0032).
            it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            it.maxHeapSize = "4g"
            // The scenes draw the demo packs' sprites and the brand's logo straight from the repository.
            it.systemProperty("cucuto.repository", repositoryRoot.absolutePath)
            it.inputs
                .dir(repositoryRoot.resolve("packs"))
                .withPropertyName("demoPacks")
                .withPathSensitivity(PathSensitivity.RELATIVE)
            it.inputs
                .dir(repositoryRoot.resolve("brand"))
                .withPropertyName("brand")
                .withPathSensitivity(PathSensitivity.RELATIVE)
            it.inputs
                .file(repositoryRoot.resolve("web/static/video/explainer.en.vtt"))
                .withPropertyName("captions")
                .withPathSensitivity(PathSensitivity.RELATIVE)
            if (renderExplainer) {
                it.systemProperty("cucuto.video.frames", explainerFrames.get().asFile.absolutePath)
                it.systemProperty("cucuto.video.stills", explainerStills.get())
                it.filter.includeTestsMatching("app.mauri.cucuto.video.RenderExplainerTest.frames")
                it.outputs.dir(explainerFrames)
                it.outputs.upToDateWhen { false }
            }
            if (writeCaptions) {
                it.systemProperty("cucuto.video.writeCaptions", "true")
                it.filter.includeTestsMatching("app.mauri.cucuto.video.TimelineTest.captionFilesMatchTheTimeline")
                it.outputs.upToDateWhen { false }
            }
        }
    }
}

kotlin {
    compilerOptions {
        // The brand's avatar shapes (MaterialShapes) are Material 3 Expressive, as in the app.
        optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
    }
}

dependencies {
    // The demo packs' sprites and the brand's logo are SVG, decoded synchronously (Coil's executeBlocking), so every
    // frame is complete.
    implementation(libs.coil.svg)
    implementation(libs.compose.material.icons.core)

    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.test.espresso.core)
}

tasks.register("renderExplainerFrames") {
    group = "explainer"
    description = "Renders every frame of the explainer video into build/explainer/frames/ (no device needed)."
    dependsOn("testDebugUnitTest")
}

tasks.register("writeExplainerCaptions") {
    group = "explainer"
    description = "Writes web/static/video/explainer.{en,es}.vtt from the timeline."
    dependsOn("testDebugUnitTest")
}

// Frames, then the AI voice-over (tools/video/voice.main.kts), then the mix and the encodes (explainer.main.kts).
val explainerVoice =
    tasks.register<Exec>("explainerVoice") {
        group = "explainer"
        description = "Speaks the voice-over with the local text-to-speech model into build/explainer/voice/."
        dependsOn("renderExplainerFrames")
        workingDir = repositoryRoot
        commandLine(
            "kotlin",
            "tools/video/voice.main.kts",
            "--script",
            explainerFrames
                .get()
                .file("voice.tsv")
                .asFile.absolutePath,
            "--out",
            explainerOut
                .get()
                .dir("voice")
                .asFile.absolutePath,
        )
    }

tasks.register<Exec>("explainerVideo") {
    group = "explainer"
    description = "Renders, voices and encodes the explainer video into build/explainer/ (needs ffmpeg and kotlin)."
    dependsOn(explainerVoice)
    workingDir = repositoryRoot
    commandLine(
        "kotlin",
        "tools/video/explainer.main.kts",
        "--frames",
        explainerFrames.get().asFile.absolutePath,
        "--voice-over",
        explainerOut
            .get()
            .file("voice/voice-over.wav")
            .asFile.absolutePath,
        "--out",
        explainerOut.get().asFile.absolutePath,
    )
}
