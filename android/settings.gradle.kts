pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Lets the JDK toolchain (see `jdk` in the version catalog) be provisioned automatically when missing.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "cucuto"

// Pack format rules shared with tools/packer (docs/decisions/0005-shared-pack-spec-module.md).
includeBuild("../shared/pack-spec")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")
include(":xposed")
include(":core:pack")
include(":core:repo")
include(":core:data")
include(":core:routing")
include(":core:conditions")
include(":core:ringtone")
include(":core:backup")
include(":core:haptics")
include(":core:audio")
include(":core:jxl")
include(":core:drive")
include(":core:creator")
