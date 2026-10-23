package app.mauri.cucuto.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * `cucuto.android.library`: an Android library module. The namespace is derived from the Gradle path, so
 * `:core:pack` becomes `app.mauri.cucuto.core.pack`.
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            pluginManager.apply("cucuto.spotless")

            extensions.configure<LibraryExtension> {
                configureKotlinAndroid(this)
                namespace = "app.mauri.cucuto" + path.replace(':', '.').replace('-', '_')
                if (file("consumer-rules.pro").exists()) {
                    defaultConfig {
                        consumerProguardFiles("consumer-rules.pro")
                    }
                }
            }
        }
    }
}
