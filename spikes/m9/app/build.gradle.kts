plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "app.mauri.cucuto.spike.m9"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.mauri.cucuto.spike.m9"
        minSdk = 36
        targetSdk = 37
        versionCode = 1
        versionName = "0.1-m9"
    }

    buildTypes {
        // Debug-only on purpose: the spike can swap the ringtone of every incoming phone call while
        // its debug property is set. Never ship a release build of this.
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
    // Must stay compileOnly: the framework provides the implementation inside system_server.
    compileOnly(libs.libxposed.api)
}
