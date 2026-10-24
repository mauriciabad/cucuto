package app.mauri.cucuto.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import app.mauri.cucuto.core.backup.BackupDocument
import app.mauri.cucuto.core.backup.BackupFileNames
import app.mauri.cucuto.core.backup.BackupOptions
import app.mauri.cucuto.core.data.db.AssignmentDao
import app.mauri.cucuto.core.data.db.ContactDao
import app.mauri.cucuto.core.data.db.InstalledPackDao
import app.mauri.cucuto.core.data.db.PackRepositoryDao
import app.mauri.cucuto.core.data.di.ApplicationScope
import app.mauri.cucuto.core.data.di.WallClock
import app.mauri.cucuto.core.data.settings.AutoBackupSettings
import app.mauri.cucuto.core.data.settings.AutoBackupState
import app.mauri.cucuto.core.data.settings.AutoBackupTargetKind
import app.mauri.cucuto.core.data.settings.RoutingSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** What one automatic backup run did. */
sealed interface AutoBackupResult {
    data object Off : AutoBackupResult

    /** Nothing changed since the last backup, which is still in the folder: no new file (owner, #108). */
    data object Unchanged : AutoBackupResult

    data class Written(
        val fileName: String,
    ) : AutoBackupResult

    data class Failed(
        val message: String,
    ) : AutoBackupResult
}

/** What the Settings screen controls of automatic backups; [AutoBackup] is the real one. */
interface AutoBackupControl {
    val state: Flow<AutoBackupState>

    /** Keeps access to the folder picked with `ACTION_OPEN_DOCUMENT_TREE` ([treeUri]) and turns backups on. */
    suspend fun choose(treeUri: String)

    /**
     * Keeps access to one document ([documentUri], from `ACTION_CREATE_DOCUMENT` or `ACTION_OPEN_DOCUMENT`) and turns
     * backups on: every backup replaces its content (ADR 0040, for Google Drive and other cloud apps).
     */
    suspend fun chooseFile(documentUri: String)

    /** Turns automatic backups off and gives the folder or file permission back. Files already written stay. */
    suspend fun turnOff()

    suspend fun setIncludePacks(include: Boolean)

    /** Writes a backup now, even when nothing changed. */
    suspend fun backUpNow(): AutoBackupResult
}

/**
 * Automatic backups to the folder the user picked (docs/project-brief.md, "Backup and restore"; ADR 0028).
 *
 * - Written [DEBOUNCE_MS] after the last change to contacts, voices, repositories, packs or settings, and once after
 *   each app start (catching changes made just before the process died).
 * - One file per day, `cucuto-backup-YYYY-MM-DD.zip`; a later backup on the same day replaces it. The newest
 *   [BackupFileNames.AUTOMATIC_KEEP] backup files stay, other files in the folder are never touched.
 * - Or, with [AutoBackupTargetKind.FILE], one document the user picked (ADR 0040): every backup replaces its content.
 *   Google Drive offers no folders to other apps, but its provider takes files: Drive uploads the file when it can
 *   (also later, when the phone was offline) and keeps the older versions.
 * - A backup whose content equals the last one (same [fingerprint]) is not written again.
 * - Never encrypted and never with credentials: there is nobody to type a passphrase. With "Include sound packs"
 *   (default on) they are full backups, so a restore works even when a repository is gone.
 */
