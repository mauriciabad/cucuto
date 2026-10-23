package app.mauri.cucuto.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * `cucuto.kotlin.library`: a plain Kotlin/JVM library with no Android dependency, for code shared by the app and
 * the `:xposed` module (for example the routing table format in `:core:routing`). Unit tests run on the host JVM.
 */
class KotlinLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            pluginManager.apply("cucuto.spotless")

            extensions.configure<KotlinJvmProjectExtension> {
                jvmToolchain(libs.version("jdk").toInt())
                explicitApi()
            }

            dependencies {
                add("testImplementation", libs.library("junit4"))
            }
        }
    }
}
