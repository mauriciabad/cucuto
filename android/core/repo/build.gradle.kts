plugins {
    alias(libs.plugins.cucuto.android.library)
    alias(libs.plugins.cucuto.kotlin.serialization)
}

dependencies {
    implementation(projects.core.pack)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
}
