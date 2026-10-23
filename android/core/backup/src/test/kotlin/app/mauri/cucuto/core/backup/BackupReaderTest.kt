package app.mauri.cucuto.core.backup

import app.mauri.cucuto.packspec.DocumentKind
import app.mauri.cucuto.packspec.DocumentValidator
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The golden fixtures (spec/fixtures/fixtures.json) and the zip layer of backups. */
class BackupReaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val reader by lazy { BackupReader(tmp.newFolder("work")) }

    private data class Fixture(
        val path: String,
        val kind: String,
        val valid: Boolean,
        val errors: List<String>,
    )

    private val fixtures: List<Fixture> =
        Json
            .parseToJsonElement(File(fixturesDir, "fixtures.json").readText())
            .jsonObject["fixtures"]!!
            .jsonArray
            .map { it.jsonObject }
            .map { o ->
                Fixture(
                    o["path"]!!.jsonPrimitive.content,
                    o["kind"]!!.jsonPrimitive.content,
                    o["valid"]!!.jsonPrimitive.content.toBoolean(),
                    (o["errors"] as JsonArray).map { it.jsonPrimitive.content },
                )
            }

    @Test
    fun `every backup fixture opens or fails with its listed codes`() =
        runTest {
            val backups = fixtures.filter { it.kind == "backup" }
            assertTrue("Expected backup fixtures", backups.size >= 3)
            for (fixture in backups) {
                val zip = zipOf(tmp.newFile(), listOf(BACKUP to File(fixturesDir, fixture.path).readBytes()))
                when (val result = reader.open(fileSource(zip))) {
                    is BackupOpenResult.Opened -> {
                        assertTrue("${fixture.path} should be invalid", fixture.valid)
                        result.backup.use { assertFalse(it.encrypted) }
                    }

                    is BackupOpenResult.Invalid -> {
                        assertTrue("${fixture.path}: ${result.report.issues}", !fixture.valid)
                        assertEquals(fixture.path, fixture.errors, result.report.errorCodes())
                    }

                    else -> {
                        throw AssertionError("${fixture.path}: $result")
                    }
                }
            }
        }

    @Test
    fun `the valid fixture decodes into the model and encodes back to the same JSON`() {
        val text = File(fixturesDir, "valid/backup.json").readText()
        val document = BackupJson.decodeFromString(BackupDocument.serializer(), text)
        assertEquals("app.mauri.cucuto", document.app.id)
        assertEquals(2, document.contacts.size)
        val ada = document.contacts.first()
        assertEquals("frog", ada.message!!.characterId)
        assertEquals(listOf("characters/frog/clips/croak.ogg"), ada.message!!.disabledClips)
        assertEquals(BackupRingtoneMode.FIXED, ada.ringtone!!.rule.mode)
        assertEquals(BackupGroupPolicy.PER_SENDER, document.contacts[1].groupPolicy)
        assertEquals(listOf("com.example.private.songs.day"), document.packs[1].packIds)
        assertEquals(BackupAuthType.BASIC, document.repositories[1].auth.type)

        val encoded = BackupExporter.encode(document)
        assertTrue(DocumentValidator.validate(DocumentKind.BACKUP, encoded).isValid)
        // Same content once defaults the fixture leaves out are filled in.
        val again = BackupJson.decodeFromString(BackupDocument.serializer(), encoded.decodeToString())
        assertEquals(document, again)
        assertEquals(
            withoutDefaults(Json.parseToJsonElement(text).jsonObject),
            withoutDefaults(Json.parseToJsonElement(encoded.decodeToString()).jsonObject),
        )
    }

    @Test
    fun `envelope fixtures follow the schema`() {
        for (fixture in fixtures.filter { it.kind == "backup-envelope" }) {
            val report =
                DocumentValidator.validate(
                    DocumentKind.BACKUP_ENVELOPE,
                    File(fixturesDir, fixture.path).readBytes(),
                )
            assertEquals(fixture.path, fixture.errors, report.errorCodes())
            if (fixture.valid) {
                val envelope =
                    BackupJson.decodeFromString(BackupEnvelope.serializer(), File(fixturesDir, fixture.path).readText())
                assertEquals(65_536, envelope.kdf.memoryKiB)
                assertEquals(BackupCrypto.NONCE_BYTES, BackupCrypto.decodeBase64(envelope.nonce)!!.size)
            }
        }
    }

    @Test
    fun `packs and bundles are sent to the importer and other zips are rejected`() =
        runTest {
            val pack = zipFolder(File(fixturesDir, "valid/minimal-pack"), tmp.newFile("a.zip"))
            assertEquals(BackupOpenResult.NotABackup(ZipContentType.PACK), reader.open(fileSource(pack)))
            val bundle = zipFolder(File(fixturesDir, "valid/bundle"), tmp.newFile("b.zip"))
            assertEquals(BackupOpenResult.NotABackup(ZipContentType.BUNDLE), reader.open(fileSource(bundle)))
            assertEquals(ZipContentType.PACK, ZipContentSniffer.sniff(pack).type)

            val empty = zipOf(tmp.newFile(), listOf("readme.txt" to "hi".encodeToByteArray()))
            assertCodes(listOf("MANIFEST_MISSING"), reader.open(fileSource(empty)))
            val both =
                zipOf(
                    tmp.newFile(),
                    listOf(
                        BACKUP to File(fixturesDir, "valid/backup.json").readBytes(),
                        PACK to "{}".encodeToByteArray(),
                    ),
                )
            assertCodes(listOf("MANIFEST_AMBIGUOUS"), reader.open(fileSource(both)))
            val notZip = tmp.newFile().apply { writeText("not a zip at all, just text") }
            assertCodes(listOf("ARCHIVE_UNREADABLE"), reader.open(fileSource(notZip)))
            val lonelyEnvelope =
                zipOf(
                    tmp.newFile(),
                    listOf(ENVELOPE to File(fixturesDir, "valid/envelope.json").readBytes()),
                )
            assertCodes(listOf("FILE_MISSING"), reader.open(fileSource(lonelyEnvelope)))
        }

    @Test
    fun `a broken backup json is reported with the validator's codes`() =
        runTest {
            val syntax = zipOf(tmp.newFile(), listOf(BACKUP to "{ \"formatVersion\": 1, }".encodeToByteArray()))
            assertCodes(listOf("MANIFEST_INVALID_JSON"), reader.open(fileSource(syntax)))
            val future =
                File(
                    fixturesDir,
                    "valid/backup.json",
                ).readText().replace("\"formatVersion\": 1", "\"formatVersion\": 2")
            val newer = zipOf(tmp.newFile(), listOf(BACKUP to future.encodeToByteArray()))
            assertCodes(listOf("FORMAT_VERSION_UNSUPPORTED"), reader.open(fileSource(newer)))
        }

    @Test
    fun `scratch files are deleted when a backup is closed or rejected`() =
        runTest {
            val work = tmp.newFolder("scratch")
            val reader = BackupReader(work)
            val zip = zipOf(tmp.newFile(), listOf(BACKUP to File(fixturesDir, "valid/backup.json").readBytes()))
            val opened = (reader.open(fileSource(zip)) as BackupOpenResult.Opened).backup
            assertTrue(work.listFiles()!!.isNotEmpty())
            opened.close()
            assertEquals(0, work.listFiles()!!.size)
            reader.open(fileSource(tmp.newFile().apply { writeText("x") }))
            assertEquals(0, work.listFiles()!!.size)
        }

    private fun assertCodes(
        expected: List<String>,
        result: BackupOpenResult,
    ) {
        val report =
            (result as? BackupOpenResult.Invalid)?.report ?: throw AssertionError("Expected Invalid, got $result")
        assertEquals(expected, report.errorCodes())
    }

    private fun assertFalse(value: Boolean) = org.junit.Assert.assertFalse(value)

    /** Drops values equal to the schema's defaults, which the writer always spells out. */
    private fun withoutDefaults(
        element: kotlinx.serialization.json.JsonElement,
    ): kotlinx.serialization.json.JsonElement =
        when (element) {
            is JsonObject -> {
                JsonObject(
                    element
                        .filterNot { (k, v) ->
                            val p = v as? kotlinx.serialization.json.JsonPrimitive
                            (k == "isGroup" && p?.content == "false") ||
                                (k == "isDefault" && p?.content == "false") ||
                                (k == "enabled" && p?.content == "true" && element.containsKey("auth")) ||
                                (
                                    (k == "phoneNumbers" || k == "disabledClips" || k == "keys") &&
                                        (v as? JsonArray)?.isEmpty() == true
                                )
                        }.mapValues { withoutDefaults(it.value) },
                )
            }

            is JsonArray -> {
                JsonArray(element.map(::withoutDefaults))
            }

            else -> {
                element
            }
        }
}
