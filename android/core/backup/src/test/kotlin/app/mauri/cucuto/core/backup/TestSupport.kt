package app.mauri.cucuto.core.backup

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import app.mauri.cucuto.core.data.db.CucutoDatabase
import app.mauri.cucuto.core.data.di.WallClock
import app.mauri.cucuto.core.pack.ImportSource
import app.mauri.cucuto.core.repo.RepositoryCredentials
import app.mauri.cucuto.core.repo.credentials.CredentialStore
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** spec/ at the repository root (set by the Gradle test task). */
val specDir: File = File(System.getProperty("cucuto.specDir") ?: error("cucuto.specDir is not set"))

val fixturesDir: File = File(specDir, "fixtures")

/** An in-memory database on the platform SQLite (Robolectric's native SQLite in unit tests). */
fun inMemoryDatabase(): CucutoDatabase =
    Room
        .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CucutoDatabase::class.java)
        .setDriver(AndroidSQLiteDriver())
        .build()

class FakeClock(
    var time: Long = 1_761_244_200_000, // 2025-10-23T18:30:00Z
) : WallClock {
    override fun now(): Long = time
}

/** Settings kept in memory, with the same rules as [AppSettingsBackup]: one boolean key. */
class FakeSettings(
    var enabled: Boolean = true,
) : SettingsBackup {
    override suspend fun export(): JsonObject =
        JsonObject(mapOf(AppSettingsBackup.ENABLED to kotlinx.serialization.json.JsonPrimitive(enabled)))

    override suspend fun restore(settings: JsonObject): SettingsRestoreResult {
        val applied = mutableListOf<String>()
        val ignored = mutableListOf<String>()
        for ((key, value) in settings) {
            val bool = (value as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull()
            if (key == AppSettingsBackup.ENABLED && bool != null) {
                enabled = bool
                applied += key
            } else {
                ignored += key
            }
        }
        return SettingsRestoreResult(applied, ignored)
    }
}

class FakeCredentialStore : CredentialStore {
    val entries = mutableMapOf<String, RepositoryCredentials>()

    override suspend fun get(repositoryUrl: String) = entries[repositoryUrl]

    override suspend fun put(
        repositoryUrl: String,
        credentials: RepositoryCredentials,
    ) {
        entries[repositoryUrl] = credentials
    }

    override suspend fun remove(repositoryUrl: String) {
        entries.remove(repositoryUrl)
    }

    override suspend fun repositoryUrls() = entries.keys.toSet()
}

/** Writes a zip with the given entries (in order). */
fun zipOf(
    target: File,
    entries: List<Pair<String, ByteArray>>,
): File {
    ZipOutputStream(target.outputStream()).use { zip ->
        for ((name, bytes) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return target
}

/** Zips a folder (every regular file, sorted) the way a user's zip tool would. */
fun zipFolder(
    folder: File,
    target: File,
): File =
    zipOf(
        target,
        folder
            .walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(folder).invariantSeparatorsPath }
            .sorted()
            .map { it to File(folder, it).readBytes() }
            .toList(),
    )

fun fileSource(file: File): ImportSource = ImportSource.of(file)
