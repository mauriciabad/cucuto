// `:xposed`: the libxposed API 102 entry point and hooks that run inside system_server.
// Keep it tiny and defensive: no DI, no Compose, no heavy dependencies. Packaged inside the app APK.
plugins {
    alias(libs.plugins.cucuto.android.library)
}

// The CC0 demo pack sources: the integration suite installs their clips (ADR 0047).
val packsDir: File = layout.projectDirectory.dir("../../packs").asFile

android {
    testOptions {
        unitTests.all {
            // Robolectric's Android 16+ runtime touches FileDescriptor internals that JDK 21 hides by default.
            it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }
}

dependencies {
    // The routing table format shared with the app: plain Kotlin, no dependencies.
    implementation(projects.core.routing)
    // Haptic to VibrationEffect with capability checks and fallbacks (ADR 0012): no Context, no DI, never throws.
    implementation(projects.core.haptics)

    // Provided by LSPosed at runtime; never bundled.
    compileOnly(libs.libxposed.api)
    testImplementation(libs.libxposed.api)

    // Integration suite (ADR 0047): the app's real data layer and publisher feed this module's real hooks, with
    // Robolectric for the framework classes. Test classpath only; nothing here ships in system_server.
    testImplementation(projects.core.data)
    testImplementation(libs.androidx.room3.runtime)
    testImplementation(libs.androidx.datastore.preferences)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}

tasks.withType<Test>().configureEach {
    systemProperty("cucuto.packsDir", packsDir.absolutePath)
    inputs.dir(packsDir).withPropertyName("packsDir").withPathSensitivity(PathSensitivity.RELATIVE)
    // `-Pcucuto.recordGolden=<file>` records a new golden routing table (RoutingIntegrationTest.recordGolden).
    providers.gradleProperty("cucuto.recordGolden").orNull?.let { systemProperty("cucuto.recordGolden", it) }
}
