// `:core:repo`: pack repository sources (HTTPS index first), credentials, download and verification (ADR 0011).
plugins {
    alias(libs.plugins.cucuto.android.library)
    alias(libs.plugins.cucuto.kotlin.serialization)
}

// spec/ is the cross-platform contract: tests serve its golden fixtures from a local HTTPS server.
val specDir: File = layout.projectDirectory.dir("../../../spec").asFile

dependencies {
    // Downloads end in the pack importer; its events and results are part of this module's API.
    api(projects.core.pack)
    api(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.okhttp.tls)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

tasks.withType<Test>().configureEach {
    systemProperty("cucuto.specDir", specDir.absolutePath)
    inputs.dir(specDir).withPropertyName("specDir").withPathSensitivity(PathSensitivity.RELATIVE)
}
