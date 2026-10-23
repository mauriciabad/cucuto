package app.mauri.cucuto.core.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mauri.cucuto.core.data.db.AssignmentEntity
import app.mauri.cucuto.core.data.db.ContactEntity
import app.mauri.cucuto.core.data.db.ContactKeyEntity
import app.mauri.cucuto.core.data.db.ContactKeyKind
import app.mauri.cucuto.core.data.db.CucutoDatabase
import app.mauri.cucuto.core.data.db.GroupPolicy
import app.mauri.cucuto.core.data.db.InstalledPackEntity
import app.mauri.cucuto.core.data.db.PackRepositoryEntity
import app.mauri.cucuto.core.data.db.PackSource
import app.mauri.cucuto.core.data.db.RepositoryAuthType
import app.mauri.cucuto.core.pack.PackStore
import app.mauri.cucuto.core.repo.RepositoryCredentials
import app.mauri.cucuto.packspec.FileInput
import app.mauri.cucuto.packspec.JvmPackIo
import app.mauri.cucuto.packspec.PackValidator
import app.mauri.cucuto.packspec.zip.ZipArchive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/** Export from one database, read the file back and restore it into an empty one: a phone change on the JVM. */
@RunWith(AndroidJUnit4::class)
class BackupRoundTripTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var oldPhone: CucutoDatabase
    private lateinit var newPhone: CucutoDatabase
    private val clock = FakeClock()
    private val credentials = FakeCredentialStore()
    private val settings = FakeSettings(enabled = false)
    private lateinit var store: PackStore

    private val official = "https://cucuto.mauri.app/repo/index.json"
    private val private = "https://packs.example.com/private/"
    private val critters = "app.mauri.fixtures.minimal"
    private val sha = "1".repeat(64)

    @Before
    fun setUp() {
        oldPhone = inMemoryDatabase()
        newPhone = inMemoryDatabase()
        store = PackStore(tmp.newFolder("packs"))
    }

    @After
    fun tearDown() {
        oldPhone.close()
        newPhone.close()
    }

    private fun exporter(db: CucutoDatabase = oldPhone) =
        BackupExporter(
            db = db,
            settings = settings,
            packStore = store,
            credentials = credentials,
            app = BackupApp(versionName = "0.1.0", versionCode = 1),
            workDir = tmp.newFolder(),
            clock = clock,
        )

    private suspend fun seedOldPhone() {
        val contacts = oldPhone.contactDao()
        val ada =
            contacts.insert(
                ContactEntity(
                    displayName = "Ada",
                    shuffleSeed = 1,
                    createdAt = 1,
                    updatedAt = 1,
                    androidLookupKey = "0r1-ABC",
                ),
            )
        contacts.insertKey(key(ada, "com.whatsapp", "34600111222@s.whatsapp.net"))
        contacts.insertKey(key(ada, "com.whatsapp", "123456789@lid"))
        contacts.insertKey(key(ada, ContactKeyEntity.NO_PACKAGE, "+34600111222"))
        oldPhone.assignmentDao().upsert(
            AssignmentEntity(
                contactId = ada,
                packId = critters,
                characterId = "beep",
                disabledClips = listOf("characters/beep/clips/b.ogg", "characters/beep/clips/a.ogg"),
                hapticOverride = """{"mode":"custom","events":[{"at":0,"preset":"CLICK"}]}""",
                updatedAt = 1,
            ),
        )
        val family =
            contacts.insert(
                ContactEntity(
                    displayName = "Family",
                    isGroup = true,
                    groupPolicy = GroupPolicy.PER_SENDER,
                    shuffleSeed = 2,
                    createdAt = 1,
                    updatedAt = 1,
                ),
            )
        contacts.insertKey(key(family, "com.whatsapp.w4b", "120363000000000000@g.us"))

        oldPhone.packRepositoryDao().insert(
            PackRepositoryEntity(url = official, name = "Official", isDefault = true, addedAt = 1),
        )
        oldPhone.packRepositoryDao().insert(
            PackRepositoryEntity(url = private, authType = RepositoryAuthType.BASIC, addedAt = 2),
        )
        credentials.put(private, RepositoryCredentials.Basic("ana", "s3cret"))

        oldPhone.installedPackDao().upsert(installed(critters, sha, PackSource.REPOSITORY, repositoryUrl = official))
        installIntoStore("valid/minimal-pack", critters, "1.0.0")
    }

    private fun key(
        contactId: Long,
        packageName: String,
        value: String,
    ) = ContactKeyEntity(
        contactId = contactId,
        packageName = packageName,
        value = value,
        kind = ContactKeyKind.classify(value),
    )

    private fun installed(
        id: String,
        sha256: String,
        source: PackSource,
        repositoryUrl: String? = null,
        bundleId: String? = null,
    ) = InstalledPackEntity(
        packId = id,
        version = "1.0.0",
        name = id,
        path = "/unused",
        sha256 = sha256,
        sizeBytes = 1000,
        source = source,
        repositoryUrl = repositoryUrl,
        fileName = if (source == PackSource.LOCAL) "$id.soundpack.zip" else null,
        bundleId = bundleId,
        bundleName = bundleId?.let { "Bundle" },
        bundleVersion = bundleId?.let { "2.0.0" },
        installedAt = 1,
    )

    /** Copies a fixture pack folder into the store's layout, as the importer leaves it. */
    private fun installIntoStore(
        fixture: String,
        id: String,
        version: String,
    ) {
        val dir = File(store.root, "$id/$version")
        File(fixturesDir, fixture).copyRecursively(dir)
        File(store.root, "$id/active").writeText("$version\n")
    }

    private suspend fun open(
        bytes: ByteArray,
        passphrase: CharArray? = null,
    ): BackupOpenResult =
        BackupReader(tmp.newFolder()).open(
            fileSource(
                tmp.newFile().apply {
                    writeBytes(bytes)
                },
            ),
            passphrase,
        )

    private suspend fun export(options: BackupOptions = BackupOptions()): Pair<BackupExportResult, ByteArray> {
        val out = ByteArrayOutputStream()
        val result = exporter().export(out, options)
        return result to out.toByteArray()
    }

    @Test
    fun `a plain backup brings everything back except credentials`() =
        runTest {
            seedOldPhone()
            val (result, bytes) = export()
            assertFalse(result.encrypted)
            assertEquals(ZipContentType.BACKUP, ZipContentSniffer.sniff(tmp.newFile().apply { writeBytes(bytes) }).type)
            val document = result.document
            assertEquals("2025-10-23T18:30:00Z", document.createdAt)
            assertNull(
                document.repositories
                    .single { it.url == private }
                    .auth.credentials,
            )

            val opened = (open(bytes) as BackupOpenResult.Opened).backup
            opened.use { backup ->
                assertEquals(document, backup.document)
                val target = FakeSettings(enabled = true)
                val newCredentials = FakeCredentialStore()
                val report =
                    BackupRestorer(
                        newPhone,
                        target,
                        newCredentials,
                        clock,
                    ).restore(backup.document, backup.encrypted)
                assertEquals(2, report.created)
                assertEquals(listOf(official, private), report.repositoriesAdded)
                assertEquals(listOf(AppSettingsBackup.ENABLED), report.settings.applied)
                assertFalse(target.enabled)
                assertTrue(newCredentials.entries.isEmpty())
            }

            val restored = newPhone.contactDao().observeAll().first()
            val ada = restored.single { it.contact.displayName == "Ada" }
            assertEquals(
                setOf("34600111222@s.whatsapp.net", "123456789@lid", "+34600111222"),
                ada.keys.map { it.value }.toSet(),
            )
            assertEquals("0r1-ABC", ada.contact.androidLookupKey)
            val assignment = newPhone.assignmentDao().get(ada.contact.id)!!
            assertEquals(critters to "beep", assignment.packId to assignment.characterId)
            assertEquals(listOf("characters/beep/clips/a.ogg", "characters/beep/clips/b.ogg"), assignment.disabledClips)
            assertEquals("""{"mode":"custom","events":[{"at":0,"preset":"CLICK"}]}""", assignment.hapticOverride)
            val family = restored.single { it.contact.isGroup }
            assertEquals(GroupPolicy.PER_SENDER, family.contact.groupPolicy)
            assertEquals(RepositoryAuthType.BASIC, newPhone.packRepositoryDao().getByUrl(private)!!.authType)

            // Exporting the restored phone gives the same backup, apart from the local IDs.
            val again = exporter(newPhone).snapshot()
            assertEquals(
                document.contacts.map { it.copy(id = "") }.toSet(),
                again.contacts.map { it.copy(id = "") }.toSet(),
            )
        }

    @Test
    fun `an encrypted backup needs its passphrase and carries credentials when asked`() =
        runTest {
            seedOldPhone()
            val passphrase = "correct horse battery staple".toCharArray()
            val (result, bytes) =
                export(
                    BackupOptions(passphrase = passphrase, includeCredentials = true, kdf = KdfParameters.MINIMUM),
                )
            assertTrue(result.encrypted)
            assertEquals(
                BackupCredentials(username = "ana", password = "s3cret"),
                result.document.repositories[1]
                    .auth.credentials,
            )
            // Nothing readable outside the payload.
            val file = tmp.newFile().apply { writeBytes(bytes) }
            assertEquals(ZipContentType.ENCRYPTED_BACKUP, ZipContentSniffer.sniff(file).type)
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains("s3cret"))
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains("Ada"))

            assertEquals(BackupOpenResult.PassphraseRequired, open(bytes))
            assertTrue(open(bytes, "wrong".toCharArray()) is BackupOpenResult.DecryptionFailed)

            val opened = (open(bytes, passphrase) as BackupOpenResult.Opened).backup
            opened.use { backup ->
                assertTrue(backup.encrypted)
                val newCredentials = FakeCredentialStore()
                val report =
                    BackupRestorer(
                        newPhone,
                        FakeSettings(),
                        newCredentials,
                        clock,
                    ).restore(backup.document, backup.encrypted)
                assertEquals(listOf(private), report.credentialsRestored)
                assertEquals(RepositoryCredentials.Basic("ana", "s3cret"), newCredentials.entries[private])
            }
        }

    @Test
    fun `a tampered encrypted backup is rejected`() =
        runTest {
            seedOldPhone()
            val passphrase = "pw".toCharArray()
            val (_, bytes) = export(BackupOptions(passphrase = passphrase, kdf = KdfParameters.MINIMUM))
            // Flip a byte inside payload.bin. The zip CRC or the GCM tag catches it (BackupCryptoTest covers the tag
            // alone); either way nothing is restored.
            val file = tmp.newFile().apply { writeBytes(bytes) }
            val record =
                FileInput(file.toPath()).use { input ->
                    ZipArchive.open(input, JvmPackIo.inflater).source!!.records.single {
                        it.name ==
                            BackupEnvelope.PAYLOAD
                    }
                }
            val tampered = bytes.copyOf()
            val at = (record.localHeaderOffset + 30 + BackupEnvelope.PAYLOAD.length + 40).toInt()
            tampered[at] = (tampered[at] + 1).toByte()
            val result = runCatching { open(tampered, passphrase) }.getOrNull()
            assertFalse(result is BackupOpenResult.Opened)
        }

    @Test
    fun `credentials are only exported with a passphrase`() {
        assertThrows(IllegalArgumentException::class.java) { BackupOptions(includeCredentials = true) }
    }

    @Test
    fun `a full backup includes valid pack files and fills unknown hashes`() =
        runTest {
            seedOldPhone()
            // A pack recorded without a hash (debug tools), and a bundle of two installed packs.
            val full = "app.mauri.fixtures.full"
            oldPhone.installedPackDao().upsert(installed(full, "", PackSource.LOCAL).copy(version = "2.1.0-beta.1"))
            installIntoStore("valid/full-pack", full, "2.1.0-beta.1")
            val bundlePacks =
                File(
                    fixturesDir,
                    "valid/bundle/packs",
                ).listFiles()!!.filter { File(it, "pack.json").isFile }
            assertEquals(2, bundlePacks.size)
            for (folder in bundlePacks) {
                val id =
                    Regex(
                        "\"id\"\\s*:\\s*\"([^\"]+)\"",
                    ).find(File(folder, "pack.json").readText())!!.groupValues[1]
                val version =
                    Regex(
                        "\"version\"\\s*:\\s*\"([^\"]+)\"",
                    ).find(File(folder, "pack.json").readText())!!.groupValues[1]
                oldPhone.installedPackDao().upsert(
                    installed(
                        id,
                        "2".repeat(64),
                        PackSource.LOCAL,
                        bundleId = "app.mauri.fixtures.bundle",
                    ).copy(version = version),
                )
                installIntoStore("valid/bundle/packs/${folder.name}", id, version)
            }

            val plain = exporter().snapshot()
            val (withoutFiles, _) = export()
            assertEquals(listOf(full), withoutFiles.skippedPacks)
            assertEquals(plain.packs.size - 1, withoutFiles.document.packs.size)

            val (result, bytes) = export(BackupOptions(includePackFiles = true))
            assertEquals(emptyList<String>(), result.skippedPacks)
            assertEquals(3, result.includedPacks.size)
            val hashed = result.document.packs.single { it.id == full }
            assertTrue(hashed.sha256 != BackupSnapshot.UNKNOWN_SHA256)
            val bundle = result.document.packs.single { it.type == BackupPackType.BUNDLE }
            assertEquals("2.0.0", bundle.version)
            assertEquals("2".repeat(64), bundle.sha256)

            val opened = (open(bytes) as BackupOpenResult.Opened).backup
            opened.use { backup ->
                assertEquals(3, backup.includedPacks.size)
                for (ref in backup.includedPacks) {
                    val zip = tmp.newFile()
                    backup.includedPack(ref)!!.open().use { input -> zip.outputStream().use { input.copyTo(it) } }
                    val validation = JvmPackIo.validatePath(zip.toPath(), PackValidator())
                    assertTrue("${ref.id}: ${validation.report.issues}", validation.report.isValid)
                    if (ref.sha256 == hashed.sha256) assertEquals(hashed.size, zip.length())
                }
                val plan =
                    PackRestorePlanner.plan(backup.document.packs, emptyMap(), backup.includedPacks)
                assertTrue(plan.all { it.action == PackRestoreAction.FROM_BACKUP })
            }
        }
}
