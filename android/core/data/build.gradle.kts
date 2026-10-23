// `:core:data`: Room database, repositories, settings and the publisher of the routing table the module reads.
plugins {
    alias(libs.plugins.cucuto.android.library)
    alias(libs.plugins.cucuto.android.room)
    alias(libs.plugins.cucuto.android.hilt)
    alias(libs.plugins.cucuto.kotlin.serialization)
}

android {
    testOptions {
        // Robolectric reads the exported Room schemas (test assets) for the migration tests.
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric's Android 16+ runtime touches FileDescriptor internals that JDK 21 hides by default.
            it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }
    sourceSets {
        named("test") {
            assets.directories += "$projectDir/schemas"
        }
    }
}

dependencies {
    // The routing table model and codec are part of this module's API (publisher state, builder output).
    api(projects.core.routing)
    // Reads the auto haptics stored next to installed packs (AutoHapticFile) into the routing table.
    implementation(projects.core.haptics)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    // App side of libxposed: receives the framework's service binder and writes Remote Preferences.
    implementation(libs.libxposed.service)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room3.testing)
}
