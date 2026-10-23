package app.mauri.cucuto.core.backup

import app.mauri.cucuto.core.pack.ImportSource
import app.mauri.cucuto.packspec.DocumentKind
import app.mauri.cucuto.packspec.DocumentValidator
import app.mauri.cucuto.packspec.ErrorCode
import app.mauri.cucuto.packspec.Issue
import app.mauri.cucuto.packspec.PackReadException
import app.mauri.cucuto.packspec.ValidationReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/** How opening a backup ended. */
sealed interface BackupOpenResult {
    /** A valid backup. Close [backup] when the restore is done: it deletes the scratch copies. */
    data class Opened(
        val backup: OpenedBackup,
    ) : BackupOpenResult

    /** The backup is encrypted: ask for the passphrase and open it again. */
    data object PassphraseRequired : BackupOpenResult

    /** Wrong passphrase, or the encrypted file was changed (`BACKUP_DECRYPTION_FAILED`). */
    data class DecryptionFailed(
        val message: String,
    ) : BackupOpenResult

    /** The file is a pack or a bundle: hand it to the pack importer instead. */
    data class NotABackup(
        val type: ZipContentType,
    ) : BackupOpenResult

    /** The file breaks the backup format; [report] has the stable error codes. */
    data class Invalid(
        val report: ValidationReport,
    ) : BackupOpenResult

    /** Something outside the file's content failed (reading the source, storage). */
    data class Failed(
        val message: String,
        val cause: Throwable? = null,
    ) : BackupOpenResult
}

/**
 * A validated backup, ready to restore. Its pack files (a full backup) are read straight from the scratch copy.
 */
class OpenedBackup internal constructor(
    val document: BackupDocument,
    /** True when it was passphrase-encrypted: only then are its repository credentials restored. */
    val encrypted: Boolean,
    private val zip: ZipContents,
    private val scratch: List<File>,
) : Closeable {
    /** References whose `included` file is really in the archive. */
    val includedPacks: List<BackupPackReference> =
        document.packs.filter { ref -> ref.included?.let { zip.record(it) } != null }

    /** References that claim an included file the archive does not have; restore falls back to their source. */
    val missingIncludedPacks: List<BackupPackReference> =
        document.packs.filter { it.included != null && it !in includedPacks }

    /**
     * The included file of [reference] as an import source for the pack importer, which validates it like any other
     * pack. Null when the backup does not include it.
     */
    fun includedPack(reference: BackupPackReference): ImportSource? {
        val path = reference.included ?: return null
        val record = zip.record(path) ?: return null
        return object : ImportSource {
            override fun describe() = ImportSource.Description(path.substringAfterLast('/'), record.uncompressedSize)

            override fun open(): InputStream = zip.open(path)
        }
    }

    override fun close() {
        zip.close()
        scratch.forEach(File::delete)
    }
}

/**
 * Opens backups (`cucuto-backup-YYYY-MM-DD.zip`, any name works): copies the source into [workDir] so the zip can be
 * read at random, detects plain and encrypted backups by their root entries (and points packs and bundles to the
 * importer), decrypts, then validates `backup.json` with the shared schema before decoding it.
 */
