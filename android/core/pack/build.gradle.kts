plugins {
    alias(libs.plugins.cucuto.android.library)
    alias(libs.plugins.cucuto.kotlin.serialization)
}

// spec/ is the cross-platform contract: unit tests run every golden fixture, instrumented tests decode its media.
val specDir: File = layout.projectDirectory.dir("../../../spec").asFile

android {
    sourceSets {
        named("androidTest") {
            assets.directories += specDir.resolve("fixtures").path
        }
    }
}

dependencies {
    // The pack format rules shared with packer: the included build in shared/pack-spec (ADR 0005).
    api("app.mauri.cucuto:pack-spec")
    // JPEG XL test-decoding at import (JxlImageProbe), with the same decoder the app displays sprites with.
    implementation(projects.core.jxl)
    // Audio test-decoding at import (PlatformAudioProbe), with the decoding loop auto haptics uses too (ADR 0077).
    implementation(projects.core.codec)
    implementation(libs.kotlinx.coroutines.core)

    // The benchmarks also time the install step, auto haptics (#315, ADR 0077).
    testImplementation(projects.core.haptics)
    androidTestImplementation(projects.core.haptics)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    systemProperty("cucuto.specDir", specDir.absolutePath)
    inputs.dir(specDir).withPropertyName("specDir").withPathSensitivity(PathSensitivity.RELATIVE)
}
