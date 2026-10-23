package app.mauri.cucuto.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * The app's version taken from a release tag (`v1.2.3` gives `1.2.3`), see docs/decisions/0021-signed-release-apk.md.
 *
 * versionCode is `MAJOR * 1_000_000 + MINOR * 1_000 + PATCH`, so every later version has a larger code (Android only
 * installs updates with a code at least as large) and the largest one, 999.999.999, stays under Android's
 * 2_100_000_000 limit.
 */
internal data class AppVersion(
    val code: Int,
    val name: String,
) {
    companion object {
        private val SEMVER = Regex("""(0|[1-9]\d{0,2})\.(0|[1-9]\d{0,2})\.(0|[1-9]\d{0,2})""")

        fun parse(version: String): AppVersion {
            val match =
                SEMVER.matchEntire(version)
                    ?: throw GradleException(
                        "cucuto.version must be MAJOR.MINOR.PATCH with each part 0..999 and no leading zeros " +
                            "(from a tag like v1.2.3), got '$version'",
                    )
            val (major, minor, patch) = match.destructured.toList().map(String::toInt)
            return AppVersion(code = major * 1_000_000 + minor * 1_000 + patch, name = version)
        }
    }
}

/** Environment variables that sign release builds (set by .github/workflows/release.yml, or by hand). */
internal object ReleaseSigningEnv {
    const val KEYSTORE = "CUCUTO_RELEASE_KEYSTORE"
    const val KEYSTORE_PASSWORD = "CUCUTO_RELEASE_KEYSTORE_PASSWORD"
    const val KEY_ALIAS = "CUCUTO_RELEASE_KEY_ALIAS"
    const val KEY_PASSWORD = "CUCUTO_RELEASE_KEY_PASSWORD"
}

/**
 * Release versioning and signing, both opt-in so debug builds and CI stay as they are:
 * - `-Pcucuto.version=1.2.3` overrides versionCode and versionName of every variant (AGP variant API, so the values
 *   in the app's `defaultConfig` stay the defaults for local builds);
 * - `CUCUTO_RELEASE_KEYSTORE` (a keystore file) and its three companions sign the release build type. Without it,
 *   release builds stay unsigned (`app-release-unsigned.apk`), as before.
 *
 * The secrets come from the environment, never from a file in the repository (Android's "Remove signing
 * information from your build files" guidance; GitHub Actions passes secrets as environment variables).
 */
internal fun Project.configureReleaseBuild() {
    val version = providers.gradleProperty("cucuto.version").orNull?.let(AppVersion::parse)
    if (version != null) {
        extensions.configure<ApplicationAndroidComponentsExtension> {
            onVariants { variant ->
                variant.outputs.forEach { output ->
                    output.versionCode.set(version.code)
                    output.versionName.set(version.name)
                }
            }
        }
    }

    val env = { name: String -> providers.environmentVariable(name).orNull?.takeIf(String::isNotEmpty) }
    val keystore = env(ReleaseSigningEnv.KEYSTORE) ?: return
    val values =
        listOf(ReleaseSigningEnv.KEYSTORE_PASSWORD, ReleaseSigningEnv.KEY_ALIAS, ReleaseSigningEnv.KEY_PASSWORD)
            .associateWith(env)
    val missing = values.filterValues { it == null }.keys
    if (missing.isNotEmpty()) {
        throw GradleException(
            "${ReleaseSigningEnv.KEYSTORE} is set but these are missing: ${missing.joinToString()} (docs/releasing.md)",
        )
    }
    val keystoreFile = file(keystore)
    if (!keystoreFile.isFile) throw GradleException("${ReleaseSigningEnv.KEYSTORE} points to a missing file: $keystore")

    extensions.configure<ApplicationExtension> {
        val release =
            signingConfigs.create("release") {
                storeFile = keystoreFile
                storePassword = values.getValue(ReleaseSigningEnv.KEYSTORE_PASSWORD)
                keyAlias = values.getValue(ReleaseSigningEnv.KEY_ALIAS)
                keyPassword = values.getValue(ReleaseSigningEnv.KEY_PASSWORD)
            }
        buildTypes.getByName("release").signingConfig = release
    }
}
