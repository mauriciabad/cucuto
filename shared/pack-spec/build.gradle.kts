import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// Consumers depend on "app.mauri.cucuto:pack-spec"; includeBuild substitutes this project for it.
group = "app.mauri.cucuto"

// The repository-level spec/ folder (schemas, error codes, fixtures) is the contract. Its JSON files are embedded as
// Kotlin string constants so commonMain needs no resource or file APIs.
val specDir = layout.projectDirectory.dir("../../spec")
val generatedDir = layout.buildDirectory.dir("generated/spec/commonMain/kotlin")

val generateSpecSources = tasks.register("generateSpecSources") {
    description = "Embeds spec/*.schema.json and spec/error-codes.json as Kotlin constants."
    val inputFiles = specDir.asFileTree.matching { include("*.schema.json", "error-codes.json") }
    inputs.files(inputFiles).withPropertyName("spec")
    val outDir = generatedDir
    outputs.dir(outDir)
    doLast {
        fun literal(text: String): String = text.chunked(8_000).joinToString(" +\n        ") { chunk ->
            buildString {
                append('"')
                for (c in chunk) when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '$' -> append("\\$")
                    '\n' -> append("\\n")
                    '\r' -> {}
                    '\t' -> append("\\t")
                    else -> append(c)
                }
                append('"')
            }
        }
        val files = inputFiles.files.sortedBy { it.name }
        val schemas = files.filter { it.name.endsWith(".schema.json") }
        val codes = files.single { it.name == "error-codes.json" }
        val source = buildString {
            appendLine("// Generated from spec/ by generateSpecSources in shared/pack-spec. Do not edit.")
            appendLine("package app.mauri.cucuto.packspec")
            appendLine()
            appendLine("internal object SpecResources {")
            appendLine("    val schemas: Map<String, String> = mapOf(")
            for (f in schemas) appendLine("        \"${f.name}\" to (${literal(f.readText())}),")
            appendLine("    )")
            appendLine()
            appendLine("    val errorCodes: String = ${literal(codes.readText())}")
            appendLine("}")
        }
        val out = outDir.get().file("app/mauri/cucuto/packspec/SpecResources.kt").asFile
        out.parentFile.mkdirs()
        out.writeText(source)
    }
}

kotlin {
    jvmToolchain(libs.versions.jvm.toolchain.get().toInt())
    jvm {
        compilerOptions { jvmTarget = JvmTarget.JVM_21 }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateSpecSources.map { generatedDir.get() })
            dependencies {
                api(libs.kotlinx.serialization.json)
                implementation(libs.json.schema.validator)
            }
        }
        jvmTest {
            dependencies {
                implementation(project.dependencies.platform(libs.junit.bom))
                implementation(libs.junit.jupiter)
                runtimeOnly(libs.junit.platform.launcher)
            }
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    // Smaller than the large synthetic entries of StreamingZipTest: reading one whole would fail (#84).
    maxHeapSize = "64m"
    systemProperty("cucuto.specDir", specDir.asFile.absolutePath)
    inputs.dir(specDir).withPropertyName("specDir")
}
