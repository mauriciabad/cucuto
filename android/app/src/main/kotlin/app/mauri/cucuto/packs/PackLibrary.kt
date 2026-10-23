package app.mauri.cucuto.packs

import android.content.Context
import app.mauri.cucuto.core.data.db.InstalledPackEntity
import app.mauri.cucuto.core.data.db.PackSource
import app.mauri.cucuto.core.data.repository.InstalledPackRepository
import app.mauri.cucuto.core.pack.ImportEvent
import app.mauri.cucuto.core.pack.ImportResult
import app.mauri.cucuto.core.pack.ImportSource
import app.mauri.cucuto.core.pack.PackImporter
import app.mauri.cucuto.core.pack.PackSelection
import app.mauri.cucuto.core.pack.PreparedImport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Glue between the importer (`:core:pack`) and the database (`:core:data`): every pack the importer activates is
 * recorded in `installed_packs`, which is what the routing table reads (ADR 0009), so an import immediately makes
 * its characters assignable and routable. The import UI (M3) and repository downloads (M5) go through here.
 */
@Singleton
class PackLibrary
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val installedPacks: InstalledPackRepository,
    ) {
        private val importer: PackImporter by lazy { PackImporter.create(context) }

        fun prepare(source: ImportSource): Flow<ImportEvent> = importer.prepare(source)

        /**
         * Installs [selection] from [import] and records the packs that became active.
         *
         * @param origin Where the file came from, for backups (`packReference.source`).
         */
        fun install(
            import: PreparedImport,
            selection: PackSelection = PackSelection.default(import),
            origin: Origin = Origin.Local(import.displayName),
        ): Flow<ImportEvent> =
            importer.install(import, selection).onEach { event ->
                val result = (event as? ImportEvent.Finished)?.result
                if (result is ImportResult.Installed) record(import, result, origin)
            }

        suspend fun discard(import: PreparedImport) = importer.discard(import)

        private suspend fun record(
            import: PreparedImport,
            result: ImportResult.Installed,
            origin: Origin,
        ) {
            val now = System.currentTimeMillis()
            for (pack in result.packs) {
                if (!pack.active) continue
                val candidate = import.packs.firstOrNull { it.id == pack.id && it.version == pack.version }
                installedPacks.recordInstalled(
                    InstalledPackEntity(
                        packId = pack.id,
                        version = pack.version,
                        name = candidate?.manifest?.name ?: pack.id,
                        path = pack.directory.path,
                        sha256 = import.archiveSha256,
                        sizeBytes = import.archiveSizeBytes,
                        source = origin.source,
                        repositoryUrl = (origin as? Origin.Repository)?.url,
                        downloadUrl = (origin as? Origin.Url)?.url,
                        fileName = (origin as? Origin.Local)?.fileName,
                        bundleId = import.bundle?.id,
                        bundleName = import.bundle?.name,
                        bundleVersion = import.bundle?.version,
                        installedAt = now,
                    ),
                )
            }
        }

        /** Where an imported file came from. */
        sealed interface Origin {
            val source: PackSource

            data class Local(
                val fileName: String?,
            ) : Origin {
                override val source = PackSource.LOCAL
            }

            data class Url(
                val url: String,
            ) : Origin {
                override val source = PackSource.URL
            }

            data class Repository(
                val url: String,
            ) : Origin {
                override val source = PackSource.REPOSITORY
            }
        }
    }
