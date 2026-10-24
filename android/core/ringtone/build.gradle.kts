// `:core:ringtone`: phone-call ringtones (#52, ADR 0039). Ring files rendered on the phone with MediaCodec and
// exported to `Ringtones/cucuto/` through MediaStore, Android contacts' `CUSTOM_RINGTONE`, variant rules, the re-roll
// after each call and boundary alarms. No hook: Telecom plays the ringtone itself (M9 report, ADR 0023).
plugins {
    alias(libs.plugins.cucuto.android.library)
    alias(libs.plugins.cucuto.android.hilt)
}

// spec/ is the cross-platform contract: the renderer's tests run the golden ring file plans.
val specDir: File = layout.projectDirectory.dir("../../../spec").asFile

android {
    testOptions {
        unitTests.all {
            // Robolectric's Android 16+ runtime touches FileDescriptor internals that JDK 21 hides by default.
            it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }
}

dependencies {
    // Contacts (Android links), phone ringtones and the undo record, installed packs, the settings DataStore.
    implementation(projects.core.data)
    // Sunrise and sunset for "day" and "night" (ADR 0037), shared with day and night clips.
    implementation(projects.core.conditions)
    // RingFiles (the ring file rule shared with packer) and the pack manifest model.
    implementation("app.mauri.cucuto:pack-spec")
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    systemProperty("cucuto.specDir", specDir.absolutePath)
    inputs.dir(specDir).withPropertyName("specDir").withPathSensitivity(PathSensitivity.RELATIVE)
}
