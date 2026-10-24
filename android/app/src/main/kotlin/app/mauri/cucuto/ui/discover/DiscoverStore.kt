package app.mauri.cucuto.ui.discover

import app.mauri.cucuto.core.data.db.PackRepositoryEntity
import app.mauri.cucuto.core.data.db.RepositoryAuthType
import app.mauri.cucuto.core.data.repository.InstalledPackRepository
import app.mauri.cucuto.core.data.repository.PackRepositoryRegistry
import app.mauri.cucuto.core.repo.DEFAULT_REPOSITORY_URL
import app.mauri.cucuto.core.repo.RepositoryCredentials
import app.mauri.cucuto.core.repo.credentials.CredentialStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** A pack repository the user added (or the default one), as Discover shows it. */
data class SavedRepository(
    val id: Long,
    /** The registered index URL (always `https://`, checked by `RepositoryLinks` when it was added). */
    val url: String,
    /** The index's name from the last successful fetch, or null before the first one. */
    val name: String?,
    val isDefault: Boolean,
    val enabled: Boolean,
    val authType: RepositoryAuthType,
)

/** An installed pack, for the "installed" and "update available" badges. */
data class InstalledRef(
    val packId: String,
    val version: String,
    val bundleId: String?,
    val bundleVersion: String?,
)

/**
 * What Discover stores on the phone: the repositories (`:core:data`), their credentials (`:core:repo`, Keystore
 * encrypted, keyed by URL) and the installed packs. No network here. [RoomDiscoverStore] is the real one; tests use a
 * fake.
 */
interface DiscoverStore {
    val repositories: Flow<List<SavedRepository>>

    val installed: Flow<List<InstalledRef>>

    /** Adds the project website's repository unless it is already there. */
    suspend fun ensureDefault()

    /** Adds a repository (and its [credentials]); returns its ID, or null when the URL is already registered. */
    suspend fun add(
        url: String,
        name: String?,
        credentials: RepositoryCredentials?,
    ): Long?

    /** The stored credentials of [url], or null (none, or they can no longer be decrypted). */
    suspend fun credentials(url: String): RepositoryCredentials?

    /** Replaces the credentials of repository [id]; null removes them. */
    suspend fun setCredentials(
        id: Long,
        credentials: RepositoryCredentials?,
    )

    suspend fun setName(
        id: Long,
        name: String,
    )

    suspend fun setEnabled(
        id: Long,
        enabled: Boolean,
    )

    /** Removes repository [id] and its credentials. Installed packs stay. */
    suspend fun remove(id: Long)
}

@Singleton
class RoomDiscoverStore
    @Inject
    constructor(
        private val registry: PackRepositoryRegistry,
        private val installedPacks: InstalledPackRepository,
        private val credentialStore: CredentialStore,
    ) : DiscoverStore {
        override val repositories: Flow<List<SavedRepository>> =
            registry.observeAll().map { rows ->
                // The default repository first, then the others in the order they were added.
                rows.sortedWith(compareBy({ !it.isDefault }, { it.addedAt }, { it.id })).map { it.toSaved() }
            }

        override val installed: Flow<List<InstalledRef>> =
            installedPacks.observeAll().map { rows ->
                rows.map { InstalledRef(it.packId, it.version, it.bundleId, it.bundleVersion) }
            }

        override suspend fun ensureDefault() {
            if (registry.getByUrl(DEFAULT_REPOSITORY_URL) == null) {
                registry.add(DEFAULT_REPOSITORY_URL, isDefault = true)
            }
        }

        override suspend fun add(
            url: String,
            name: String?,
            credentials: RepositoryCredentials?,
        ): Long? {
            val id = registry.add(url, name, authTypeOf(credentials)) ?: return null
            if (credentials != null) credentialStore.put(url, credentials)
            return id
        }

        override suspend fun credentials(url: String): RepositoryCredentials? = credentialStore.get(url)

        override suspend fun setCredentials(
            id: Long,
            credentials: RepositoryCredentials?,
        ) {
            val row = row(id) ?: return
            if (credentials == null) credentialStore.remove(row.url) else credentialStore.put(row.url, credentials)
            registry.update(row.copy(authType = authTypeOf(credentials)))
        }

        override suspend fun setName(
            id: Long,
            name: String,
        ) {
            val row = row(id) ?: return
            if (row.name != name) registry.update(row.copy(name = name))
        }

        override suspend fun setEnabled(
            id: Long,
            enabled: Boolean,
        ) {
            val row = row(id) ?: return
            if (row.enabled != enabled) registry.update(row.copy(enabled = enabled))
        }

        override suspend fun remove(id: Long) {
            val row = row(id) ?: return
            registry.remove(id)
            credentialStore.remove(row.url)
        }

        private suspend fun row(id: Long): PackRepositoryEntity? =
            registry.observeAll().first().firstOrNull {
                it.id ==
                    id
            }

        private fun PackRepositoryEntity.toSaved() = SavedRepository(id, url, name, isDefault, enabled, authType)
    }

/** The `authType` column for [credentials] (backup schema `repository.auth.type`). */
internal fun authTypeOf(credentials: RepositoryCredentials?): RepositoryAuthType =
    when (credentials) {
        null -> RepositoryAuthType.NONE
        is RepositoryCredentials.Basic -> RepositoryAuthType.BASIC
        is RepositoryCredentials.Bearer -> RepositoryAuthType.BEARER
    }
