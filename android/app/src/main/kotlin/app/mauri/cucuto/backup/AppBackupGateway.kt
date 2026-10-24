package app.mauri.cucuto.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.mauri.cucuto.BuildConfig
import app.mauri.cucuto.core.backup.AppSettingsBackup
import app.mauri.cucuto.core.backup.BackupApp
import app.mauri.cucuto.core.backup.BackupExporter
import app.mauri.cucuto.core.backup.BackupFileNames
import app.mauri.cucuto.core.backup.BackupOpenResult
import app.mauri.cucuto.core.backup.BackupOptions
import app.mauri.cucuto.core.backup.BackupPackReference
import app.mauri.cucuto.core.backup.BackupPackSourceType
import app.mauri.cucuto.core.backup.BackupPackType
import app.mauri.cucuto.core.backup.BackupReader
import app.mauri.cucuto.core.backup.BackupRestorer
import app.mauri.cucuto.core.backup.OpenedBackup
import app.mauri.cucuto.core.backup.PackRestoreAction
import app.mauri.cucuto.core.backup.PackRestoreItem
import app.mauri.cucuto.core.backup.RestoreOptions
import app.mauri.cucuto.core.backup.RestoreReport
import app.mauri.cucuto.core.data.db.CucutoDatabase
import app.mauri.cucuto.core.data.db.InstalledPackDao
import app.mauri.cucuto.core.data.di.WallClock
import app.mauri.cucuto.core.data.settings.RoutingSettings
import app.mauri.cucuto.core.pack.ImportEvent
import app.mauri.cucuto.core.pack.ImportProgress
import app.mauri.cucuto.core.pack.ImportResult
import app.mauri.cucuto.core.pack.ImportSource
import app.mauri.cucuto.core.pack.PackSelection
import app.mauri.cucuto.core.pack.PackStore
import app.mauri.cucuto.core.pack.PreparedImport
import app.mauri.cucuto.core.repo.DownloadProgress
import app.mauri.cucuto.core.repo.ExpectedFile
import app.mauri.cucuto.core.repo.RepoError
import app.mauri.cucuto.core.repo.RepoResult
import app.mauri.cucuto.core.repo.RepositoryAccess
import app.mauri.cucuto.core.repo.RepositoryClient
import app.mauri.cucuto.core.repo.credentials.CredentialStore
import app.mauri.cucuto.core.ringtone.ContactLinkRestore
import app.mauri.cucuto.core.ringtone.PhoneRingtones
import app.mauri.cucuto.core.ringtone.restore.RestoredLink
import app.mauri.cucuto.packs.ImportStep
import app.mauri.cucuto.packs.PackLibrary
import app.mauri.cucuto.packs.PackOrigin
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real [BackupGateway]: `:core:backup` wired to the app's database, settings, pack library, credential store and
 * the storage access framework. Packs are installed through [PackLibrary], so restored packs are recorded with their
 * original source (repository, link or file name) and later backups keep pointing at it.
 */
