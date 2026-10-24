package app.mauri.cucuto.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import io.github.takahirom.roborazzi.RoborazziExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * `cucuto.android.screenshots`: screenshot tests on the JVM with Roborazzi on Robolectric Native Graphics, no device
 * needed (ADR 0032). Apply after the Android application plugin.
 *
 * The golden images live in `src/testDebug/screenshots/`, next to the tests in `src/testDebug`. The tasks come from
 * Roborazzi: `recordRoborazziDebug` writes them, `verifyRoborazziDebug` fails on any difference (CI passes
 * `-Proborazzi.test.verify=true` to `check`), `compareRoborazziDebug` writes a diff image without failing.
 */
class AndroidScreenshotsConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("io.github.takahirom.roborazzi")

            extensions.configure<RoborazziExtension> {
                outputDir.set(layout.projectDirectory.dir(GOLDEN_DIR))
            }

            extensions.configure<ApplicationExtension> {
                testOptions {
                    unitTests.isIncludeAndroidResources = true
                    unitTests.all { test ->
                        // Roborazzi's recommendation: hardware rendering draws shadows, elevation and clipping like a
                        // device does (https://github.com/takahirom/roborazzi#robolectricpixelcopyrendermode).
                        test.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                        test.maxHeapSize = "4g"
                    }
                }
            }

            dependencies {
                add("testImplementation", libs.library("roborazzi"))
                add("testImplementation", libs.library("roborazzi-compose"))
            }
        }
    }

    private companion object {
        const val GOLDEN_DIR = "src/testDebug/screenshots"
    }
}
