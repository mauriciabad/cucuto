package app.mauri.cucuto.core.repo

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.URLEncoder

/** The project website's repository, added on first start (docs/project-brief.md, "Pack repositories"). */
const val DEFAULT_REPOSITORY_URL = "https://cucuto.mauri.app/repo/index.json"

/** The index file the app looks for when the user adds a folder URL (docs/pack-format.md, "Pack repositories"). */
const val INDEX_FILE_NAME = "repository.json"

/** What a pasted URL, QR code or deep link points at. [url] is always `https://`, without user info or fragment. */
sealed interface RepositoryLink {
    val url: HttpUrl

    /** A `repository.json` index (any `.json` file, or `repository.json` appended to a folder URL). */
    data class Index(
        override val url: HttpUrl,
    ) : RepositoryLink

    /** A direct link to one `.soundpack.zip` (any `.zip`), downloaded once through the importer. */
    data class DirectPack(
        override val url: HttpUrl,
    ) : RepositoryLink
}

/** Why a link was rejected. */
enum class LinkError {
    /** Not a URL at all, or a scheme other than `https`/`http`. */
    NOT_A_URL,

    /** A plain `http://` URL. Repositories are HTTPS only. */
    NOT_HTTPS,

    /** `https://user:password@host/`: credentials belong in the encrypted credential store, not in the URL. */
    CREDENTIALS_IN_URL,

    /** Not a `cucuto://repo/add` deep link. */
    NOT_A_DEEP_LINK,

    /** A deep link without exactly one `url` parameter. */
    MISSING_URL,
}

sealed interface LinkResult {
    data class Ok(
        val link: RepositoryLink,
    ) : LinkResult

    data class Error(
        val error: LinkError,
    ) : LinkResult
}

/**
 * Parses what users add: a folder URL (`https://example.com/packs/` → `…/packs/repository.json`), an index URL
 * (`…/index.json`), a direct pack link (`…/cats.soundpack.zip`) or the deep link `cucuto://repo/add?url=…` that
 * websites use for "Add to app".
 */
object RepositoryLinks {
    const val DEEP_LINK_SCHEME = "cucuto"
    const val DEEP_LINK_HOST = "repo"
    const val DEEP_LINK_PATH = "/add"
    const val DEEP_LINK_URL_PARAMETER = "url"

    /** The default repository as a link. */
    val default: RepositoryLink.Index get() = RepositoryLink.Index(DEFAULT_REPOSITORY_URL.toHttpUrl())

    /**
     * Parses a pasted or scanned URL. A missing scheme means `https://`. A last path segment ending in `.zip` is a
     * direct pack link, one ending in `.json` is an index, and anything else is a folder that gets `repository.json`.
     */
    fun parse(input: String): LinkResult {
        val text = input.trim()
        if (text.isEmpty() ||
            text.any { it.isWhitespace() || it.isISOControl() }
        ) {
            return LinkResult.Error(LinkError.NOT_A_URL)
        }
        val schemeEnd = text.indexOf("://")
        val withScheme =
            when {
                schemeEnd < 0 -> {
                    "https://$text"
                }

                text.regionMatches(0, "https", 0, schemeEnd, ignoreCase = true) && schemeEnd == 5 -> {
                    text
                }

                text.regionMatches(0, "http", 0, schemeEnd, ignoreCase = true) && schemeEnd == 4 -> {
                    return LinkResult.Error(LinkError.NOT_HTTPS)
                }

                else -> {
                    return LinkResult.Error(LinkError.NOT_A_URL)
                }
            }
        val parsed = withScheme.toHttpUrlOrNull() ?: return LinkResult.Error(LinkError.NOT_A_URL)
        if (!parsed.isHttps) return LinkResult.Error(LinkError.NOT_HTTPS)
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            return LinkResult.Error(LinkError.CREDENTIALS_IN_URL)
        }
        val url = parsed.newBuilder().fragment(null).build()
        val last = url.pathSegments.last()
        val lower = last.lowercase()
        val link =
            when {
                lower.endsWith(".zip") -> {
                    RepositoryLink.DirectPack(url)
                }

                lower.endsWith(".json") -> {
                    RepositoryLink.Index(url)
                }

                last.isEmpty() -> {
                    RepositoryLink.Index(
                        url.newBuilder().setPathSegment(url.pathSize - 1, INDEX_FILE_NAME).build(),
                    )
                }

                else -> {
                    RepositoryLink.Index(url.newBuilder().addPathSegment(INDEX_FILE_NAME).build())
                }
            }
        return LinkResult.Ok(link)
    }

    /**
     * Parses `cucuto://repo/add?url=<percent-encoded URL>`. The URL inside goes through [parse], so the same rules
     * apply. Other parameters are ignored. Links from websites are untrusted: the UI shows the host and a warning
     * before adding.
     */
    fun parseDeepLink(link: String): LinkResult {
        val uri =
            try {
                URI(link.trim())
            } catch (e: URISyntaxException) {
                return LinkResult.Error(LinkError.NOT_A_DEEP_LINK)
            }
        val isAddLink =
            uri.scheme.equals(DEEP_LINK_SCHEME, ignoreCase = true) &&
                uri.host.equals(DEEP_LINK_HOST, ignoreCase = true) &&
                (uri.rawPath == DEEP_LINK_PATH || uri.rawPath == "$DEEP_LINK_PATH/")
        if (!isAddLink) return LinkResult.Error(LinkError.NOT_A_DEEP_LINK)
        val values =
            uri.rawQuery
                .orEmpty()
                .split('&')
                .filter { it.substringBefore('=') == DEEP_LINK_URL_PARAMETER && it.contains('=') }
                .map { it.substringAfter('=') }
        val value = values.singleOrNull() ?: return LinkResult.Error(LinkError.MISSING_URL)
        val decoded =
            try {
                // Percent-decoding only: a URL has no spaces, so a literal "+" stays a "+".
                URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                return LinkResult.Error(LinkError.NOT_A_URL)
            }
        if (decoded.isBlank()) return LinkResult.Error(LinkError.MISSING_URL)
        return parse(decoded)
    }

    /** The "Add to app" deep link for a repository or pack URL. */
    fun deepLinkFor(url: HttpUrl): String =
        "$DEEP_LINK_SCHEME://$DEEP_LINK_HOST$DEEP_LINK_PATH?$DEEP_LINK_URL_PARAMETER=" +
            URLEncoder.encode(url.toString(), Charsets.UTF_8)
}
