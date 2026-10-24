package app.mauri.cucuto.core.data.db

import androidx.room3.Room
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The migration harness. For every exported schema version (`android/core/data/schemas/`), it creates a database
 * at that version, migrates it to [CucutoDatabase.VERSION] with [CucutoMigrations.ALL] and lets Room validate the
 * result against the current schema. When you bump the version: export the new schema, add the migration, and add
 * a data-preservation case below for anything non-trivial.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val file: File =
        ApplicationProvider.getApplicationContext<android.content.Context>().getDatabasePath("migration-test.db")

    @get:Rule
    val helper =
        MigrationTestHelper(
            instrumentation = InstrumentationRegistry.getInstrumentation(),
            file = file,
            driver = AndroidSQLiteDriver(),
            databaseClass = CucutoDatabase::class,
        )

    @Test
    fun everyExportedSchemaIsCommitted() {
        val assets = ApplicationProvider.getApplicationContext<android.content.Context>().assets
        val versions =
            assets
                .list(CucutoDatabase::class.java.name)
                .orEmpty()
                .map { it.removeSuffix(".json").toInt() }
                .sorted()
        assertEquals((1..CucutoDatabase.VERSION).toList(), versions)
    }

    @Test
    fun migrationsCoverEveryVersion() {
        val covered = CucutoMigrations.ALL.map { it.startVersion to it.endVersion }
        for (version in 1 until CucutoDatabase.VERSION) {
            assertTrue("No migration from $version", covered.any { it.first == version })
        }
    }

    @Test
    fun everyVersionMigratesToTheCurrentSchema() =
        runTest {
            for (version in 1..CucutoDatabase.VERSION) {
                file.delete()
                helper.createDatabase(version).close()
                helper.runMigrationsAndValidate(CucutoDatabase.VERSION, CucutoMigrations.ALL.toList()).close()
            }
        }

    @Test
    fun version2KeepsInstalledPacksAndAddsBundleColumns() =
        runTest {
            file.delete()
            helper.createDatabase(1).use { connection ->
                connection.execSQL(
                    "INSERT INTO installed_packs (packId, version, name, path, sha256, sizeBytes, source, bundleId, " +
                        "installedAt) VALUES ('com.example.songs.day', '1.0.0', 'Day', '/p', '', 10, 'LOCAL', " +
                        "'com.example.songs', 1)",
                )
            }
            helper.runMigrationsAndValidate(2, listOf(CucutoMigrations.MIGRATION_1_2)).close()
            val db =
                Room
                    .databaseBuilder(ApplicationProvider.getApplicationContext(), CucutoDatabase::class.java, file.path)
                    .setDriver(AndroidSQLiteDriver())
                    .addMigrations(*CucutoMigrations.ALL)
                    .build()
            try {
                val pack = db.installedPackDao().get("com.example.songs.day")!!
                assertEquals("com.example.songs", pack.bundleId)
                assertEquals(null, pack.bundleName)
                assertEquals(null, pack.bundleVersion)
            } finally {
                db.close()
            }
        }

    @Test
    fun version4MarksPickedRingtonesAndKeepsTheOnesThatFollowTheVoice() =
        runTest {
            file.delete()
            helper.createDatabase(3).use { connection ->
                connection.execSQL(
                    "INSERT INTO contacts (id, displayName, isGroup, groupPolicy, shuffleSeed, createdAt, updatedAt) " +
                        "VALUES (1, 'Ada', 0, 'GROUP', 7, 1, 1), (2, 'Bruno', 0, 'GROUP', 8, 1, 1)",
                )
                connection.execSQL(
                    "INSERT INTO phone_ringtones (contactId, packId, characterId, mode, variant, updatedAt) " +
                        "VALUES (1, NULL, NULL, 'TIME', NULL, 1), (2, 'com.example.songs', 'bubblegum', 'PACK', NULL, 1)",
                )
            }
            helper.runMigrationsAndValidate(4, listOf(CucutoMigrations.MIGRATION_3_4)).close()
            val db =
                Room
                    .databaseBuilder(ApplicationProvider.getApplicationContext(), CucutoDatabase::class.java, file.path)
                    .setDriver(AndroidSQLiteDriver())
                    .addMigrations(*CucutoMigrations.ALL)
                    .build()
            try {
                val follows = db.phoneRingtoneDao().get(1)!!
                assertTrue(follows.followsVoice)
                assertEquals(RingtoneMode.TIME, follows.mode)
                val picked = db.phoneRingtoneDao().get(2)!!
                assertEquals(false, picked.followsVoice)
                assertEquals("bubblegum", picked.characterId)
            } finally {
                db.close()
            }
        }

    @Test
    fun version5KeepsEveryRingtoneChoiceWithoutRulesOfItsOwn() =
        runTest {
            file.delete()
            helper.createDatabase(4).use { connection ->
                connection.execSQL(
                    "INSERT INTO contacts (id, displayName, isGroup, groupPolicy, shuffleSeed, createdAt, updatedAt) " +
                        "VALUES (1, 'Ada', 0, 'GROUP', 7, 1, 1)",
                )
                connection.execSQL(
                    "INSERT INTO phone_ringtones (contactId, packId, characterId, mode, variant, updatedAt, " +
                        "followsVoice) VALUES (1, 'com.example.songs', 'bubblegum', 'FIXED', 'radio.ogg', 1, 0)",
                )
            }
            helper.runMigrationsAndValidate(5, listOf(CucutoMigrations.MIGRATION_4_5)).close()
            val db =
                Room
                    .databaseBuilder(ApplicationProvider.getApplicationContext(), CucutoDatabase::class.java, file.path)
                    .setDriver(AndroidSQLiteDriver())
                    .addMigrations(*CucutoMigrations.ALL)
                    .build()
            try {
                val row = db.phoneRingtoneDao().get(1)!!
                assertEquals(RingtoneMode.FIXED, row.mode)
                assertEquals("radio.ogg", row.variant)
                assertEquals(null, row.rules)
                val rules = listOf(RingtoneRuleEntry("radio.ogg"))
                db.phoneRingtoneDao().upsert(row.copy(mode = RingtoneMode.CUSTOM, rules = RingtoneRules.encode(rules)))
                assertEquals(rules, RingtoneRules.decode(db.phoneRingtoneDao().get(1)!!.rules))
            } finally {
                db.close()
            }
        }

    @Test
    fun version6KeepsEveryRingtoneACharacter() =
        runTest {
            file.delete()
            helper.createDatabase(5).use { connection ->
                connection.execSQL(
                    "INSERT INTO contacts (id, displayName, isGroup, groupPolicy, shuffleSeed, createdAt, updatedAt) " +
                        "VALUES (1, 'Ada', 0, 'GROUP', 7, 1, 1)",
                )
                connection.execSQL(
                    "INSERT INTO phone_ringtones (contactId, packId, characterId, mode, variant, updatedAt, " +
                        "followsVoice, rules) VALUES (1, 'com.example.songs', 'bubblegum', 'PACK', NULL, 1, 0, NULL)",
                )
            }
            helper.runMigrationsAndValidate(6, listOf(CucutoMigrations.MIGRATION_5_6)).close()
            val db =
                Room
                    .databaseBuilder(ApplicationProvider.getApplicationContext(), CucutoDatabase::class.java, file.path)
                    .setDriver(AndroidSQLiteDriver())
                    .addMigrations(*CucutoMigrations.ALL)
                    .build()
            try {
                val row = db.phoneRingtoneDao().get(1)!!
                assertEquals(RingtoneChoice.CHARACTER, row.choice)
                assertEquals("bubblegum", row.characterId)
            } finally {
                db.close()
            }
        }

    @Test
    fun dataWrittenAtVersion1SurvivesOpeningWithRoom() =
        runTest {
            file.delete()
            helper.createDatabase(1).use { connection ->
                connection.execSQL(
                    "INSERT INTO contacts (id, displayName, isGroup, groupPolicy, shuffleSeed, createdAt, updatedAt) " +
                        "VALUES (1, 'Ada', 0, 'GROUP', 7, 1, 1)",
                )
                connection.execSQL(
                    "INSERT INTO contact_keys (contactId, packageName, value, kind) " +
                        "VALUES (1, 'com.whatsapp', '34600000001@s.whatsapp.net', 'PHONE_JID')",
                )
                connection.execSQL(
                    "INSERT INTO assignments (contactId, packId, characterId, disabledClips, updatedAt) " +
                        "VALUES (1, 'app.mauri.demo.critters', 'frog', '[\"a.ogg\"]', 1)",
                )
            }
            val db =
                Room
                    .databaseBuilder(ApplicationProvider.getApplicationContext(), CucutoDatabase::class.java, file.path)
                    .setDriver(AndroidSQLiteDriver())
                    .addMigrations(*CucutoMigrations.ALL)
                    .build()
            try {
                val contact =
                    db
                        .contactDao()
                        .observeAll()
                        .first()
                        .single()
                assertEquals("Ada", contact.contact.displayName)
                assertEquals(ContactKeyKind.PHONE_JID, contact.keys.single().kind)
                assertEquals(listOf("a.ogg"), db.assignmentDao().get(1)!!.disabledClips)
            } finally {
                db.close()
            }
        }
}
