package app.mauri.cucuto.core.data.db

import android.content.Context
import androidx.room3.ColumnTypeConverters
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL

/**
 * The app's database. Schemas are exported to `android/core/data/schemas/` (commit them); every version bump needs
 * a migration in [CucutoMigrations] (or an `@AutoMigration`) and a case in `MigrationTest`. There is no destructive
 * fallback: losing assignments silently is worse than a crash caught by tests.
 */
@Database(
    entities = [
        ContactEntity::class,
        ContactKeyEntity::class,
        AssignmentEntity::class,
        InstalledPackEntity::class,
        PackRepositoryEntity::class,
        DecisionLogEntity::class,
        SeenSenderEntity::class,
        PhoneRingtoneEntity::class,
        ContactRingtoneWriteEntity::class,
    ],
    version = CucutoDatabase.VERSION,
    exportSchema = true,
)
@ColumnTypeConverters(Converters::class)
abstract class CucutoDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao

    abstract fun assignmentDao(): AssignmentDao

    abstract fun installedPackDao(): InstalledPackDao

    abstract fun packRepositoryDao(): PackRepositoryDao

    abstract fun decisionLogDao(): DecisionLogDao

    abstract fun seenSenderDao(): SeenSenderDao

    abstract fun phoneRingtoneDao(): PhoneRingtoneDao

    companion object {
        const val VERSION: Int = 3
        const val NAME: String = "cucuto.db"

        /** Opens the on-disk database with the platform SQLite (no bundled native library needed). */
        fun build(context: Context): CucutoDatabase =
            Room
                .databaseBuilder(context, CucutoDatabase::class.java, NAME)
                .setDriver(AndroidSQLiteDriver())
                .addMigrations(*CucutoMigrations.ALL)
                .build()
    }
}

/**
 * Hand-written migrations, oldest first. Each one is covered by `MigrationTest`, which migrates every exported
 * schema version to the current one and validates the result against the current schema.
 */
object CucutoMigrations {
    /** 1 to 2: the bundle's name and version on installed packs, for backups (#43). */
    val MIGRATION_1_2: Migration =
        object : Migration(1, 2) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE installed_packs ADD COLUMN bundleName TEXT")
                connection.execSQL("ALTER TABLE installed_packs ADD COLUMN bundleVersion TEXT")
            }
        }

    /** 2 to 3: phone-call ringtones and the record that undoes them (#52, ADR 0039). */
    val MIGRATION_2_3: Migration =
        object : Migration(2, 3) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `phone_ringtones` (`contactId` INTEGER NOT NULL, `packId` TEXT, " +
                        "`characterId` TEXT, `mode` TEXT NOT NULL, `variant` TEXT, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`contactId`), FOREIGN KEY(`contactId`) REFERENCES `contacts`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `contact_ringtone_writes` (`lookupKey` TEXT NOT NULL, " +
                        "`contactId` INTEGER NOT NULL, `originalRingtone` TEXT, `writtenUri` TEXT, `variant` TEXT, " +
                        "`userChanged` INTEGER NOT NULL, `writtenAt` INTEGER NOT NULL, PRIMARY KEY(`lookupKey`))",
                )
            }
        }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
