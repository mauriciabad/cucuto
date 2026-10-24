package app.mauri.cucuto.packs

import android.content.Context
import app.mauri.cucuto.core.data.db.AssignmentEntity
import app.mauri.cucuto.core.data.db.ContactWithKeys
import app.mauri.cucuto.core.data.db.InstalledPackEntity
import app.mauri.cucuto.core.data.repository.AssignmentRepository
import app.mauri.cucuto.core.data.repository.ContactRepository
import app.mauri.cucuto.core.data.repository.InstalledPackRepository
import app.mauri.cucuto.core.pack.ImportEvent
import app.mauri.cucuto.core.pack.ImportResult
import app.mauri.cucuto.core.pack.ImportSource
import app.mauri.cucuto.core.pack.PackImporter
import app.mauri.cucuto.core.pack.PackSelection
import app.mauri.cucuto.core.pack.PackStore
import app.mauri.cucuto.core.pack.PreparedImport
import app.mauri.cucuto.packspec.ErrorCode
import app.mauri.cucuto.packspec.PackManifest
import app.mauri.cucuto.packspec.SpecJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Glue between the importer (`:core:pack`) and the database (`:core:data`): every pack the importer activates is
 * recorded in `installed_packs`, which is what the routing table reads (ADR 0009), so an import immediately makes
 * its characters assignable and routable. The Packs screen and repository downloads (M5) go through here.
 */