@Singleton
class AppBackupGateway
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val db: CucutoDatabase,
        private val installedPackDao: InstalledPackDao,
        routing: RoutingSettings,
        private val library: PackLibrary,
        private val clock: WallClock,
        private val phoneRingtones: PhoneRingtones,
        // The app's repository client and Keystore-encrypted credential store (ADR 0011, RepositoryModule), created
        // only when a backup or restore needs them.
        credentialStore: Lazy<CredentialStore>,
        repositoryClient: Lazy<RepositoryClient>,
    ) : BackupGateway {
        private val settingsBackup = AppSettingsBackup(routing)

        private val credentials: CredentialStore by lazy { credentialStore.get() }

        /** Also used by [AutoBackup] for the automatic backups. */
        val exporter: BackupExporter by lazy {
            BackupExporter(
                db = db,
                settings = settingsBackup,
                packStore = PackStore.inFilesDir(context.filesDir),
                credentials = credentials,
                app =
                    BackupApp(
                        versionName = BuildConfig.VERSION_NAME,
                        versionCode = BuildConfig.VERSION_CODE.toLong(),
                    ),
                workDir = File(context.cacheDir, "backup-export"),
                clock = clock,
            )
        }
        private val reader by lazy { BackupReader.create(context.cacheDir) }
        private val restorer by lazy { BackupRestorer(db, settingsBackup, credentials, clock) }
        private val client by lazy { repositoryClient.get() }
        private val downloads: File get() = File(context.cacheDir, "backup-restore")

        override fun suggestedFileName(): String = BackupFileNames.forDate(today())

        /** Today's local date, which names backup files. */
        fun today() = Instant.ofEpochMilli(clock.now()).atZone(ZoneId.systemDefault()).toLocalDate()

        override suspend fun export(
            destination: String,
            request: ExportRequest,
        ): ExportOutcome =
            withContext(Dispatchers.IO) {
                val uri = Uri.parse(destination)
                try {
                    val options =
                        BackupOptions(
                            passphrase = request.passphrase,
                            includeCredentials = request.includeCredentials && request.passphrase != null,
                            includePackFiles = request.includePacks,
                        )
                    val result =
                        context.contentResolver.openOutputStream(uri, "wt")?.use { exporter.export(it, options) }
                            ?: throw IOException("The document can't be written")
                    ExportOutcome.Written(
                        encrypted = result.encrypted,
                        contacts = result.document.contacts.size,
                        includedPacks = result.includedPacks.size,
                        skippedPacks = result.skippedPacks,
                    )
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { deleteQuietly(uri) }
                    throw e
                } catch (e: Exception) {
                    // A half-written backup is worse than none: it looks fine until the day it is needed.
                    deleteQuietly(uri)
                    ExportOutcome.Failed(e.message ?: e.javaClass.simpleName)
                }
            }

        override suspend fun open(
            source: String,
            passphrase: CharArray?,
        ): OpenOutcome {
            val file = ImportSource.of(context.contentResolver, Uri.parse(source))
            return when (val result = reader.open(file, passphrase)) {
                is BackupOpenResult.Opened -> OpenOutcome.Opened(OpenedSession(result.backup))
                BackupOpenResult.PassphraseRequired -> OpenOutcome.PassphraseRequired
                is BackupOpenResult.DecryptionFailed -> OpenOutcome.WrongPassphrase
                is BackupOpenResult.NotABackup -> OpenOutcome.NotABackup(result.type)
                is BackupOpenResult.Invalid -> OpenOutcome.Invalid(result.report.issues.map { it.toString() })
                is BackupOpenResult.Failed -> OpenOutcome.Failed(result.message)
            }
        }

        override suspend fun installedPacks(): Map<String, String?> =
            withContext(Dispatchers.IO) { installedPackDao.getAll().associate { it.packId to it.bundleId } }

        override suspend fun restore(
            session: RestoreSession,
            overwrite: Set<String>,
        ): RestoreReport =
            withContext(Dispatchers.IO) {
                restorer.restore(session.document, session.encrypted, RestoreOptions(overwrite))
            }

        override suspend fun restoreContactLinks(reports: List<RestoreReport>): ContactLinkRestore =
            phoneRingtones.afterRestore(restoredLinks(reports))

        override suspend fun findRestoredContacts(): ContactLinkRestore? =
            if (phoneRingtones.canReadContacts()) phoneRingtones.processPendingRestore() else null

        override suspend fun enablePhoneRingtones(): ContactLinkRestore? = phoneRingtones.enableAndRestore()

        override suspend fun linkPickedContact(
            contactId: Long,
            contactUri: String,
        ): Boolean = phoneRingtones.linkPicked(contactId, contactUri) != null

        override fun restorePack(
            session: RestoreSession,
            item: PackRestoreItem,
        ): Flow<PackProgress> =
            channelFlow {
                val ref = item.reference
                when (item.action) {
                    PackRestoreAction.INSTALLED -> {
                        send(PackProgress.Done)
                    }

                    PackRestoreAction.FROM_BACKUP -> {
                        val source = (session as? OpenedSession)?.backup?.includedPack(ref)
                        if (source == null) {
                            send(PackProgress.Failed(PackFailure.OTHER))
                        } else if (install(source, item, originOf(ref))) {
                            keepOriginalHash(ref)
                        }
                    }

                    PackRestoreAction.DOWNLOAD_FROM_REPOSITORY -> {
                        downloadFromRepository(item)
                    }

                    PackRestoreAction.DOWNLOAD_FROM_URL -> {
                        downloadFromUrl(item)
                    }

                    PackRestoreAction.PICK_FILE -> {
                        send(PackProgress.Failed(PackFailure.OTHER, "Pick the file"))
                    }
                }
            }.flowOn(Dispatchers.IO)

        override fun importPackFile(
            source: String,
            item: PackRestoreItem,
        ): Flow<PackProgress> =
            channelFlow {
                val file = ImportSource.of(context.contentResolver, Uri.parse(source))
                val name = item.reference.source.fileName ?: file.describe().displayName
                install(file, item, PackOrigin.Local(name))
            }.flowOn(Dispatchers.IO)

        private suspend fun ProducerScope<PackProgress>.downloadFromRepository(item: PackRestoreItem) {
            val ref = item.reference
            val repositoryUrl = ref.source.repositoryUrl
            val indexUrl = repositoryUrl?.toHttpUrlOrNull()
            if (repositoryUrl == null || indexUrl == null) {
                send(PackProgress.Failed(PackFailure.DOWNLOAD))
                return
            }
            send(PackProgress.Working(null))
            val access = RepositoryAccess(indexUrl, credentials.get(repositoryUrl))
            val snapshot =
                when (val index = client.fetchIndex(access)) {
                    is RepoResult.Failure -> return send(failure(index.error))
                    is RepoResult.Success -> index.value.snapshot
                }
            val matching = snapshot.entries.filter { it.entry.id == ref.id && it.supported }
            val entry = matching.firstOrNull { it.entry.version == ref.version } ?: matching.firstOrNull()
            if (entry == null) {
                send(PackProgress.Failed(PackFailure.NOT_IN_REPOSITORY))
                return
            }
            downloadAndInstall(
                entry.downloadUrl,
                entry.expected,
                access,
                item,
                PackOrigin.Repository(repositoryUrl),
            )
        }

        private suspend fun ProducerScope<PackProgress>.downloadFromUrl(item: PackRestoreItem) {
            val ref = item.reference
            val link = ref.source.downloadUrl
            val url = link?.toHttpUrlOrNull()
            if (link == null || url == null) {
                send(PackProgress.Failed(PackFailure.DOWNLOAD))
                return
            }
            // The backup's hash is the file that was imported: a link that now serves another file is refused.
            val expected = ExpectedFile(ref.size ?: -1, ref.sha256).takeIf { ref.size != null }
            downloadAndInstall(url, expected, null, item, PackOrigin.Url(link), checkHash = ref.sha256)
        }

        private suspend fun ProducerScope<PackProgress>.downloadAndInstall(
            url: HttpUrl,
            expected: ExpectedFile?,
            access: RepositoryAccess?,
            item: PackRestoreItem,
            origin: PackOrigin,
            checkHash: String? = null,
        ) {
            val file = File(downloads, "${UUID.randomUUID()}.soundpack.zip")
            try {
                val progress =
                    DownloadProgress {
                        bytes,
                        total,
                        ->
                        send(PackProgress.Working(total?.let { fraction(bytes, it) }))
                    }
                when (val result = client.download(url, file, expected, access, progress)) {
                    is RepoResult.Failure -> {
                        send(failure(result.error))
                    }

                    is RepoResult.Success -> {
                        if (checkHash != null && !result.value.sha256.equals(checkHash, ignoreCase = true)) {
                            send(PackProgress.Failed(PackFailure.HASH_MISMATCH))
                        } else {
                            install(ImportSource.of(result.value.file), item, origin)
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) { file.delete() }
            }
        }

        /**
         * Validates and installs the packs of [source] that [item] is missing. Returns true when they were installed;
         * sends [PackProgress.Done] or a failure either way.
         */
        private suspend fun ProducerScope<PackProgress>.install(
            source: ImportSource,
            item: PackRestoreItem,
            origin: PackOrigin,
        ): Boolean {
            send(PackProgress.Working(null))
            val prepared =
                library.prepare(source).first { it !is ImportStep.Working }.let { step ->
                    when (step) {
                        is ImportStep.Ready -> {
                            step.review.handle as PreparedImport
                        }

                        is ImportStep.Invalid -> {
                            send(PackProgress.Failed(PackFailure.INVALID))
                            return false
                        }

                        is ImportStep.Failed -> {
                            send(PackProgress.Failed(PackFailure.OTHER, step.message))
                            return false
                        }

                        else -> {
                            send(PackProgress.Failed(PackFailure.OTHER))
                            return false
                        }
                    }
                }
            val wanted = wantedPacks(prepared, item)
            if (wanted.isEmpty()) {
                library.discard(prepared)
                send(PackProgress.Failed(PackFailure.WRONG_FILE))
                return false
            }
            var installed = false
            library.install(prepared, PackSelection(wanted, wanted), origin).collect { event ->
                when (event) {
                    is ImportEvent.Progress -> {
                        val p = event.progress
                        send(
                            PackProgress.Working(
                                (p as? ImportProgress.Analyzing)?.let { fraction(it.done.toLong(), it.total.toLong()) },
                            ),
                        )
                    }

                    is ImportEvent.Finished -> {
                        val result = event.result
                        installed = result is ImportResult.Installed && result.packs.any { it.active }
                        send(
                            if (installed) {
                                PackProgress.Done
                            } else {
                                PackProgress.Failed(
                                    PackFailure.OTHER,
                                    result.toString(),
                                )
                            },
                        )
                    }
                }
            }
            return installed
        }

        /** After a restore from a full backup, record the original file's hash (what repositories and links serve). */
        private suspend fun keepOriginalHash(ref: BackupPackReference) {
            val dao = installedPackDao
            for (id in ref.packIds.ifEmpty { listOf(ref.id) }) {
                val row = dao.get(id) ?: continue
                dao.upsert(row.copy(sha256 = ref.sha256, sizeBytes = ref.size ?: row.sizeBytes))
            }
        }

        private fun originOf(ref: BackupPackReference): PackOrigin {
            val source = ref.source
            return when {
                source.type == BackupPackSourceType.REPOSITORY && source.repositoryUrl != null -> {
                    PackOrigin
                        .Repository(
                            source.repositoryUrl!!,
                        )
                }

                source.type == BackupPackSourceType.URL && source.downloadUrl != null -> {
                    PackOrigin.Url(
                        source.downloadUrl!!,
                    )
                }

                else -> {
                    PackOrigin.Local(source.fileName)
                }
            }
        }

        private fun deleteQuietly(uri: Uri) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
        }

        /** A validated backup from [BackupReader]. */
        private class OpenedSession(
            val backup: OpenedBackup,
        ) : RestoreSession {
            override val document get() = backup.document
            override val encrypted get() = backup.encrypted
            override val includedPacks get() = backup.includedPacks

            override fun close() = backup.close()
        }

        companion object {
            /** The packs of [import] that [item] still needs: its missing IDs, or every new pack of its bundle. */
            internal fun wantedPacks(
                import: PreparedImport,
                item: PackRestoreItem,
            ): Set<String> {
                val ref = item.reference
                val wholeBundle =
                    ref.type == BackupPackType.BUNDLE && item.missingPackIds == listOf(ref.id) &&
                        import.bundle?.id == ref.id
                return import.packs
                    .filter { it.id in item.missingPackIds || (wholeBundle && it.installedVersion == null) }
                    .map { it.id }
                    .toSet()
            }

            private fun fraction(
                done: Long,
                total: Long,
            ): Float? = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null

            private fun failure(error: RepoError): PackProgress.Failed =
                when (error) {
                    is RepoError.HashMismatch, is RepoError.SizeMismatch -> {
                        PackProgress.Failed(
                            PackFailure.HASH_MISMATCH,
                        )
                    }

                    is RepoError.Storage -> {
                        PackProgress.Failed(PackFailure.OTHER, error.toString())
                    }

                    else -> {
                        PackProgress.Failed(PackFailure.DOWNLOAD, error.toString())
                    }
                }
        }
    }
