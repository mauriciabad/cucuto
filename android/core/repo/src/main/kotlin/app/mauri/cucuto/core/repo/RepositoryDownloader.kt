package app.mauri.cucuto.core.repo

import android.content.Context
import app.mauri.cucuto.core.pack.ImportEvent
import app.mauri.cucuto.core.pack.ImportSource
import app.mauri.cucuto.core.pack.PackImporter
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import java.io.File
import java.util.UUID

/** What [RepositoryDownloader.downloadAndPrepare] emits. */
sealed interface DownloadEvent {
    /** Transfer progress: [bytes] of [total] (null when unknown). */
    data class Downloading(
        val bytes: Long,
        val total: Long?,
    ) : DownloadEvent

    /** The file arrived and matched the index; record [file]'s size and SHA-256 with the installed packs. */
    data class Downloaded(
        val size: Long,
        val sha256: String,
    ) : DownloadEvent

    /** The download failed; nothing reached the importer. Terminal. */
    data class Failed(
        val error: RepoError,
    ) : DownloadEvent

    /** An event of [PackImporter.prepare]; its [ImportEvent.Finished] is terminal. */
    data class Import(
        val event: ImportEvent,
    ) : DownloadEvent
}

/**
 * Downloads a repository entry or a direct `.soundpack.zip` link to a temporary file in [workDir], then hands it to
 * the same [PackImporter] pipeline as local files (validation, decoding, staging). The temporary file is deleted when
 * the import is prepared, fails or is cancelled; the importer keeps its own copy until install.
 */
class RepositoryDownloader(
    private val client: RepositoryClient,
    private val importer: PackImporter,
    private val workDir: File,
) {
    /** Downloads [entry] (size and SHA-256 verified) from the repository reached with [access]. */
    fun downloadAndPrepare(
        entry: ResolvedEntry,
        access: RepositoryAccess,
    ): Flow<DownloadEvent> = run(entry.downloadUrl, entry.expected, access)

    /** Downloads a direct pack link: no index, so only the size cap applies and the hash is reported. */
    fun downloadAndPrepare(url: HttpUrl): Flow<DownloadEvent> = run(url, expected = null, access = null)

    /** Deletes downloads interrupted by process death. Call once at startup. */
    fun deleteLeftovers() {
        workDir.deleteRecursively()
    }

    private fun run(
        url: HttpUrl,
        expected: ExpectedFile?,
        access: RepositoryAccess?,
    ): Flow<DownloadEvent> =
        channelFlow {
            val file = File(workDir, "${UUID.randomUUID()}.soundpack.zip")
            try {
                val progress = DownloadProgress { bytes, total -> send(DownloadEvent.Downloading(bytes, total)) }
                when (val result = client.download(url, file, expected, access, progress)) {
                    is RepoResult.Failure -> {
                        send(DownloadEvent.Failed(result.error))
                    }

                    is RepoResult.Success -> {
                        send(DownloadEvent.Downloaded(result.value.size, result.value.sha256))
                        importer.prepare(ImportSource.of(result.value.file)).collect { send(DownloadEvent.Import(it)) }
                    }
                }
            } finally {
                withContext(NonCancellable) { file.delete() }
            }
        }

    companion object {
        /** The app's downloader: temporary files in `cache/repo-download/`. */
        fun create(
            context: Context,
            client: RepositoryClient,
            importer: PackImporter,
        ): RepositoryDownloader = RepositoryDownloader(client, importer, File(context.cacheDir, "repo-download"))
    }
}
