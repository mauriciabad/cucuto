plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "app.mauri.cucuto.spike.waring"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.mauri.cucuto.spike.waring"
        minSdk = 36
        targetSdk = 37
        versionCode = 1
        versionName = "0.1-waring"
    }

    buildTypes {
        // Debug-only on purpose: with swap mode on, the spike changes the ringtone of WhatsApp calls from the
        // configured test contact. Never ship a release build of this.
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    // Must stay compileOnly: the framework provides the implementation inside WhatsApp's process.
    compileOnly(libs.libxposed.api)
    // The app side: writes the spike's settings to Remote Preferences (adb-driven, see ConfigReceiver).
    implementation(libs.libxposed.service)

    testImplementation(libs.junit4)
}
