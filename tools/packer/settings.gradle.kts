rootProject.name = "packer"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

// pack-spec: the validation core shared with the Android importer, a standalone build in shared/pack-spec
// (docs/decisions/0005-shared-pack-spec-module.md). Its tests run as part of `check` here (see cli/build.gradle.kts).
includeBuild("../../shared/pack-spec")

// cli: the `packer` command-line tool.
include(":cli")
