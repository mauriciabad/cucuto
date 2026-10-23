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

// pack-spec: validation core with no JVM-only APIs in commonMain (see docs/decisions/0001-shared-pack-spec-module.md).
// cli: the `packer` command-line tool.
include(":pack-spec", ":cli")
