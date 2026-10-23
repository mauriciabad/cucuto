package app.mauri.cucuto.core.data.db

import android.content.Context
import androidx.room3.ColumnTypeConverters
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.driver.AndroidSQLiteDriver

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

    companion object {
        const val VERSION: Int = 1
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
    val ALL: Array<Migration> = arrayOf()
}
