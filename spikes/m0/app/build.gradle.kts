plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "app.mauri.cucuto.spike"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.mauri.cucuto.spike"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1-m0"
    }

    buildTypes {
        // The spike is debug-only on purpose: it carries a debug allowlist that lets the
        // system_server hook act on our own package. Never ship a release build of this.
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
