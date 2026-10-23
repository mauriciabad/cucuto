package app.mauri.cucuto.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** `cucuto.kotlin.serialization`: kotlinx.serialization compiler plugin plus the JSON runtime. */
class KotlinSerializationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
            dependencies {
                add("implementation", libs.library("kotlinx-serialization-json"))
            }
        }
    }
}
