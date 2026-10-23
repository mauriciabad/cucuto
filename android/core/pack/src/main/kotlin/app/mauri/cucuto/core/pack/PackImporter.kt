package app.mauri.cucuto.core.pack

import android.content.Context
import app.mauri.cucuto.core.pack.media.MediaVerifier
import app.mauri.cucuto.core.pack.media.PlatformAudioProbe
import app.mauri.cucuto.core.pack.media.PlatformImageProbe
import app.mauri.cucuto.packspec.BundleManifest
import app.mauri.cucuto.packspec.ErrorCode
import app.mauri.cucuto.packspec.FileFormat
import app.mauri.cucuto.packspec.FileInput
import app.mauri.cucuto.packspec.FileKind
import app.mauri.cucuto.packspec.Issue
import app.mauri.cucuto.packspec.JvmPackIo
import app.mauri.cucuto.packspec.Limits
import app.mauri.cucuto.packspec.PackContent
import app.mauri.cucuto.packspec.PackEntry
import app.mauri.cucuto.packspec.PackPaths
import app.mauri.cucuto.packspec.PackReadException
import app.mauri.cucuto.packspec.PackValidator
import app.mauri.cucuto.packspec.SemVer
import app.mauri.cucuto.packspec.Severity
import app.mauri.cucuto.packspec.ValidationReport
import app.mauri.cucuto.packspec.mediaReferences
import app.mauri.cucuto.packspec.zip.ZipArchive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Imports packs and bundles (`<name>.soundpack.zip`) from any [ImportSource] (docs/decisions/0008-pack-importer.md).
 * Names and MIME types are never trusted: any zip works, and `pack.json` or `bundle.json` at its root decides what
 * it is. Pickers and intent filters ask for [MIME_TYPE].
 *
 * 1. [prepare] copies the file into the app, validates it with the shared pack-spec rules (archive, layout, manifests,
 *    references, media headers), unpacks the files to install into a staging folder and test-decodes every clip,
 *    ringtone and image with the platform decoders. It ends with [ImportResult.Ready], [ImportResult.Invalid] or
 *    [ImportResult.Failed].
 * 2. The UI shows the packs, warnings and what installing does ([PackCandidate.action]), and the user picks packs of a
 *    bundle and confirms replacements and downgrades ([PackSelection]).
 * 3. [install] runs the [analyzer] on the chosen packs (auto haptics), moves them into [PackStore] with renames and
 *    switches them to the new version, or [discard] drops everything.
 *
 * All IO runs on [ioDispatcher]; the flows are cold and safe to collect from the main thread. Cancelling a flow
 * deletes what it unpacked.
 */
