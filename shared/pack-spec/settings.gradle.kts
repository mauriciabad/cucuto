// pack-spec: the pack format rules shared by packer (tools/packer) and the Android importer (android/core/pack).
// A standalone build so both include it with includeBuild (docs/decisions/0005-shared-pack-spec-module.md).
rootProject.name = "pack-spec"

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
