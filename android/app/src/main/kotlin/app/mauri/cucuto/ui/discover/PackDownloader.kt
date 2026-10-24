package app.mauri.cucuto.ui.discover

import app.mauri.cucuto.core.pack.ImportSource
import app.mauri.cucuto.core.repo.DownloadProgress
import app.mauri.cucuto.core.repo.ExpectedFile
import app.mauri.cucuto.core.repo.RepoResult
import app.mauri.cucuto.core.repo.RepositoryAccess
import app.mauri.cucuto.core.repo.RepositoryClient
import app.mauri.cucuto.packs.ImportStep
import app.mauri.cucuto.packs.PackCatalog
import app.mauri.cucuto.packs.PackOrigin
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Downloads a repository entry or a direct pack link (size and SHA-256 checked by [RepositoryClient]) to a temporary
 * file in [workDir], then hands it to the same import pipeline as local files ([PackCatalog.prepare], ADR 0011): the
 * user reviews and confirms in the import review as usual. The temporary file is deleted once the importer has its own
 * copy, or when the download fails or is cancelled.
 */
class PackDownloader(
    private val client: RepositoryClient,
    private val catalog: PackCatalog,
    private val workDir: File,
) {
    private val leftoversDeleted = AtomicBoolean(false)

    /**
     * Emits [ImportStep.Downloading] while the file arrives, then the importer's steps, or one
     * [ImportStep.DownloadFailed]. [expected] is the index entry's size and hash (null for a direct link, which is
     * only capped); [access] supplies credentials for the repository's origin.
     */
    fun download(
        name: String,
        url: HttpUrl,
        expected: ExpectedFile?,
        access: RepositoryAccess?,
        origin: PackOrigin,
    ): Flow<ImportStep> =
        channelFlow {
            // Downloads interrupted by process death: nothing else uses this folder.
            if (leftoversDeleted.compareAndSet(false, true)) withContext(NonCancellable) { deleteFolder() }
            val file = File(workDir, "${UUID.randomUUID()}.soundpack.zip")
            try {
                val progress = DownloadProgress { bytes, total -> send(ImportStep.Downloading(name, bytes, total)) }
                when (val result = client.download(url, file, expected, access, progress)) {
                    is RepoResult.Failure -> {
                        send(ImportStep.DownloadFailed(result.error))
                    }

                    is RepoResult.Success -> {
                        catalog
                            .prepare(
                                ImportSource.of(result.value.file),
                                origin,
                            ).collect { send(it) }
                    }
                }
            } finally {
                withContext(NonCancellable) { file.delete() }
            }
        }

    private fun deleteFolder() {
        workDir.listFiles()?.forEach { it.deleteRecursively() }
    }
}
