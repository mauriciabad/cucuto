package app.mauri.cucuto.buildlogic

import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * `cucuto.spotless`: ktlint (official Kotlin style, configured in `android/.editorconfig`) plus the Compose rules,
 * wired into `check`. On the root project it also formats the build scripts and the build-logic sources.
 * Run `./gradlew spotlessApply` to fix formatting.
 */
class SpotlessConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.diffplug.spotless")
            val ktlintVersion = libs.version("ktlint")
            val composeRules = libs.library("compose-rules-ktlint").get().let { "${it.module}:${it.version}" }
            val isRoot = this == rootProject

            extensions.configure<SpotlessExtension> {
                kotlin {
                    if (isRoot) {
                        target("build-logic/**/*.kt")
                    } else {
                        target("src/**/*.kt")
                    }
                    targetExclude("**/build/**")
                    ktlint(ktlintVersion).customRuleSets(listOf(composeRules))
                }
                kotlinGradle {
                    if (isRoot) {
                        target("*.gradle.kts", "build-logic/**/*.gradle.kts")
                    } else {
                        target("*.gradle.kts")
                    }
                    targetExclude("**/build/**")
                    ktlint(ktlintVersion)
                }
            }
        }
    }
}