@Singleton
class AutoBackup
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val settings: AutoBackupSettings,
        private val gateway: AppBackupGateway,
        private val contacts: ContactDao,
        private val assignments: AssignmentDao,
        private val packs: InstalledPackDao,
        private val repositories: PackRepositoryDao,
        private val routing: RoutingSettings,
        private val clock: WallClock,
        @param:ApplicationScope private val scope: CoroutineScope,
    ) : AutoBackupControl {
        override val state: Flow<AutoBackupState> = settings.state

        private val started = AtomicBoolean(false)
        private val mutex = Mutex()

        /** Starts watching for changes. Call once from `Application.onCreate`. */
        @OptIn(FlowPreview::class)
        fun start() {
            if (!started.compareAndSet(false, true)) return
            scope.launch {
                combine(
                    settings.state.map { it.folderUri to it.includePacks },
                    contacts.observeAll(),
                    assignments.observeAll(),
                    packs.observeAll(),
                    combine(repositories.observeAll(), routing.enabled) { a, b -> a to b },
                ) { config, _, _, _, _ -> config }
                    .debounce(DEBOUNCE_MS)
                    .collect { (folder, _) -> if (folder != null) backUp() }
            }
        }

        /** Writes an automatic backup now if automatic backups are on and something changed. Never throws. */
        suspend fun backUp(force: Boolean = false): AutoBackupResult =
            mutex.withLock {
                val state = settings.state.first()
                val folder = state.folderUri ?: return@withLock AutoBackupResult.Off
                try {
                    when (state.targetKind) {
                        AutoBackupTargetKind.FOLDER -> write(Uri.parse(folder), state, force)
                        AutoBackupTargetKind.FILE -> writeFile(Uri.parse(folder), state, force)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val message = e.message ?: e.javaClass.simpleName
                    Log.w(TAG, "Automatic backup failed: $message")
                    settings.recordFailure(clock.now(), message)
                    AutoBackupResult.Failed(message)
                }
            }

        private suspend fun write(
            tree: Uri,
            state: AutoBackupState,
            force: Boolean,
        ): AutoBackupResult =
            withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                val parent =
                    DocumentsContract.buildDocumentUriUsingTree(
                        tree,
                        DocumentsContract.getTreeDocumentId(tree),
                    )
                val existing = children(tree)
                val fingerprint = fingerprint(gateway.exporter.snapshot(), state.includePacks)
                if (!force && fingerprint == state.lastFingerprint &&
                    state.lastFileName?.let { it in existing } == true
                ) {
                    return@withContext AutoBackupResult.Unchanged
                }
                val name = BackupFileNames.forDate(gateway.today())
                exportThen(state) { scratch ->
                    val target =
                        existing[name]
                            ?: DocumentsContract.createDocument(resolver, parent, BackupFileNames.MIME_TYPE, name)
                            ?: error("The folder refused a new file")
                    DocumentOverwrite.write(resolver, target, scratch)
                }
                val after = children(tree)
                for (old in BackupFileNames.toPrune(after.keys)) {
                    after[old]?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
                }
                settings.recordSuccess(clock.now(), name, fingerprint)
                AutoBackupResult.Written(name)
            }

        /** One document, rewritten each time (ADR 0040). */
        private suspend fun writeFile(
            document: Uri,
            state: AutoBackupState,
            force: Boolean,
        ): AutoBackupResult =
            withContext(Dispatchers.IO) {
                // Also proves the file is still there and reachable: a deleted file fails here, not halfway through.
                val name = displayName(document) ?: error("The backup file is gone. Choose it again.")
                val fingerprint = fingerprint(gateway.exporter.snapshot(), state.includePacks)
                if (!force && fingerprint == state.lastFingerprint) return@withContext AutoBackupResult.Unchanged
                exportThen(state) { scratch -> DocumentOverwrite.write(context.contentResolver, document, scratch) }
                settings.recordSuccess(clock.now(), name, fingerprint)
                AutoBackupResult.Written(name)
            }

        /** Exports to the cache first, so a failure never leaves a broken backup at the target, then runs [copy]. */
        private suspend fun exportThen(
            state: AutoBackupState,
            copy: (File) -> Unit,
        ) {
            val scratch = File(context.cacheDir, "backup-export/auto-${UUID.randomUUID()}.zip")
            try {
                scratch.parentFile?.mkdirs()
                scratch.outputStream().buffered().use {
                    gateway.exporter.export(it, BackupOptions(includePackFiles = state.includePacks))
                }
                copy(scratch)
            } finally {
                scratch.delete()
            }
        }

        /** The folder's files by display name. */
        private fun children(tree: Uri): Map<String, Uri> {
            val children =
                DocumentsContract.buildChildDocumentsUriUsingTree(
                    tree,
                    DocumentsContract.getTreeDocumentId(tree),
                )
            val columns =
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val found = mutableMapOf<String, Uri>()
            context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    found[name] = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                }
            } ?: error("The backup folder is not available")
            return found
        }

        override suspend fun backUpNow(): AutoBackupResult = backUp(force = true)

        override suspend fun choose(treeUri: String) = take(Uri.parse(treeUri), AutoBackupTargetKind.FOLDER)

        override suspend fun chooseFile(documentUri: String) = take(Uri.parse(documentUri), AutoBackupTargetKind.FILE)

        private suspend fun take(
            uri: Uri,
            kind: AutoBackupTargetKind,
        ) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val resolver = context.contentResolver
            withContext(Dispatchers.IO) { resolver.takePersistableUriPermission(uri, flags) }
            val previous = settings.state.first().folderUri
            if (previous != null && previous != uri.toString()) release(Uri.parse(previous))
            val name =
                when (kind) {
                    AutoBackupTargetKind.FOLDER -> {
                        folderName(uri)
                    }

                    AutoBackupTargetKind.FILE -> {
                        withContext(
                            Dispatchers.IO,
                        ) { runCatching { displayName(uri) }.getOrNull() }
                    }
                }
            settings.setFolder(uri.toString(), name, kind)
            scope.launch { backUp(force = true) }
        }

        /** The document's display name, or null when the provider no longer knows it. */
        private fun displayName(document: Uri): String? =
            context.contentResolver
                .query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }

        override suspend fun turnOff() {
            settings.state
                .first()
                .folderUri
                ?.let { release(Uri.parse(it)) }
            settings.setFolder(null, null)
        }

        override suspend fun setIncludePacks(include: Boolean) {
            settings.setIncludePacks(include)
        }

        private fun release(tree: Uri) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.releasePersistableUriPermission(tree, flags) }
        }

        private suspend fun folderName(tree: Uri): String? =
            withContext(Dispatchers.IO) {
                runCatching {
                    val doc =
                        DocumentsContract.buildDocumentUriUsingTree(
                            tree,
                            DocumentsContract.getTreeDocumentId(tree),
                        )
                    context.contentResolver
                        .query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null }
                }.getOrNull()
            }

        companion object {
            private const val TAG = "CucutoAutoBackup"

            /** "Shortly after any change" (brief): long enough to group a burst of edits into one backup. */
            const val DEBOUNCE_MS = 10_000L

            private val json = Json { encodeDefaults = true }

            /**
             * A hash of what a backup holds, without the moment it was made or the app version: two backups with the
             * same fingerprint restore the same thing. Keys are sorted, so the most recently used key moving to the
             * front is not a change.
             */
            fun fingerprint(
                document: BackupDocument,
                includePacks: Boolean,
            ): String {
                val normalized =
                    document.copy(
                        createdAt = "",
                        app = document.app.copy(versionName = "", versionCode = 0),
                        contacts =
                            document.contacts
                                .map {
                                    it.copy(
                                        keys =
                                            it.keys.sortedBy { key ->
                                                key.packageName + "\u0000" +
                                                    key.conversationKey
                                            },
                                    )
                                },
                    )
                val bytes = json.encodeToString(BackupDocument.serializer(), normalized).encodeToByteArray()
                val digest = MessageDigest.getInstance("SHA-256")
                digest.update((if (includePacks) 1 else 0).toByte())
                return digest.digest(bytes).joinToString("") { "%02x".format(it) }
            }
        }
    }