class PackImporter(
    private val store: PackStore,
    /** Scratch folder for the copied archive (in the cache). */
    private val workDir: File,
    private val verifier: MediaVerifier,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val validator: PackValidator = PackValidator(),
    /** Extra work on each pack before it is installed; see [PackAnalyzer]. */
    private val analyzer: PackAnalyzer? = null,
) {
    /** Serializes changes to [store]. */
    private val installLock = Mutex()

    /** Validates and unpacks [source]. See the class documentation. */
    fun prepare(source: ImportSource): Flow<ImportEvent> =
        flow {
            val result = Preparation(source) { emit(ImportEvent.Progress(it)) }.run()
            emit(ImportEvent.Finished(result))
        }.flowOn(ioDispatcher)

    /**
     * Installs the packs of [import] chosen in [selection]. With [activate] (the default) each pack switches to the
     * new version at once and its other versions are deleted; without it, a pack that was already installed keeps its
     * active version until [PackStore.activate] is called (for example after asking what to do with contacts of a
     * character the update removes). The import is used up either way.
     */
    fun install(
        import: PreparedImport,
        selection: PackSelection = PackSelection.default(import),
        activate: Boolean = true,
    ): Flow<ImportEvent> =
        flow {
            if (!import.claim()) {
                val message = "This import was already installed or discarded"
                emit(ImportEvent.Finished(ImportResult.Failed(FailureReason.NOT_PREPARED, message)))
                return@flow
            }
            val progress: suspend (ImportProgress) -> Unit = { emit(ImportEvent.Progress(it)) }
            val result =
                try {
                    // Outside the lock: the staging folders belong to this import, and analysis can take a while.
                    analyze(import, selection, progress)
                    installLock.withLock { installSelected(import, selection, activate, progress) }
                } catch (e: IOException) {
                    ImportResult.Failed(FailureReason.STORAGE_ERROR, "Could not install: ${e.message}", e)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ImportResult.Failed(FailureReason.UNEXPECTED, "Install failed: $e", e)
                } finally {
                    withContext(NonCancellable) { store.deleteStaging(import.stagingDir) }
                }
            emit(ImportEvent.Finished(result))
        }.flowOn(ioDispatcher)

    /** Drops a prepared import and its unpacked files. Does nothing if it was already installed or discarded. */
    suspend fun discard(import: PreparedImport) {
        if (import.claim()) withContext(ioDispatcher + NonCancellable) { store.deleteStaging(import.stagingDir) }
    }

    /**
     * Removes every version of the pack [id] from the store. Serialized with [install], so a removal never races an
     * import of the same pack. Returns false when it was not installed.
     */
    suspend fun uninstall(id: String): Boolean =
        withContext(ioDispatcher) { installLock.withLock { store.uninstall(id) } }

    /** Deletes leftovers of imports interrupted by process death. Call once at startup, before any import. */
    suspend fun deleteLeftovers() =
        withContext(ioDispatcher) {
            store.deleteLeftovers()
            workDir.deleteRecursively()
            Unit
        }

    /**
     * Runs the [analyzer] on the staged packs that [installSelected] will most likely install (chosen, and confirmed
     * when they need it). Never fails: a pack whose analysis throws is installed without it.
     */
    private suspend fun analyze(
        import: PreparedImport,
        selection: PackSelection,
        progress: suspend (ImportProgress) -> Unit,
    ) {
        val analyzer = analyzer ?: return
        val chosen =
            import.packs.filter {
                it.id in selection.selected && (!it.action.needsConfirmation || it.id in selection.confirmed)
            }
        for (candidate in chosen) {
            val staged = import.stagedPacks[candidate.root] ?: continue
            try {
                analyzer.analyze(staged, candidate.manifest) { done, total ->
                    progress(ImportProgress.Analyzing(candidate.id, done, total))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The pack installs without the analysis; the app redoes missing analyses later (ADR 0020).
            }
        }
    }

    private suspend fun installSelected(
        import: PreparedImport,
        selection: PackSelection,
        activate: Boolean,
        progress: suspend (ImportProgress) -> Unit,
    ): ImportResult {
        val installed = mutableListOf<InstalledPack>()
        val skipped = mutableListOf<PackCandidate>()
        val chosen = import.packs.filter { it.id in selection.selected }
        chosen.forEachIndexed { i, prepared ->
            progress(ImportProgress.Installing(i, chosen.size))
            // The store may have changed since prepare(): decide again.
            val previous = store.activeVersion(prepared.id)
            val candidate = prepared.copy(action = actionFor(prepared.version, previous), installedVersion = previous)
            if (candidate.action.needsConfirmation && candidate.id !in selection.confirmed) {
                skipped += candidate
                return@forEachIndexed
            }
            val staged = import.stagedPacks.getValue(candidate.root)
            val dir = store.commit(staged, candidate.id, candidate.version)
            if (activate) store.activate(candidate.id, candidate.version)
            installed +=
                InstalledPack(
                    id = candidate.id,
                    version = candidate.version,
                    directory = dir,
                    previousVersion = previous,
                    active = store.activeVersion(candidate.id) == candidate.version,
                )
        }
        progress(ImportProgress.Installing(chosen.size, chosen.size))
        return ImportResult.Installed(installed, skipped)
    }

    /** The files of one pack to unpack: archive path to path inside the installed folder. */
    private class PackPlan(
        val entry: PackEntry,
        val files: Map<String, String>,
        val bytes: Long,
    )

    private class SourceTooLargeException : IOException()

    private class SourceReadException(
        cause: Throwable,
    ) : IOException(cause)

    /** One run of [prepare]. */
    private inner class Preparation(
        private val source: ImportSource,
        private val progress: suspend (ImportProgress) -> Unit,
    ) {
        private val archive = File(workDir, "${UUID.randomUUID()}.zip")
        private var stagingDir: File? = null

        /** SHA-256 (lowercase hex) and size of the source, computed while copying it. */
        private var archiveSha256 = ""
        private var archiveSize = 0L

        suspend fun run(): ImportResult {
            var ready = false
            try {
                return prepare().also { ready = it is ImportResult.Ready }
            } catch (e: SourceTooLargeException) {
                return ImportResult.Failed(FailureReason.SOURCE_TOO_LARGE, "Larger than any pack or bundle can be")
            } catch (e: SourceReadException) {
                val message = "Could not read the file: ${e.cause?.message}"
                return ImportResult.Failed(FailureReason.SOURCE_UNREADABLE, message, e.cause)
            } catch (e: SecurityException) {
                return ImportResult.Failed(FailureReason.SOURCE_UNREADABLE, "No permission to read the file", e)
            } catch (e: IOException) {
                val message = "Could not write to app storage: ${e.message}"
                return ImportResult.Failed(FailureReason.STORAGE_ERROR, message, e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A bug or an unexpected platform error: report it instead of crashing the screen that collects.
                return ImportResult.Failed(FailureReason.UNEXPECTED, "Import failed: $e", e)
            } finally {
                withContext(NonCancellable) {
                    archive.delete()
                    if (!ready) stagingDir?.let(store::deleteStaging)
                }
            }
        }

        private suspend fun prepare(): ImportResult {
            val description =
                try {
                    source.describe()
                } catch (e: Exception) {
                    ImportSource.Description(null, null)
                }
            copy(description.sizeBytes)

            progress(ImportProgress.Validating)
            val validated: ValidationReport
            val plans: List<PackPlan>
            val bundle: BundleManifest?
            FileInput(archive.toPath()).use { input ->
                val opened = ZipArchive.open(input, JvmPackIo.inflater)
                val zip = opened.source
                if (zip == null || opened.issues.any { it.severity == Severity.ERROR }) {
                    return ImportResult.Invalid(ValidationReport(opened.issues))
                }
                val validation = validator.validate(zip, opened.issues)
                val content = validation.content
                if (!validation.report.isValid || content == null) return ImportResult.Invalid(validation.report)
                validated = validation.report
                bundle = (content as? PackContent.Bundle)?.manifest
                plans = content.packs.map { plan(zip, it) }

                val needed = plans.sumOf { it.bytes }
                if (!store.root.isDirectory) store.root.mkdirs()
                if (store.root.usableSpace < needed + FREE_SPACE_MARGIN_BYTES) {
                    return ImportResult.Failed(
                        FailureReason.NOT_ENOUGH_STORAGE,
                        "Needs ${needed + FREE_SPACE_MARGIN_BYTES} bytes free; ${store.root.usableSpace} are available",
                    )
                }
                val extractIssues = extract(zip, plans)
                if (extractIssues.isNotEmpty()) {
                    return ImportResult.Invalid(ValidationReport(validated.issues + extractIssues))
                }
            }
            archive.delete()

            val decodeIssues = decode(plans)
            if (decodeIssues.any { it.severity == Severity.ERROR }) {
                return ImportResult.Invalid(ValidationReport(validated.issues + decodeIssues))
            }

            val candidates =
                plans.map { plan ->
                    val m = plan.entry.manifest
                    val installed = store.activeVersion(m.id)
                    PackCandidate(
                        root = plan.entry.root,
                        manifest = m,
                        action = actionFor(m.version, installed),
                        installedVersion = installed,
                        sizeBytes = plan.bytes,
                        fileCount = plan.files.size,
                    )
                }
            val runtimeWarnings =
                candidates.mapNotNull { c ->
                    val manifestFile = PackPaths.join(c.root, PackStore.MANIFEST)
                    when (c.action) {
                        InstallAction.REINSTALL -> {
                            Issue(
                                ErrorCode.PACK_SAME_VERSION,
                                "${c.id} ${c.version} is already installed",
                                file = manifestFile,
                                pointer = "/version",
                            )
                        }

                        InstallAction.DOWNGRADE -> {
                            Issue(
                                ErrorCode.PACK_DOWNGRADE,
                                "${c.id} ${c.installedVersion} is installed; this file has the lower version ${c.version}",
                                file = manifestFile,
                                pointer = "/version",
                            )
                        }

                        else -> {
                            null
                        }
                    }
                }
            val staged = plans.indices.associate { i -> plans[i].entry.root to File(stagingDir!!, i.toString()) }
            return ImportResult.Ready(
                PreparedImport(
                    displayName = description.displayName,
                    bundle = bundle,
                    packs = candidates,
                    report = ValidationReport(validated.issues + decodeIssues + runtimeWarnings),
                    archiveSha256 = archiveSha256,
                    archiveSizeBytes = archiveSize,
                    stagingDir = stagingDir!!,
                    stagedPacks = staged,
                ),
            )
        }

        /** Copies the source to [archive], so the zip can be read at random and cannot change underneath. */
        private suspend fun copy(sizeHint: Long?) {
            if (sizeHint != null && sizeHint > MAX_ARCHIVE_BYTES) throw SourceTooLargeException()
            if (!workDir.isDirectory && !workDir.mkdirs()) throw IOException("Cannot create $workDir")
            val input: InputStream =
                try {
                    source.open()
                } catch (e: SecurityException) {
                    throw e
                } catch (e: Exception) {
                    throw SourceReadException(e)
                }
            var copied = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            input.use {
                FileOutputStream(archive).use { out ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var reported = 0L
                    progress(ImportProgress.Copying(0, sizeHint))
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n =
                            try {
                                input.read(buffer)
                            } catch (e: IOException) {
                                throw SourceReadException(e)
                            }
                        if (n < 0) break
                        copied += n
                        if (copied > MAX_ARCHIVE_BYTES) throw SourceTooLargeException()
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        if (copied - reported >= PROGRESS_STEP_BYTES) {
                            reported = copied
                            progress(ImportProgress.Copying(copied, sizeHint))
                        }
                    }
                }
            }
            archiveSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            archiveSize = copied
            progress(ImportProgress.Copying(copied, copied))
        }

        /** The manifest, every referenced media file and the text files (licenses) of a pack. */
        private fun plan(
            zip: ZipArchive,
            entry: PackEntry,
        ): PackPlan {
            val prefix = if (entry.root.isEmpty()) "" else entry.root + "/"
            val paths = linkedSetOf(PackPaths.join(entry.root, PackStore.MANIFEST))
            entry.manifest.mediaReferences().mapTo(paths) { PackPaths.join(entry.root, it.path) }
            zip.entries
                .filter { it.path.startsWith(prefix) && FileFormat.of(it.path)?.kind == FileKind.TEXT }
                .filterNot { PackPaths.isUnsafe(it.path) || PackPaths.isIgnored(it.path) }
                .filterNot { PackPaths.isInvalidName(it.path) }
                .mapTo(paths) { it.path }
            val sizes = zip.entries.associate { it.path to it.size }
            return PackPlan(entry, paths.associateWith { it.removePrefix(prefix) }, paths.sumOf { sizes[it] ?: 0L })
        }

        /** Unpacks every planned file under the staging folder. Returns read errors as issues. */
        private suspend fun extract(
            zip: ZipArchive,
            plans: List<PackPlan>,
        ): List<Issue> {
            val staging = store.newStagingDir().also { stagingDir = it }
            val total = plans.sumOf { it.files.size }
            var done = 0
            plans.forEachIndexed { i, plan ->
                val packDir = File(staging, i.toString())
                val packCanonical = packDir.canonicalFile.toPath()
                for ((archivePath, relative) in plan.files) {
                    currentCoroutineContext().ensureActive()
                    progress(ImportProgress.Extracting(done++, total))
                    val format = FileFormat.of(archivePath) ?: continue
                    val target = File(packDir, relative)
                    // Defense in depth: pack-spec already rejected unsafe paths (zip slip).
                    if (!target.canonicalFile.toPath().startsWith(packCanonical)) {
                        return listOf(Issue(ErrorCode.UNSAFE_PATH, "Path leaves the pack folder", file = archivePath))
                    }
                    val bytes =
                        try {
                            zip.read(archivePath, format.maxBytes)
                        } catch (e: PackReadException) {
                            return listOf(Issue(e.code, e.message ?: "Unreadable", file = archivePath))
                        }
                    val parent = target.parentFile
                    if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                        throw IOException("Cannot create $parent")
                    }
                    FileOutputStream(target).use { out ->
                        out.write(bytes)
                        out.fd.sync()
                    }
                }
            }
            progress(ImportProgress.Extracting(total, total))
            return emptyList()
        }

        /** Test-decodes every media file of every pack from the staging folder. */
        private suspend fun decode(plans: List<PackPlan>): List<Issue> {
            val jobs =
                plans.flatMapIndexed { i, plan ->
                    val manifestFile = PackPaths.join(plan.entry.root, PackStore.MANIFEST)
                    plan.entry.manifest.mediaReferences().groupBy { it.path }.map { (relative, refs) ->
                        val archivePath = PackPaths.join(plan.entry.root, relative)
                        DecodeJob(File(File(stagingDir!!, i.toString()), relative), archivePath, refs, manifestFile)
                    }
                }
            val issues = mutableListOf<Issue>()
            jobs.forEachIndexed { i, job ->
                currentCoroutineContext().ensureActive()
                progress(ImportProgress.Decoding(i, jobs.size))
                val format = FileFormat.of(job.archivePath) ?: return@forEachIndexed
                issues += verifier.verify(job.file, job.archivePath, format, job.references, job.manifestFile)
            }
            progress(ImportProgress.Decoding(jobs.size, jobs.size))
            return issues
        }
    }

    private class DecodeJob(
        val file: File,
        val archivePath: String,
        val references: List<app.mauri.cucuto.packspec.MediaReference>,
        val manifestFile: String,
    )

    companion object {
        /** No valid archive is larger than the unpacked bundle limit plus zip overhead. */
        const val MAX_ARCHIVE_BYTES = Limits.BUNDLE_UNPACKED_BYTES + 64 * Limits.MiB

        /** MIME type for document pickers and intent filters: packs, bundles and backups are plain zips. */
        const val MIME_TYPE = "application/zip"
        private const val FREE_SPACE_MARGIN_BYTES = 32 * Limits.MiB
        private const val COPY_BUFFER_BYTES = 256 * 1024
        private const val PROGRESS_STEP_BYTES = Limits.MiB

        /** The importer of the app: packs in `files/packs/`, scratch files in the cache, platform decoders. */
        fun create(
            context: Context,
            analyzer: PackAnalyzer? = null,
        ): PackImporter =
            PackImporter(
                store = PackStore.inFilesDir(context.filesDir),
                workDir = File(context.cacheDir, "pack-import"),
                verifier = MediaVerifier(PlatformAudioProbe(), PlatformImageProbe()),
                analyzer = analyzer,
            )

        /** What installing [version] does when [installedVersion] is active (null: not installed). */
        fun actionFor(
            version: String,
            installedVersion: String?,
        ): InstallAction {
            if (installedVersion == null) return InstallAction.INSTALL
            val new = SemVer.parse(version)
            val old = SemVer.parse(installedVersion)
            val cmp = if (new != null && old != null) new.compareTo(old) else version.compareTo(installedVersion)
            return when {
                cmp > 0 -> InstallAction.UPDATE
                cmp == 0 -> InstallAction.REINSTALL
                else -> InstallAction.DOWNGRADE
            }
        }
    }
}
