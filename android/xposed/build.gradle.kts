// `:xposed`: the libxposed API 102 entry point and hooks that run inside system_server.
// Keep it tiny and defensive: no DI, no Compose, no heavy dependencies. Packaged inside the app APK.
plugins {
    alias(libs.plugins.cucuto.android.library)
}

dependencies {
    // The routing table format shared with the app: plain Kotlin, no dependencies.
    implementation(projects.core.routing)
    // Haptic to VibrationEffect with capability checks and fallbacks (ADR 0012): no Context, no DI, never throws.
    implementation(projects.core.haptics)

    // Provided by LSPosed at runtime; never bundled.
    compileOnly(libs.libxposed.api)
    testImplementation(libs.libxposed.api)
}
