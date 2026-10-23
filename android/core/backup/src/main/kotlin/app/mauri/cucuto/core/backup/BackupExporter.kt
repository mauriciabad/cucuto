package app.mauri.cucuto.core.backup

import app.mauri.cucuto.core.data.db.CucutoDatabase
import app.mauri.cucuto.core.data.di.WallClock
import app.mauri.cucuto.core.pack.PackStore
import app.mauri.cucuto.core.repo.RepositoryCredentials
import app.mauri.cucuto.core.repo.credentials.CredentialStore
import app.mauri.cucuto.packspec.DocumentKind
import app.mauri.cucuto.packspec.DocumentValidator
import app.mauri.cucuto.packspec.ValidationReport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** What to put in a backup. */
class BackupOptions(
    /** Encrypts the whole backup with this passphrase (Argon2id + AES-256-GCM). */
    val passphrase: CharArray? = null,
    /** Includes repository credentials. Only allowed together with a [passphrase]. */
    val includeCredentials: Boolean = false,
    /** A "full backup": includes every installed pack's files, so restoring needs no downloads. Can be large. */
    val includePackFiles: Boolean = false,
    val kdf: KdfParameters = KdfParameters.DEFAULT,
) {
    init {
        require(!includeCredentials || passphrase != null) { "Credentials are only exported in encrypted backups" }
        require(passphrase == null || passphrase.isNotEmpty()) { "The passphrase is empty" }
    }
}

/** What [BackupExporter.export] wrote. */
data class BackupExportResult(
    val document: BackupDocument,
    val encrypted: Boolean,
    /** References whose pack files are inside the backup. */
    val includedPacks: List<String>,
    /** Installed packs left out: their file hash is unknown and their files were not included. */
    val skippedPacks: List<String>,
)

/** The backup is not valid against `spec/backup.schema.json`: a bug, reported instead of writing a broken file. */
class BackupInvalidException(
    val report: ValidationReport,
) : IllegalStateException("The backup does not match its schema: ${report.errors.joinToString()}")

/**
 * Writes backups (docs/pack-format.md#backups): `backup.json` from the database, the settings and optionally the
 * repository credentials, plus the installed packs' files for a full backup, optionally encrypted.
 *
 * Every document is validated against the schema before anything is written. A full backup re-zips each installed
 * pack (or the installed packs of a bundle) from its folder: the importer does not keep the original file.
 */
