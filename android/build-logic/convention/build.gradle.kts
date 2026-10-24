plugins {
    `kotlin-dsl`
}

group = "app.mauri.cucuto.buildlogic"

kotlin {
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.jdk.get())
    }
}

dependencies {
    // `implementation` (not compileOnly) so the convention plugins put one consistent set of plugin versions on the
    // build classpath. This is also how the project upgrades AGP's built-in Kotlin from its KGP floor to `kotlin`.
    implementation(libs.android.gradlePlugin)
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.compose.gradlePlugin)
    implementation(libs.serialization.gradlePlugin)
    implementation(libs.ksp.gradlePlugin)
    implementation(libs.hilt.gradlePlugin)
    implementation(libs.room3.gradlePlugin)
    implementation(libs.spotless.gradlePlugin)
    implementation(libs.roborazzi.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "cucuto.android.application"
            implementationClass = "app.mauri.cucuto.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "cucuto.android.library"
            implementationClass = "app.mauri.cucuto.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "cucuto.android.compose"
            implementationClass = "app.mauri.cucuto.buildlogic.AndroidComposeConventionPlugin"
        }
        register("androidHilt") {
            id = "cucuto.android.hilt"
            implementationClass = "app.mauri.cucuto.buildlogic.AndroidHiltConventionPlugin"
        }
        register("androidScreenshots") {
            id = "cucuto.android.screenshots"
            implementationClass = "app.mauri.cucuto.buildlogic.AndroidScreenshotsConventionPlugin"
        }
        register("androidRoom") {
            id = "cucuto.android.room"
            implementationClass = "app.mauri.cucuto.buildlogic.AndroidRoomConventionPlugin"
        }
        register("kotlinLibrary") {
            id = "cucuto.kotlin.library"
            implementationClass = "app.mauri.cucuto.buildlogic.KotlinLibraryConventionPlugin"
        }
        register("kotlinSerialization") {
            id = "cucuto.kotlin.serialization"
            implementationClass = "app.mauri.cucuto.buildlogic.KotlinSerializationConventionPlugin"
        }
        register("spotless") {
            id = "cucuto.spotless"
            implementationClass = "app.mauri.cucuto.buildlogic.SpotlessConventionPlugin"
        }
    }
}
