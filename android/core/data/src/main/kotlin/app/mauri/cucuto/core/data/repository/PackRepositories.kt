package app.mauri.cucuto.core.data.repository

import app.mauri.cucuto.core.data.db.InstalledPackDao
import app.mauri.cucuto.core.data.db.InstalledPackEntity
import app.mauri.cucuto.core.data.db.PackRepositoryDao
import app.mauri.cucuto.core.data.db.PackRepositoryEntity
import app.mauri.cucuto.core.data.db.RepositoryAuthType
import app.mauri.cucuto.core.data.di.WallClock
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** The installed packs index. The importer records installs here; the routing table follows automatically. */
@Singleton
class InstalledPackRepository
    @Inject
    constructor(
        private val dao: InstalledPackDao,
    ) {
        fun observeAll(): Flow<List<InstalledPackEntity>> = dao.observeAll()

        suspend fun get(packId: String): InstalledPackEntity? = dao.get(packId)

        /** Records an install or an update (one row per pack ID; the newest install wins). */
        suspend fun recordInstalled(pack: InstalledPackEntity) = dao.upsert(pack)

        suspend fun remove(packId: String) = dao.delete(packId)
    }

/**
 * The pack repositories the user added (named "registry" to avoid "repository repository"). Credentials live in
 * `:core:repo`, encrypted, keyed by URL.
 */
@Singleton
class PackRepositoryRegistry
    @Inject
    constructor(
        private val dao: PackRepositoryDao,
        private val clock: WallClock,
    ) {
        fun observeAll(): Flow<List<PackRepositoryEntity>> = dao.observeAll()

        suspend fun getByUrl(url: String): PackRepositoryEntity? = dao.getByUrl(url)

        /** Adds a repository; returns its ID, or null if the URL is already registered. */
        suspend fun add(
            url: String,
            name: String? = null,
            authType: RepositoryAuthType = RepositoryAuthType.NONE,
            isDefault: Boolean = false,
        ): Long? {
            val id =
                dao.insert(
                    PackRepositoryEntity(
                        url = url,
                        name = name,
                        isDefault = isDefault,
                        authType = authType,
                        addedAt = clock.now(),
                    ),
                )
            return id.takeIf { it != -1L }
        }

        suspend fun update(repository: PackRepositoryEntity) = dao.update(repository)

        suspend fun remove(id: Long) = dao.delete(id)
    }