@Singleton
class PackLibrary
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val installedPacks: InstalledPackRepository,
        private val assignments: AssignmentRepository,
        private val contacts: ContactRepository,
        private val autoHaptics: AutoHapticsRunner,
    ) : PackCatalog {
        /** Analyses auto haptics before installing, so each version is committed with its clip envelopes. */
        private val importer: PackImporter by lazy { PackImporter.create(context, autoHaptics.packAnalyzer) }

        /** Discards and cleanup outlive the screen that started them. */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Leftovers of imports interrupted by process death go before the first import of this process. */
        private val leftoversDeleted = scope.async { runCatching { importer.deleteLeftovers() } }

        private val manifests = ConcurrentHashMap<String, PackManifest>()

        @OptIn(ExperimentalCoroutinesApi::class)
        override val installed: Flow<List<LibraryPack>> =
            combine(installedPacks.observeAll(), assignments.observeAll(), contacts.observeContacts()) { rows, a, c ->
                Triple(rows, a, c)
            }.mapLatest { (rows, assigned, people) -> rows.map { libraryPack(it, assigned, people) } }
                .flowOn(Dispatchers.IO)

        override fun prepare(source: ImportSource): Flow<ImportStep> = prepareFrom(source, origin = null)

        override fun prepare(
            source: ImportSource,
            origin: PackOrigin,
        ): Flow<ImportStep> = prepareFrom(source, origin)

        private fun prepareFrom(
            source: ImportSource,
            origin: PackOrigin?,
        ): Flow<ImportStep> =
            flow {
                leftoversDeleted.await()
                emitAll(importer.prepare(source))
            }.map { event ->
                when (event) {
                    is ImportEvent.Progress -> ImportStep.Working(event.progress)
                    is ImportEvent.Finished -> stepFor(event.result, source, origin)
                }
            }

        override fun install(
            review: ImportReview,
            selection: PackSelection,
        ): Flow<ImportStep> {
            val import = review.handle as PreparedImport
            val origin = review.origin ?: PackOrigin.Local(import.displayName)
            return install(import, selection, origin).map { event ->
                when (event) {
                    is ImportEvent.Progress -> ImportStep.Working(event.progress)
                    is ImportEvent.Finished -> stepFor(event.result, source = null, origin = null)
                }
            }
        }

        override fun discard(review: ImportReview) {
            val import = review.handle as? PreparedImport ?: return
            scope.launch { discard(import) }
        }

        override suspend fun remove(
            packId: String,
            clearAssignments: Boolean,
        ) {
            // The database first: the routing table drops the pack (its contacts fall back to WhatsApp's tone)
            // before its files disappear, so the clip provider is never asked for a deleted clip.
            installedPacks.remove(packId)
            if (clearAssignments) {
                assignments.observeByPack(packId).first().forEach { assignments.unassign(it.contactId) }
            }
            importer.uninstall(packId)
            manifests.keys.removeAll { it.startsWith("$packId\u0000") }
        }

        /**
         * Installs [selection] from [import] and records the packs that became active.
         *
         * @param origin Where the file came from, for backups (`packReference.source`).
         */
        fun install(
            import: PreparedImport,
            selection: PackSelection = PackSelection.default(import),
            origin: PackOrigin = PackOrigin.Local(import.displayName),
        ): Flow<ImportEvent> =
            importer.install(import, selection).onEach { event ->
                val result = (event as? ImportEvent.Finished)?.result
                if (result is ImportResult.Installed) record(import, result, origin)
            }

        suspend fun discard(import: PreparedImport) = importer.discard(import)

        private suspend fun stepFor(
            result: ImportResult,
            source: ImportSource?,
            origin: PackOrigin?,
        ): ImportStep =
            when (result) {
                is ImportResult.Ready -> {
                    ImportStep.Ready(review(result.import, origin))
                }

                is ImportResult.Invalid -> {
                    val notAPack = result.report.errors.any { it.code == ErrorCode.MANIFEST_MISSING }
                    val detected = if (notAPack && source != null) detect(source) else DetectedFile.UNKNOWN
                    ImportStep.Invalid(result.report, detected)
                }

                is ImportResult.Failed -> {
                    ImportStep.Failed(result.reason, result.message)
                }

                is ImportResult.Installed -> {
                    ImportStep.Installed(result.packs, result.skipped)
                }
            }

        private suspend fun review(
            import: PreparedImport,
            origin: PackOrigin?,
        ): ImportReview {
            val packs =
                import.packs.map { candidate ->
                    val folder = import.stagedFolder(candidate)
                    val keep =
                        candidate.manifest.characters
                            .map { it.id }
                            .toSet()
                    val orphaned =
                        if (candidate.installedVersion == null) {
                            0
                        } else {
                            assignments.observeByPack(candidate.id).first().count { it.characterId !in keep }
                        }
                    ReviewPack(candidate, characters(candidate.manifest, folder), orphaned)
                }
            val versionNotes = setOf(ErrorCode.PACK_SAME_VERSION, ErrorCode.PACK_DOWNGRADE)
            return ImportReview(
                fileName = import.displayName,
                bundle = import.bundle,
                packs = packs,
                warnings = import.report.warnings.filter { it.code !in versionNotes },
                handle = import,
                origin = origin,
            )
        }

        /** Peeks at the zip root for a backup manifest (only when the file is not a pack, so rarely). */
        private suspend fun detect(source: ImportSource): DetectedFile =
            withContext(Dispatchers.IO) {
                runCatching {
                    val names = mutableSetOf<String>()
                    ZipInputStream(source.open().buffered()).use { zip ->
                        repeat(MAX_SNIFFED_ENTRIES) {
                            val entry = zip.nextEntry ?: return@use
                            names += entry.name
                        }
                    }
                    when {
                        "backup.json" in names -> DetectedFile.BACKUP
                        "envelope.json" in names && "payload.bin" in names -> DetectedFile.BACKUP
                        else -> DetectedFile.UNKNOWN
                    }
                }.getOrDefault(DetectedFile.UNKNOWN)
            }

        private fun libraryPack(
            row: InstalledPackEntity,
            assigned: List<AssignmentEntity>,
            people: List<ContactWithKeys>,
        ): LibraryPack {
            val folder = File(row.path)
            val manifest = manifest(row, folder)
            val byId = people.associateBy { it.contact.id }
            val users =
                assigned.filter { it.packId == row.packId }.map { assignment ->
                    AssignedContact(
                        assignment.contactId,
                        byId[assignment.contactId]?.bestName(),
                        assignment.characterId,
                    )
                }
            return LibraryPack(
                id = row.packId,
                name = manifest?.name ?: row.name,
                version = row.version,
                author = manifest?.author,
                license = manifest?.license,
                description = manifest?.description,
                cover = manifest?.cover?.let { File(folder, it) },
                characters = manifest?.let { characters(it, folder) }.orEmpty(),
                sizeBytes = row.sizeBytes,
                source = row.source,
                bundleId = row.bundleId,
                contacts = users,
                filesMissing = manifest == null,
            )
        }

        private fun manifest(
            row: InstalledPackEntity,
            folder: File,
        ): PackManifest? {
            val key = "${row.packId}\u0000${row.version}\u0000${row.path}"
            manifests[key]?.let { return it }
            val file = File(folder, PackStore.MANIFEST)
            val parsed =
                runCatching { SpecJson.decodeFromString<PackManifest>(file.readText()) }.getOrNull() ?: return null
            manifests[key] = parsed
            return parsed
        }

        private suspend fun record(
            import: PreparedImport,
            result: ImportResult.Installed,
            origin: PackOrigin,
        ) {
            val now = System.currentTimeMillis()
            for (pack in result.packs) {
                if (!pack.active) continue
                val candidate = import.packs.firstOrNull { it.id == pack.id && it.version == pack.version }
                // A reinstall of the same version reuses the folder: forget what was read from the old files.
                autoHaptics.installed(pack.directory.path)
                installedPacks.recordInstalled(
                    InstalledPackEntity(
                        packId = pack.id,
                        version = pack.version,
                        name = candidate?.manifest?.name ?: pack.id,
                        path = pack.directory.path,
                        sha256 = import.archiveSha256,
                        sizeBytes = import.archiveSizeBytes,
                        source = origin.source,
                        repositoryUrl = (origin as? PackOrigin.Repository)?.url,
                        downloadUrl = (origin as? PackOrigin.Url)?.url,
                        fileName = (origin as? PackOrigin.Local)?.fileName,
                        bundleId = import.bundle?.id,
                        bundleName = import.bundle?.name,
                        bundleVersion = import.bundle?.version,
                        installedAt = now,
                    ),
                )
            }
        }

        private companion object {
            /** Backups write their manifest first; a few entries are enough to recognise one. */
            const val MAX_SNIFFED_ENTRIES = 64
        }
    }

/** The characters of [manifest] with their files resolved against [folder] (installed or staged). */
internal fun characters(
    manifest: PackManifest,
    folder: File?,
): List<LibraryCharacter> =
    manifest.characters.map { c ->
        LibraryCharacter(
            id = c.id,
            name = c.name,
            sprite = folder?.let { File(it, c.sprite) },
            seedColor = parseSeedColor(c.seedColor),
            clips = if (folder == null) emptyList() else c.clips.map { File(folder, it.file) },
            ringtoneCount = c.ringtones.size,
        )
    }

/** `#RRGGBB` (the only form the schema allows) to opaque ARGB; null for anything else. */
internal fun parseSeedColor(hex: String?): Int? {
    if (hex == null || hex.length != 7 || hex[0] != '#') return null
    val rgb = hex.substring(1).toIntOrNull(16) ?: return null
    return (0xFF shl 24) or rgb
}

private fun ContactWithKeys.bestName(): String? =
    contact.displayName?.takeIf { it.isNotBlank() }
        ?: contact.androidDisplayName?.takeIf { it.isNotBlank() }
        ?: keys.firstNotNullOfOrNull { key -> key.displayName?.takeIf { it.isNotBlank() } }
