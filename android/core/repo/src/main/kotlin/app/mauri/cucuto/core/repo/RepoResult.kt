package app.mauri.cucuto.core.repo

import app.mauri.cucuto.packspec.ErrorCode
import app.mauri.cucuto.packspec.ValidationReport
import java.io.IOException

/** The outcome of a repository request. Failures never throw: the UI shows [RepoError] and offers a retry. */
sealed interface RepoResult<out T> {
    data class Success<T>(
        val value: T,
    ) : RepoResult<T>

    data class Failure(
        val error: RepoError,
    ) : RepoResult<Nothing>

    fun getOrNull(): T? = (this as? Success)?.value
}

/** Why a repository request failed. URLs are redacted (no path, query or user info) so errors are safe to log. */
sealed interface RepoError {
    /** The stable code from spec/error-codes.json, when the failure has one. */
    val code: ErrorCode? get() = null

    /** A URL (the start, a redirect target or an index reference) is not `https://`. */
    data class InsecureUrl(
        val redactedUrl: String,
    ) : RepoError

    /** More redirects than allowed, which includes redirect loops. */
    data class TooManyRedirects(
        val limit: Int,
    ) : RepoError

    /** 401 or 403: missing or wrong credentials. */
    data class Unauthorized(
        val status: Int,
    ) : RepoError

    /** Any other unexpected HTTP status (404, 5xx, a redirect without a usable `Location`). */
    data class HttpStatus(
        val status: Int,
    ) : RepoError

    /** The advertised or actual size is above the cap ([limit] bytes) for this kind of file. */
    data class TooLarge(
        val limit: Long,
        val size: Long?,
    ) : RepoError

    /** The download does not have the size the index advertised. [actual] is a lower bound when the transfer was cut. */
    data class SizeMismatch(
        val expected: Long,
        val actual: Long,
    ) : RepoError {
        override val code get() = ErrorCode.DOWNLOAD_SIZE_MISMATCH
    }

    /** The download's SHA-256 differs from the index. */
    data class HashMismatch(
        val expected: String,
        val actual: String,
    ) : RepoError {
        override val code get() = ErrorCode.DOWNLOAD_HASH_MISMATCH
    }

    /** The index breaks the repository schema; [report] has every issue with its stable code. */
    data class InvalidIndex(
        val report: ValidationReport,
    ) : RepoError

    /** Connection, TLS or timeout failure. */
    data class Network(
        val message: String,
    ) : RepoError

    /** Writing to app storage failed. */
    data class Storage(
        val message: String,
    ) : RepoError
}

/** Carries a [RepoError] out of nested request code; converted to [RepoResult.Failure] at the API boundary. */
internal class RepoException(
    val error: RepoError,
) : IOException(error.toString())
