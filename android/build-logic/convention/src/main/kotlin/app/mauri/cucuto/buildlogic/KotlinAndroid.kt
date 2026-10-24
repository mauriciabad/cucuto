package app.mauri.cucuto.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * Configuration shared by every Android module (app and libraries): Android 17 only (min = target = 37), compiled
 * against the newest 37.x platform, Java/Kotlin on the project JDK toolchain, and the common test stack.
 */
internal fun Project.configureKotlinAndroid(extension: CommonExtension) {
    val jdk = libs.version("jdk").toInt()

    extension.apply {
        compileSdk {
            version =
                release(libs.version("sdk-compile").toInt()) {
                    minorApiLevel = libs.version("sdk-compile-minor").toInt()
                }
        }
        buildToolsVersion = libs.version("build-tools")

        defaultConfig.apply {
            minSdk {
                version = release(libs.version("sdk-min").toInt())
            }
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        compileOptions.apply {
            sourceCompatibility = JavaVersion.toVersion(jdk)
            targetCompatibility = JavaVersion.toVersion(jdk)
        }

        lint.apply {
            abortOnError = true
            warningsAsErrors = false
            checkReleaseBuilds = true
            // Every user-visible string ships in English and Spanish (#107, ADR 0036): a string or plural missing
            // from values-es/ (or left over there), a missing Spanish plural quantity or a placeholder that differs
            // between the two fails Lint.
            error +=
                setOf(
                    "MissingTranslation",
                    "ExtraTranslation",
                    "MissingQuantity",
                    "StringFormatCount",
                    "StringFormatMatches",
                    "StringFormatInvalid",
                )
        }

        testOptions.apply {
            unitTests.isReturnDefaultValues = true
        }

        packaging.apply {
            resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*", "/META-INF/*.kotlin_module")
        }
    }

    extensions.configure<KotlinAndroidProjectExtension> {
        jvmToolchain(jdk)
        compilerOptions {
            allWarningsAsErrors.set(false)
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }

    dependencies {
        add("testImplementation", libs.library("junit4"))
        add("testImplementation", libs.library("turbine"))
        add("testImplementation", libs.library("kotlinx-coroutines-test"))
    }
}
