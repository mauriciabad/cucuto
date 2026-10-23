package app.mauri.cucuto.core.repo

import okhttp3.Credentials
import okhttp3.HttpUrl

/**
 * Per-repository HTTP credentials (docs/project-brief.md, "Pack repositories"). They are sent only over HTTPS and only
 * to the repository's origin (scheme, host and port), never after a redirect that leaves it. [toString] never shows
 * the secret, so a stray log line or crash report cannot leak it.
 */
sealed interface RepositoryCredentials {
    /** The value of the `Authorization` header. */
    fun authorizationHeader(): String

    /** HTTP Basic (RFC 7617), UTF-8. */
    data class Basic(
        val username: String,
        val password: String,
    ) : RepositoryCredentials {
        init {
            require(username.isNotEmpty() && ':' !in username && username.none { it.isISOControl() }) {
                "The username must be non-empty, without ':' or control characters"
            }
            require(password.none { it.isISOControl() }) { "The password must not contain control characters" }
        }

        override fun authorizationHeader(): String = Credentials.basic(username, password, Charsets.UTF_8)

        override fun toString(): String = "Basic(username=$REDACTED, password=$REDACTED)"
    }

    /** A bearer token header (RFC 6750). */
    data class Bearer(
        val token: String,
    ) : RepositoryCredentials {
        init {
            // RFC 6750 b64token characters are a subset of visible ASCII; anything else could inject headers.
            require(token.isNotEmpty() && token.all { it in '!'..'~' }) { "The token must be visible ASCII characters" }
        }

        override fun authorizationHeader(): String = "Bearer $token"

        override fun toString(): String = "Bearer(token=$REDACTED)"
    }

    companion object {
        const val REDACTED = "██"
    }
}

/** How to reach a repository: its index URL and optional credentials. [toString] redacts the credentials. */
data class RepositoryAccess(
    val indexUrl: HttpUrl,
    val credentials: RepositoryCredentials? = null,
) {
    init {
        require(indexUrl.isHttps) { "Repositories are HTTPS only" }
    }

    override fun toString(): String = "RepositoryAccess(indexUrl=${indexUrl.redact()}, credentials=$credentials)"
}

/** Same scheme, host and port: the only requests that may carry a repository's credentials. */
internal fun HttpUrl.sameOrigin(other: HttpUrl): Boolean =
    scheme == other.scheme && host == other.host && port == other.port
