// `:core:creator`: the in-app pack creator's model and files (#47, ADR 0034). The draft a person is working on
// (saved as they go), sprite cropping, assembling and zipping a `*.soundpack.zip` with pack-spec's shared authoring
// rules, validating it with the same PackValidator as packer and the website, and copying an installed pack into a
// new draft ("duplicate and edit"). The audio pipeline lives in `:core:audio`; the screens in the app's `ui/creator/`.
plugins {
    alias(libs.plugins.cucuto.android.library)
    alias(libs.plugins.cucuto.kotlin.serialization)
}

val specDir: File = layout.projectDirectory.dir("../../../spec").asFile

dependencies {
    // PackStore (installed packs to duplicate) and, through its api, pack-spec (assembly, validation, probes).
    api(projects.core.pack)
    // JPEG XL pictures picked as sprites (Android 17 has no platform decoder, ADR 0006).
    implementation(projects.core.jxl)
    implementation(libs.kotlinx.coroutines.android)
}

tasks.withType<Test>().configureEach {
    // Real fixture media (spec/fixtures) for the end-to-end pack writing test.
    systemProperty("cucuto.specDir", specDir.absolutePath)
    inputs.dir(specDir).withPropertyName("specDir").withPathSensitivity(PathSensitivity.RELATIVE)
}
