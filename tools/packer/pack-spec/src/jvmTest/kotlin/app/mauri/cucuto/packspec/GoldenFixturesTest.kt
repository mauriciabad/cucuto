package app.mauri.cucuto.packspec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.readText

/** Runs every fixture listed in spec/fixtures/fixtures.json: the cross-platform contract. */
class GoldenFixturesTest {
    private val specDir = Path.of(System.getProperty("cucuto.specDir") ?: error("cucuto.specDir not set"))
    private val fixturesDir = specDir.resolve("fixtures")

    @TestFactory
    fun fixtures(): List<DynamicTest> {
        val manifest = Json.parseToJsonElement(fixturesDir.resolve("fixtures.json").readText()).jsonObject
        return manifest["fixtures"]!!.jsonArray.map { it.jsonObject }.map { f ->
            val path = f.string("path")
            DynamicTest.dynamicTest(path) {
                val report = validateFixture(fixturesDir.resolve(path), f.string("kind"))
                val expectedErrors = f["errors"]!!.jsonArray.map { it.jsonPrimitive.content }.sorted()
                val expectedWarnings = f["warnings"]!!.jsonArray.map { it.jsonPrimitive.content }.sorted()
                assertEquals(expectedErrors, report.errorCodes(), "errors of $path:\n${report.issues.joinToString("\n")}")
                assertEquals(expectedWarnings, report.warningCodes(), "warnings of $path:\n${report.issues.joinToString("\n")}")
            }
        }
    }

    private fun validateFixture(path: Path, kind: String): ValidationReport = when (kind) {
        "package" -> JvmPackIo.validatePath(path).report
        "repository" -> DocumentValidator.validate(DocumentKind.REPOSITORY, path.readBytes())
        "backup" -> DocumentValidator.validate(DocumentKind.BACKUP, path.readBytes())
        "backup-envelope" -> DocumentValidator.validate(DocumentKind.BACKUP_ENVELOPE, path.readBytes())
        else -> error("Unknown fixture kind $kind")
    }

    @Test
    fun `error codes match spec error-codes json`() {
        val json = Json.parseToJsonElement(specDir.resolve("error-codes.json").readText()).jsonObject
        val fromSpec = json["codes"]!!.jsonArray.map { it.jsonObject }.associate {
            it.string("code") to (it.string("severity") to it.string("stage"))
        }
        val fromEnum = ErrorCode.entries.associate { it.name to (it.severity.name.lowercase() to it.stage.name.lowercase()) }
        assertEquals(fromSpec, fromEnum)
    }

    @Test
    fun `every validation code has at least one fixture`() {
        val manifest = Json.parseToJsonElement(fixturesDir.resolve("fixtures.json").readText()).jsonObject
        val covered = manifest["fixtures"]!!.jsonArray.flatMap { f ->
            (f.jsonObject["errors"]!!.jsonArray + f.jsonObject["warnings"]!!.jsonArray).map { it.jsonPrimitive.content }
        }.toSet()
        // Limits that need huge inputs are covered by unit tests instead of committed fixtures.
        val exempt = setOf(ErrorCode.FILE_TOO_LARGE, ErrorCode.TOO_MANY_FILES, ErrorCode.UNPACKED_TOO_LARGE, ErrorCode.LARGE_PACK)
        val missing = ErrorCode.entries.filter { it.stage != Stage.RUNTIME && it !in exempt && it.name !in covered }
        assertEquals(emptyList<ErrorCode>(), missing)
    }

    private fun JsonObject.string(key: String) = this[key]!!.jsonPrimitive.content
}