class BackupExporter(
    private val db: CucutoDatabase,
    private val settings: SettingsBackup,
    private val packStore: PackStore,
    /** Needed only for backups that include credentials. */
    private val credentials: CredentialStore?,
    private val app: BackupApp,
    /** Scratch space for encrypted backups (the plain zip before encryption), in the cache. */
    private val workDir: File,
    private val clock: WallClock,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Reads the app's state into a backup document, without pack files. */
    suspend fun snapshot(includeCredentials: Boolean = false): BackupDocument {
        val contacts = db.contactDao().observeAll().first()
        val assignments =
            db
                .assignmentDao()
                .observeAll()
                .first()
                .associateBy { it.contactId }
        val repositories = db.packRepositoryDao().observeAll().first()
        val installed = db.installedPackDao().getAll()
        return BackupDocument(
            createdAt = Instant.ofEpochMilli(clock.now()).truncatedTo(ChronoUnit.SECONDS).toString(),
            app = app,
            settings = settings.export(),
            repositories =
                repositories.map { row ->
                    val secret = if (includeCredentials) credentials?.get(row.url)?.let(::toBackup) else null
                    BackupSnapshot.repository(row, secret)
                },
            packs = BackupSnapshot.packReferences(installed),
            contacts =
                contacts
                    .sortedBy { it.contact.id }
                    .map { BackupSnapshot.contact(it, assignments[it.contact.id]) },
        )
    }

    /** Writes a backup to [output] (an SAF document or a file in the automatic backup folder). Does not close it. */
    suspend fun export(
        output: OutputStream,
        options: BackupOptions = BackupOptions(),
    ): BackupExportResult =
        withContext(ioDispatcher) {
            val snapshot = snapshot(options.includeCredentials && options.passphrase != null)
            val plan = plan(snapshot, options.includePackFiles)
            validate(plan.document)
            val passphrase = options.passphrase
            val written =
                if (passphrase == null) {
                    writePlain(plan, output)
                } else {
                    if (!workDir.isDirectory && !workDir.mkdirs()) throw java.io.IOException("Cannot create $workDir")
                    val plain = File(workDir, "${UUID.randomUUID()}.zip")
                    try {
                        val document = plain.outputStream().buffered().use { writePlain(plan, it) }
                        writeEncrypted(plain, output, passphrase, options.kdf)
                        document
                    } finally {
                        plain.delete()
                    }
                }
            BackupExportResult(
                document = written,
                encrypted = passphrase != null,
                includedPacks =
                    plan.packFiles.keys
                        .map { it.id }
                        .sorted(),
                skippedPacks = plan.skipped,
            )
        }

    private class Plan(
        val document: BackupDocument,
        /** Reference (with its `included` path) to the folders to zip: one for a pack, the members of a bundle. */
        val packFiles: Map<BackupPackReference, List<Pair<String, File>>>,
        val skipped: List<String>,
    )

    private fun plan(
        snapshot: BackupDocument,
        includeFiles: Boolean,
    ): Plan {
        val packFiles = linkedMapOf<BackupPackReference, List<Pair<String, File>>>()
        val skipped = mutableListOf<String>()
        val references =
            snapshot.packs.mapNotNull { ref ->
                val folders =
                    if (includeFiles) {
                        ref.packIds.map { id -> id to packStore.activePackDir(id) }.takeIf { all ->
                            all.all {
                                it.second !=
                                    null
                            }
                        }
                    } else {
                        null
                    }
                when {
                    folders != null && folders.isNotEmpty() -> {
                        val included = ref.copy(included = BackupSnapshot.includedPath(ref))
                        packFiles[included] = folders.map { it.first to it.second!! }
                        included
                    }

                    ref.sha256 == BackupSnapshot.UNKNOWN_SHA256 -> {
                        skipped += ref.id
                        null
                    }

                    else -> {
                        ref
                    }
                }
            }
        return Plan(snapshot.copy(packs = references), packFiles, skipped)
    }

    private fun validate(document: BackupDocument) {
        val report = DocumentValidator.validate(DocumentKind.BACKUP, encode(document))
        if (!report.isValid) throw BackupInvalidException(report)
    }

    /** Writes the plain backup zip: pack files first (hashing those with an unknown hash), then `backup.json`. */
    private fun writePlain(
        plan: Plan,
        output: OutputStream,
    ): BackupDocument {
        val zip = ZipOutputStream(NonClosingOutputStream(output))
        val hashed = mutableMapOf<String, Pair<String, Long>>()
        for ((ref, folders) in plan.packFiles) {
            zip.setLevel(Deflater.NO_COMPRESSION) // Already a zip: compressing again only costs time.
            zip.putNextEntry(ZipEntry(ref.included!!))
            val digest = MessageDigest.getInstance("SHA-256")
            val counter = CountingOutputStream(DigestOutputStream(NonClosingOutputStream(zip), digest))
            if (ref.type == BackupPackType.PACK) {
                PackZipper.zipPack(folders.single().second, counter)
            } else {
                PackZipper.zipBundle(ref, folders, counter)
            }
            zip.closeEntry()
            hashed[ref.id] = digest.digest().toHex() to counter.count
        }
        val document =
            plan.document.copy(
                packs =
                    plan.document.packs.map { ref ->
                        val (sha256, size) = hashed[ref.id] ?: return@map ref
                        if (ref.sha256 == BackupSnapshot.UNKNOWN_SHA256) ref.copy(sha256 = sha256, size = size) else ref
                    },
            )
        zip.setLevel(Deflater.DEFAULT_COMPRESSION)
        zip.putNextEntry(ZipEntry(BACKUP))
        zip.write(encode(document))
        zip.closeEntry()
        zip.finish()
        zip.flush()
        return document
    }

    private fun writeEncrypted(
        plain: File,
        output: OutputStream,
        passphrase: CharArray,
        kdf: KdfParameters,
    ) {
        val salt = BackupCrypto.randomBytes(BackupCrypto.SALT_BYTES)
        val nonce = BackupCrypto.randomBytes(BackupCrypto.NONCE_BYTES)
        val key = BackupCrypto.deriveKey(passphrase, salt, kdf)
        try {
            val envelope =
                BackupEnvelope(
                    kdf =
                        EnvelopeKdf(
                            memoryKiB = kdf.memoryKiB,
                            iterations = kdf.iterations,
                            parallelism = kdf.parallelism,
                            salt = BackupCrypto.encodeBase64(salt),
                        ),
                    nonce = BackupCrypto.encodeBase64(nonce),
                )
            val zip = ZipOutputStream(NonClosingOutputStream(output))
            zip.putNextEntry(ZipEntry(ENVELOPE))
            zip.write(BackupJson.encodeToString(BackupEnvelope.serializer(), envelope).encodeToByteArray())
            zip.closeEntry()
            zip.setLevel(Deflater.NO_COMPRESSION) // Ciphertext does not compress.
            zip.putNextEntry(ZipEntry(BackupEnvelope.PAYLOAD))
            plain.inputStream().buffered().use { BackupCrypto.encrypt(key, nonce, it, NonClosingOutputStream(zip)) }
            zip.closeEntry()
            zip.finish()
            zip.flush()
        } finally {
            key.fill(0)
        }
    }

    private fun toBackup(credentials: RepositoryCredentials): BackupCredentials =
        when (credentials) {
            is RepositoryCredentials.Basic -> {
                BackupCredentials(
                    username = credentials.username,
                    password = credentials.password,
                )
            }

            is RepositoryCredentials.Bearer -> {
                BackupCredentials(token = credentials.token)
            }
        }

    companion object {
        internal fun encode(document: BackupDocument): ByteArray =
            BackupJson.encodeToString(BackupDocument.serializer(), document).encodeToByteArray()
    }
}

