package app.mauri.cucuto.core.data.db

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Index of the packs installed on this device: what the backup lists as pack references and what the routing
 * table reads characters from. The importer (`:core:pack`) writes a row after installing a pack.
 *
 * @property path Absolute path of the installed pack folder (holding `pack.json`), `files/packs/<id>/<version>/`.
 * @property sha256 SHA-256 (lowercase hex) of the `.soundpack` file that was imported.
 * @property bundleId ID of the bundle the pack came in, if any.
 */
@Entity(tableName = "installed_packs")
data class InstalledPackEntity(
    @PrimaryKey val packId: String,
    val version: String,
    val name: String,
    val path: String,
    val sha256: String,
    val sizeBytes: Long,
    val source: PackSource,
    val repositoryUrl: String? = null,
    val downloadUrl: String? = null,
    val fileName: String? = null,
    val bundleId: String? = null,
    val installedAt: Long,
)

/** Where an installed pack came from (backup schema `packReference.source.type`). */
enum class PackSource {
    /** Downloaded from a pack repository ([InstalledPackEntity.repositoryUrl]). */
    REPOSITORY,

    /** Downloaded from a plain URL ([InstalledPackEntity.downloadUrl]). */
    URL,

    /** Imported from a local file; restoring needs the file again ([InstalledPackEntity.fileName]). */
    LOCAL,
}

/**
 * A pack repository the user added. Credentials are not stored here: `:core:repo` keeps them encrypted with an
 * Android Keystore key, looked up by [url].
 */
@Entity(tableName = "repositories", indices = [Index("url", unique = true)])
data class PackRepositoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val name: String? = null,
    val isDefault: Boolean = false,
    val enabled: Boolean = true,
    val authType: RepositoryAuthType = RepositoryAuthType.NONE,
    val addedAt: Long,
)

/** How a repository authenticates (backup schema `repository.auth.type`). */
enum class RepositoryAuthType {
    NONE,
    BASIC,
    BEARER,
}
