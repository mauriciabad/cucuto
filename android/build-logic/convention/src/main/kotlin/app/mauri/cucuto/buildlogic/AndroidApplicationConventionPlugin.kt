package app.mauri.cucuto.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * `cucuto.android.application`: the single APK. Android 17 only, arm64-v8a only, R8 in release; release version and
 * signing from the tag and the environment ([configureReleaseBuild]).
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            pluginManager.apply("cucuto.spotless")

            extensions.configure<ApplicationExtension> {
                configureKotlinAndroid(this)
                defaultConfig {
                    targetSdk {
                        version = release(libs.version("sdk-target").toInt())
                    }
                    ndk {
                        // The owner's phone (and every Android 17 device we support) is arm64.
                        abiFilters += "arm64-v8a"
                    }
                }
                lint {
                    checkDependencies = true
                }
            }
            configureReleaseBuild()
        }
    }
}
