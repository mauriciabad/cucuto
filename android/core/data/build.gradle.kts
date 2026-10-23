plugins {
    alias(libs.plugins.cucuto.android.library)
    alias(libs.plugins.cucuto.android.room)
    alias(libs.plugins.cucuto.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.datastore.preferences)
}
