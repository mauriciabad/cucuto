package app.mauri.cucuto.buildlogic

import androidx.baselineprofile.gradle.consumer.BaselineProfileConsumerExtension
import androidx.baselineprofile.gradle.producer.BaselineProfileProducerExtension
import com.android.build.api.dsl.TestExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * `cucuto.android.baselineprofile`: the module that generates the app's Baseline Profile (#226,
 * docs/baseline-profile.md). A `com.android.test` module with the official Baseline Profile Gradle plugin and
 * Macrobenchmark, pointed at `:app`; the app applies the plugin's consumer side ([configureBaselineProfileConsumer]).
 *
 * Generating needs a device: `./gradlew :app:generateBaselineProfile` with an Android 17 emulator prepared like a
 * user's app (onboarding done, packs, chats), picked with `ANDROID_SERIAL`. There is no Gradle managed device: it
 * would boot empty, so the journeys would only see onboarding. CI does not run the generator.
 */
class AndroidBaselineProfileConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.test")
            pluginManager.apply("androidx.baselineprofile")
            pluginManager.apply("cucuto.spotless")

            extensions.configure<TestExtension> {
                configureKotlinAndroid(this, withUnitTests = false)
                targetProjectPath = ":app"
                defaultConfig {
                    targetSdk {
                        version = release(libs.version("sdk-target").toInt())
                    }
                }
            }

            extensions.configure<BaselineProfileProducerExtension> {
                // A prepared Android 17 emulator over adb (docs/baseline-profile.md).
                useConnectedDevices = true
            }

            dependencies {
                add("implementation", libs.library("androidx-test-ext-junit"))
                add("implementation", libs.library("androidx-test-uiautomator"))
                add("implementation", libs.library("androidx-benchmark-macro-junit4"))
            }
        }
    }
}

/**
 * The app's side of the Baseline Profile: the plugin merges the generated profile from
 * `src/main/generated/baselineProfiles/` into release builds (and adds the `nonMinifiedRelease` and
 * `benchmarkRelease` build types the generator runs). Builds never generate one by themselves, so they need no
 * device; ProfileInstaller (a dependency of the app) compiles it on install.
 */
internal fun Project.configureBaselineProfileConsumer() {
    pluginManager.apply("androidx.baselineprofile")
    extensions.configure<BaselineProfileConsumerExtension> {
        automaticGenerationDuringBuild = false
        mergeIntoMain = true
    }
}
