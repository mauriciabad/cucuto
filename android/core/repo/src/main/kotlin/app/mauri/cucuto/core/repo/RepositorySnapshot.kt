package app.mauri.cucuto.core.repo

import app.mauri.cucuto.packspec.Issue
import app.mauri.cucuto.packspec.Limits
import app.mauri.cucuto.packspec.RepositoryEntry
import app.mauri.cucuto.packspec.RepositoryIndex
import okhttp3.HttpUrl

/**
 * A validated `repository.json`. [baseUrl] is the URL it was finally fetched from (after redirects); every relative
 * reference resolves against it (RFC 3986), as docs/pack-format.md requires.
 */
class RepositorySnapshot(
    /** The URL the repository is registered under (before redirects). */
    val indexUrl: HttpUrl,
    val baseUrl: HttpUrl,
    val index: RepositoryIndex,
    /** Validation warnings (none are defined for indexes yet, but the shared validator may add some). */
    val warnings: List<Issue>,
    /** The `ETag` the server sent, if any; the next fetch sends it as `If-None-Match`. */
    val etag: String?,
) {
    /** The entries with absolute URLs. */
    val entries: List<ResolvedEntry> =
        index.packs.mapNotNull { entry ->
            val download = resolve(entry.download.url) ?: return@mapNotNull null
            ResolvedEntry(
                entry = entry,
                downloadUrl = download,
                expected = ExpectedFile(entry.download.size, entry.download.sha256),
                coverUrl = entry.cover?.let(::resolve),
                supported = (entry.packFormatVersion ?: 1) <= Limits.FORMAT_VERSION,
            )
        }

    /** Resolves a reference from the index (cover, sprite, sample, download); null unless the result is `https://`. */
    fun resolve(reference: String): HttpUrl? = baseUrl.resolve(reference)?.takeIf { it.isHttps }

    override fun toString(): String = "RepositorySnapshot(${index.name}, ${entries.size} entries, ${baseUrl.redact()})"
}

/** One index entry with its URLs resolved. */
data class ResolvedEntry(
    val entry: RepositoryEntry,
    val downloadUrl: HttpUrl,
    val expected: ExpectedFile,
    val coverUrl: HttpUrl?,
    /** False when the file needs a newer pack format than this app reads; the UI hides or greys it out. */
    val supported: Boolean,
)

/** What the index promises about a download: checked before, during and after the transfer. */
data class ExpectedFile(
    val size: Long,
    /** Lowercase hex SHA-256. */
    val sha256: String,
)
