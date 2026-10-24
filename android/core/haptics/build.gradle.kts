// `:core:haptics`: pack haptics to `VibrationEffect` (with capability checks and fallbacks) and auto haptics
// ("vibration follows the voice"). The effect builder takes no Context and no DI, so `:xposed` can use it inside
// system_server; the haptic model and its compact text form come from `:core:routing`.
plugins {
    alias(libs.plugins.cucuto.android.library)
}

// Instrumented tests decode real clips: the media of spec/fixtures and the clips of the CC0 demo packs (#315).
val repoDir: File = layout.projectDirectory.dir("../../..").asFile
val demoPackClips =
    tasks.register<Sync>("demoPackClips") {
        description = "Copies the demo packs' clips to assets/packs/ of the instrumented tests."
        from(repoDir.resolve("packs")) {
            include("*/**/*.ogg")
            into("packs")
        }
        into(layout.buildDirectory.dir("generated/demoPackClips"))
    }

android {
    sourceSets {
        named("androidTest") {
            assets.directories += repoDir.resolve("spec/fixtures").path
            assets.directories +=
                layout.buildDirectory
                    .dir("generated/demoPackClips")
                    .get()
                    .asFile.path
        }
    }
}

tasks.matching { it.name.endsWith("AndroidTestAssets") }.configureEach { dependsOn(demoPackClips) }

dependencies {
    // Haptic, HapticEvent and the routing table's haptic syntax are part of this module's API.
    api(projects.core.routing)
    // ClipPcmDecoder decodes with the importer's decoding loop: batches, one decoder per worker (ADR 0077).
    implementation(projects.core.codec)
    implementation(libs.kotlinx.coroutines.core)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
