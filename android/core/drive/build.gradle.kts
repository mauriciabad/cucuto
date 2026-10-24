// `:core:drive`: Google Drive REST v3 for automatic backups and Drive pack folders (ADR 0040). Signing in is an
// interface (`DriveAuthorizer`); its Google Play services implementation waits for the owner's OAuth client (#163).
plugins {
    alias(libs.plugins.cucuto.android.library)
    alias(libs.plugins.cucuto.kotlin.serialization)
}

dependencies {
    api(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.kotlinx.coroutines.test)
}
