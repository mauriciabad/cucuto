// `:core:haptics`: pack haptics to `VibrationEffect` (with capability checks and fallbacks) and auto haptics
// ("vibration follows the voice"). The effect builder takes no Context and no DI, so `:xposed` can use it inside
// system_server; the haptic model and its compact text form come from `:core:routing`.
plugins {
    alias(libs.plugins.cucuto.android.library)
}

dependencies {
    // Haptic, HapticEvent and the routing table's haptic syntax are part of this module's API.
    api(projects.core.routing)
    implementation(libs.kotlinx.coroutines.core)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