class BackupReader(
    /** Scratch folder in the cache. */
    private val workDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Highest Argon2id memory cost accepted, so a crafted file cannot exhaust the app's memory. */
    private val maxKdfMemoryKiB: Int = MAX_KDF_MEMORY_KIB,
) {
    suspend fun open(
        source: ImportSource,
        passphrase: CharArray? = null,
    ): BackupOpenResult =
        withContext(ioDispatcher) {
            val scratch = mutableListOf<File>()
            var keep = false
            try {
                if (!workDir.isDirectory && !workDir.mkdirs()) throw IOException("Cannot create $workDir")
                val copy = File(workDir, "${UUID.randomUUID()}.zip").also(scratch::add)
                try {
                    source.open().use { input -> copy.outputStream().use { input.copyTo(it) } }
                } catch (e: IOException) {
                    return@withContext BackupOpenResult.Failed("Could not read the file: ${e.message}", e)
                } catch (e: SecurityException) {
                    return@withContext BackupOpenResult.Failed("No permission to read the file", e)
                }
                currentCoroutineContext().ensureActive()
                openFile(copy, passphrase, scratch).also { keep = it is BackupOpenResult.Opened }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                BackupOpenResult.Failed("Could not open the backup: ${e.message}", e)
            } finally {
                if (!keep) withContext(NonCancellable) { scratch.forEach(File::delete) }
            }
        }

    private suspend fun openFile(
        file: File,
        passphrase: CharArray?,
        scratch: MutableList<File>,
    ): BackupOpenResult {
        val (zip, archiveIssues) = ZipContents.open(file)
        if (zip == null) return invalid(archiveIssues)
        var keepZip = false
        try {
            val (type, issues) = zip.type(archiveIssues)
            return when (type) {
                null -> {
                    invalid(issues)
                }

                ZipContentType.PACK, ZipContentType.BUNDLE -> {
                    BackupOpenResult.NotABackup(type)
                }

                ZipContentType.BACKUP -> {
                    readPlain(zip, encrypted = false, scratch).also { keepZip = it is BackupOpenResult.Opened }
                }

                ZipContentType.ENCRYPTED_BACKUP -> {
                    if (passphrase == null) return BackupOpenResult.PassphraseRequired
                    decrypt(zip, passphrase, scratch)
                }
            }
        } finally {
            if (!keepZip) zip.close()
        }
    }

    private suspend fun decrypt(
        zip: ZipContents,
        passphrase: CharArray,
        scratch: MutableList<File>,
    ): BackupOpenResult {
        val bytes =
            try {
                zip.read(ENVELOPE, ENVELOPE_BYTES)
            } catch (e: PackReadException) {
                return unreadable(e, ENVELOPE)
            }
        val report = DocumentValidator.validate(DocumentKind.BACKUP_ENVELOPE, bytes, ENVELOPE)
        if (!report.isValid) return BackupOpenResult.Invalid(report)
        val envelope = BackupJson.decodeFromString(BackupEnvelope.serializer(), bytes.decodeToString())
        val kdf = envelope.kdf
        if (kdf.memoryKiB > maxKdfMemoryKiB) {
            val message =
                "The backup needs ${kdf.memoryKiB / 1024} MiB to decrypt; at most ${maxKdfMemoryKiB / 1024} MiB is allowed"
            val issue = Issue(ErrorCode.BACKUP_DECRYPTION_FAILED, message, file = ENVELOPE, pointer = "/kdf/memoryKiB")
            return invalid(listOf(issue))
        }
        val salt = BackupCrypto.decodeBase64(kdf.salt)?.takeIf { it.size >= BackupCrypto.SALT_BYTES }
        val nonce = BackupCrypto.decodeBase64(envelope.nonce)?.takeIf { it.size == BackupCrypto.NONCE_BYTES }
        if (salt == null || nonce == null) {
            val message = "Salt or nonce is not valid Base64 of the right size"
            return invalid(listOf(Issue(ErrorCode.SCHEMA_VIOLATION, message, file = ENVELOPE)))
        }
        val payload = zip.record(BackupEnvelope.PAYLOAD)!!
        if (workDir.usableSpace < payload.uncompressedSize + FREE_SPACE_MARGIN_BYTES) {
            return BackupOpenResult.Failed("Not enough free space to decrypt the backup")
        }
        val plain = File(workDir, "${UUID.randomUUID()}-plain.zip").also(scratch::add)
        val key =
            BackupCrypto.deriveKey(
                passphrase,
                salt,
                KdfParameters(kdf.memoryKiB, kdf.iterations, kdf.parallelism),
            )
        try {
            currentCoroutineContext().ensureActive()
            zip.open(BackupEnvelope.PAYLOAD).use { input ->
                plain.outputStream().buffered().use { output -> BackupCrypto.decrypt(key, nonce, input, output) }
            }
        } catch (e: BackupDecryptionException) {
            plain.delete()
            return BackupOpenResult.DecryptionFailed(e.message ?: "Wrong passphrase or damaged backup")
        } finally {
            key.fill(0)
        }
        val (inner, innerIssues) = ZipContents.open(plain)
        if (inner == null) return invalid(innerIssues)
        val (type, issues) = inner.type(innerIssues)
        if (type != ZipContentType.BACKUP) {
            inner.close()
            val found =
                issues.ifEmpty {
                    listOf(
                        Issue(ErrorCode.MANIFEST_MISSING, "The encrypted payload is not a plain backup"),
                    )
                }
            return invalid(found)
        }
        return readPlain(inner, encrypted = true, scratch).also { if (it !is BackupOpenResult.Opened) inner.close() }
    }

    private fun readPlain(
        zip: ZipContents,
        encrypted: Boolean,
        scratch: List<File>,
    ): BackupOpenResult {
        val bytes =
            try {
                zip.read(BACKUP, DocumentValidator.BACKUP_BYTES)
            } catch (e: PackReadException) {
                return unreadable(e, BACKUP)
            }
        val report = DocumentValidator.validate(DocumentKind.BACKUP, bytes, BACKUP)
        if (!report.isValid) return BackupOpenResult.Invalid(report)
        val document =
            try {
                BackupJson.decodeFromString(BackupDocument.serializer(), bytes.decodeToString())
            } catch (e: IllegalArgumentException) {
                return invalid(
                    listOf(Issue(ErrorCode.SCHEMA_VIOLATION, "Unreadable backup: ${e.message}", file = BACKUP)),
                )
            }
        return BackupOpenResult.Opened(OpenedBackup(document, encrypted, zip, scratch.toList()))
    }

    private fun unreadable(
        e: PackReadException,
        name: String,
    ): BackupOpenResult = invalid(listOf(Issue(e.code, e.message ?: "Unreadable", file = name)))

    private fun invalid(issues: List<Issue>): BackupOpenResult = BackupOpenResult.Invalid(ValidationReport(issues))

    companion object {
        /** 256 MiB: four times what Cucuto writes, and still safe for the app's memory. */
        const val MAX_KDF_MEMORY_KIB = 262_144
        private const val ENVELOPE_BYTES = 64L * 1024
        private const val FREE_SPACE_MARGIN_BYTES = 32L * 1024 * 1024

        /** The app's reader: scratch files in `cache/backup/`. */
        fun create(cacheDir: File): BackupReader = BackupReader(File(cacheDir, "backup"))
    }
}
