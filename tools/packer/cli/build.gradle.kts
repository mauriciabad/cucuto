import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(libs.versions.jvm.toolchain.get().toInt())
    compilerOptions { jvmTarget = JvmTarget.JVM_21 }
}

dependencies {
    implementation(project(":pack-spec"))
    implementation(libs.clikt)
    implementation(libs.kaml)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    applicationName = "packer"
    mainClass = "app.mauri.cucuto.packer.MainKt"
}

val repoRoot = rootProject.layout.projectDirectory.dir("../..")

tasks.test {
    useJUnitPlatform()
    systemProperty("cucuto.repoRoot", repoRoot.asFile.absolutePath)
    inputs.dir(repoRoot.dir("spec")).withPropertyName("spec")
    inputs.dir(repoRoot.dir("packs")).withPropertyName("packs")
}
