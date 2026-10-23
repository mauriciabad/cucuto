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
