// Generates the app's Baseline Profile on a device: `./gradlew :app:generateBaselineProfile` (docs/baseline-profile.md).
plugins {
    alias(libs.plugins.cucuto.android.baselineprofile)
}

android {
    namespace = "app.mauri.cucuto.baselineprofile"
}
