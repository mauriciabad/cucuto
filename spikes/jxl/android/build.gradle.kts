buildscript {
    dependencies {
        // AGP 9.4 bundles KGP 2.2; Coil 3.6 is built with Kotlin 2.4, so raise KGP as the AGP 9
        // release notes describe ("Upgrade to a higher KGP version").
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
}
