package app.mauri.cucuto.core.pack

import app.mauri.cucuto.packspec.DocumentKind
import app.mauri.cucuto.packspec.DocumentValidator
import app.mauri.cucuto.packspec.ValidationReport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

/**
 * Every golden fixture in spec/fixtures/fixtures.json, through the importer the app uses: packs and bundles as
 * archives (folders are zipped first) from an [ImportSource], documents through pack-spec's [DocumentValidator].
 */
@RunWith(Parameterized::class)
class GoldenFixturesTest(
    private val path: String,
    private val fixture: JsonObject,
) {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun reportsTheExpectedCodes() =
        runTest {
            val report =
                when (val kind = fixture.string("kind")) {
                    "package" -> importReport()
                    "repository" -> document(DocumentKind.REPOSITORY)
                    "backup" -> document(DocumentKind.BACKUP)
                    "backup-envelope" -> document(DocumentKind.BACKUP_ENVELOPE)
                    else -> error("Unknown fixture kind $kind")
                }
            val details = "$path:\n" + report.issues.joinToString("\n")
            assertEquals(details, fixture.codes("errors"), report.errorCodes())
            assertEquals(details, fixture.codes("warnings"), report.warningCodes())
            assertEquals(details, fixture["valid"]!!.jsonPrimitive.content.toBoolean(), report.isValid)
        }

    private suspend fun importReport(): ValidationReport {
        val root = tmp.newFolder("app")
        val importer = testImporter(root)
        val archive = fixtureArchive(path, tmp.newFolder("archives"))
        val report =
            when (val result = importer.prepare(ImportSource.of(archive)).result()) {
                is ImportResult.Ready -> result.import.report.also { importer.discard(result.import) }
                is ImportResult.Invalid -> result.report
                else -> throw AssertionError("$path: unexpected $result")
            }
        // Nothing is left behind, whatever the outcome.
        assertTrue(File(root, "cache/pack-import").listFiles().orEmpty().isEmpty())
        assertTrue(File(root, "files/packs/.staging").listFiles().orEmpty().isEmpty())
        return report
    }

    private fun document(kind: DocumentKind) = DocumentValidator.validate(kind, File(fixturesDir, path).readBytes())

    private fun JsonObject.string(key: String) = this[key]!!.jsonPrimitive.content

    private fun JsonObject.codes(key: String) = this[key]!!.jsonArray.map { it.jsonPrimitive.content }.sorted()

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fixtures(): List<Array<Any>> {
            val manifest = Json.parseToJsonElement(File(fixturesDir, "fixtures.json").readText()).jsonObject
            return manifest["fixtures"]!!.jsonArray.map { it.jsonObject }.map {
                arrayOf(
                    it["path"]!!.jsonPrimitive.content,
                    it,
                )
            }
        }
    }
}