/** Zips installed pack folders back into `.soundpack.zip` files for full backups. */
internal object PackZipper {
    /** A single pack: the folder's files at the root, sorted, deflated. */
    fun zipPack(
        folder: File,
        output: OutputStream,
    ) {
        val zip = ZipOutputStream(NonClosingOutputStream(output))
        addFolder(zip, folder, "")
        zip.finish()
    }

    /** A bundle with the installed packs only, each in a folder named after its ID. */
    fun zipBundle(
        reference: BackupPackReference,
        folders: List<Pair<String, File>>,
        output: OutputStream,
    ) {
        val zip = ZipOutputStream(NonClosingOutputStream(output))
        val manifest =
            JsonObject(
                linkedMapOf(
                    "formatVersion" to JsonPrimitive(1),
                    "type" to JsonPrimitive("bundle"),
                    "id" to JsonPrimitive(reference.id),
                    "name" to JsonPrimitive(reference.name ?: reference.id),
                    "version" to JsonPrimitive(reference.version),
                    "packs" to kotlinx.serialization.json.JsonArray(folders.map { JsonPrimitive(it.first) }),
                ),
            )
        zip.putNextEntry(ZipEntry(BUNDLE))
        zip.write(Json.encodeToString(JsonObject.serializer(), manifest).encodeToByteArray())
        zip.closeEntry()
        for ((id, folder) in folders) addFolder(zip, folder, "$id/")
        zip.finish()
    }

    private fun addFolder(
        zip: ZipOutputStream,
        folder: File,
        prefix: String,
    ) {
        val files =
            folder
                .walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(folder).invariantSeparatorsPath }
                .sorted()
        for (path in files) {
            zip.putNextEntry(ZipEntry(prefix + path))
            File(folder, path).inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }
}

/** Lets a nested writer finish its zip without closing the stream it writes into. */
internal class NonClosingOutputStream(
    output: OutputStream,
) : FilterOutputStream(output) {
    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) = out.write(b, off, len)

    override fun close() = flush()
}

private class CountingOutputStream(
    output: OutputStream,
) : FilterOutputStream(output) {
    var count = 0L
        private set

    override fun write(b: Int) {
        out.write(b)
        count++
    }

    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        out.write(b, off, len)
        count += len
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
