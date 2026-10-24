package app.mauri.cucuto.packs

import androidx.annotation.StringRes
import app.mauri.cucuto.R
import app.mauri.cucuto.core.pack.FailureReason
import app.mauri.cucuto.core.repo.RepoError
import app.mauri.cucuto.packspec.ErrorCode
import app.mauri.cucuto.packspec.Issue
import app.mauri.cucuto.packspec.ValidationReport

/**
 * Why an import stopped, in plain words: a [title], one [summary] sentence, the distinct problems ([items], at most
 * [MAX_ITEMS], with [hiddenItems] more) and, for pack authors, the technical [details] (codes from
 * `spec/error-codes.json`, file paths and the validator's English messages).
 */
data class ImportProblem(
    @param:StringRes val title: Int,
    @param:StringRes val summary: Int,
    val items: List<ProblemItem> = emptyList(),
    val hiddenItems: Int = 0,
    val details: List<String> = emptyList(),
) {
    companion object {
        const val MAX_ITEMS = 5
    }
}

/** One kind of problem: its plain-words [message], the first [file] it concerns and how many [moreFiles] share it. */
data class ProblemItem(
    val code: ErrorCode,
    @param:StringRes val message: Int,
    val file: String?,
    val moreFiles: Int,
)

/** The problem for a file the importer rejected. */
fun problemFor(
    report: ValidationReport,
    detected: DetectedFile = DetectedFile.UNKNOWN,
): ImportProblem {
    val details = report.issues.map(Issue::toString)
    val codes = report.errors.map { it.code }.toSet()
    return when {
        detected == DetectedFile.BACKUP -> {
            ImportProblem(R.string.import_problem_backup_title, R.string.import_problem_backup_summary)
        }

        codes == setOf(ErrorCode.MANIFEST_MISSING) -> {
            ImportProblem(
                R.string.import_problem_not_pack_title,
                R.string.import_problem_not_pack_summary,
                details = details,
            )
        }

        ErrorCode.FORMAT_VERSION_UNSUPPORTED in codes -> {
            ImportProblem(
                R.string.import_problem_newer_title,
                R.string.import_problem_newer_summary,
                details = details,
            )
        }

        else -> {
            val groups = report.errors.groupBy { it.code }.map { (code, issues) -> itemFor(code, issues) }
            ImportProblem(
                title = R.string.import_problem_invalid_title,
                summary = R.string.import_problem_invalid_summary,
                items = groups.take(ImportProblem.MAX_ITEMS),
                hiddenItems = (groups.size - ImportProblem.MAX_ITEMS).coerceAtLeast(0),
                details = details,
            )
        }
    }
}

/** The problem for an import that failed outside the file's content (reading, storage). */
fun problemFor(
    reason: FailureReason,
    message: String,
): ImportProblem =
    ImportProblem(
        title = R.string.import_problem_failed_title,
        summary = plainWords(reason),
        details = listOf("${reason.name}: $message"),
    )

/** Plain-words lines for the warnings of a valid file, one per code, in order of first appearance. */
fun warningItems(warnings: List<Issue>): List<ProblemItem> =
    warnings
        .filterNot { it.code in QUIET_WARNINGS }
        .groupBy { it.code }
        .map { (code, issues) -> itemFor(code, issues) }

private fun itemFor(
    code: ErrorCode,
    issues: List<Issue>,
): ProblemItem {
    val files = issues.mapNotNull { it.file }.distinct()
    return ProblemItem(code, plainWords(code), files.firstOrNull(), (files.size - 1).coerceAtLeast(0))
}

/** Warnings that need no attention: skipped OS files (`.DS_Store`) and unused media. */
private val QUIET_WARNINGS = setOf(ErrorCode.IGNORED_ENTRY, ErrorCode.UNREFERENCED_FILE)

/** One sentence per stable code (spec/error-codes.json), for people who never saw a pack.json. */
@StringRes
fun plainWords(code: ErrorCode): Int =
    when (code) {
        ErrorCode.ARCHIVE_UNREADABLE -> R.string.error_archive_unreadable
        ErrorCode.ARCHIVE_UNSUPPORTED -> R.string.error_archive_unsupported
        ErrorCode.COMPRESSION_RATIO_EXCEEDED -> R.string.error_compression_ratio_exceeded
        ErrorCode.DUPLICATE_ENTRY -> R.string.error_duplicate_entry
        ErrorCode.UNSAFE_PATH -> R.string.error_unsafe_path
        ErrorCode.UNSAFE_ENTRY -> R.string.error_unsafe_entry
        ErrorCode.INVALID_FILE_NAME -> R.string.error_invalid_file_name
        ErrorCode.EXECUTABLE_FILE -> R.string.error_executable_file
        ErrorCode.UNKNOWN_FILE_TYPE -> R.string.error_unknown_file_type
        ErrorCode.FILE_TOO_LARGE -> R.string.error_file_too_large
        ErrorCode.TOO_MANY_FILES -> R.string.error_too_many_files
        ErrorCode.UNPACKED_TOO_LARGE -> R.string.error_unpacked_too_large
        ErrorCode.MANIFEST_MISSING -> R.string.error_manifest_missing
        ErrorCode.MANIFEST_AMBIGUOUS -> R.string.error_manifest_ambiguous
        ErrorCode.IGNORED_ENTRY -> R.string.error_ignored_entry
        ErrorCode.LARGE_PACK -> R.string.error_large_pack
        ErrorCode.MANIFEST_INVALID_JSON -> R.string.error_manifest_invalid_json
        ErrorCode.FORMAT_VERSION_UNSUPPORTED -> R.string.error_format_version_unsupported
        ErrorCode.SCHEMA_VIOLATION -> R.string.error_schema_violation
        ErrorCode.DUPLICATE_ID -> R.string.error_duplicate_id
        ErrorCode.FILE_MISSING -> R.string.error_file_missing
        ErrorCode.FILE_TYPE_MISMATCH -> R.string.error_file_type_mismatch
        ErrorCode.BUNDLE_PACK_MISSING -> R.string.error_bundle_pack_missing
        ErrorCode.BUNDLE_LAYOUT_INVALID -> R.string.error_bundle_layout_invalid
        ErrorCode.HAPTIC_INVALID -> R.string.error_haptic_invalid
        ErrorCode.WHEN_INVALID -> R.string.error_when_invalid
        ErrorCode.LOOP_POINTS_INVALID -> R.string.error_loop_points_invalid
        ErrorCode.LICENSE_NOT_SPDX -> R.string.error_license_not_spdx
        ErrorCode.UNREFERENCED_FILE -> R.string.error_unreferenced_file
        ErrorCode.MEDIA_UNDECODABLE -> R.string.error_media_undecodable
        ErrorCode.CLIP_DURATION_OUT_OF_RANGE -> R.string.error_clip_duration_out_of_range
        ErrorCode.RINGTONE_DURATION_OUT_OF_RANGE -> R.string.error_ringtone_duration_out_of_range
        ErrorCode.IMAGE_TOO_LARGE -> R.string.error_image_too_large
        ErrorCode.SVG_UNSAFE -> R.string.error_svg_unsafe
        ErrorCode.DOWNLOAD_SIZE_MISMATCH -> R.string.error_download_size_mismatch
        ErrorCode.DOWNLOAD_HASH_MISMATCH -> R.string.error_download_hash_mismatch
        ErrorCode.BACKUP_DECRYPTION_FAILED -> R.string.error_backup_decryption_failed
        ErrorCode.PACK_DOWNGRADE -> R.string.error_pack_downgrade
        ErrorCode.PACK_SAME_VERSION -> R.string.error_pack_same_version
    }

@StringRes
fun plainWords(reason: FailureReason): Int =
    when (reason) {
        FailureReason.SOURCE_UNREADABLE -> R.string.import_failure_source_unreadable
        FailureReason.SOURCE_TOO_LARGE -> R.string.import_failure_source_too_large
        FailureReason.NOT_ENOUGH_STORAGE -> R.string.import_failure_not_enough_storage
        FailureReason.STORAGE_ERROR -> R.string.import_failure_storage_error
        FailureReason.NOT_PREPARED -> R.string.import_failure_not_prepared
        FailureReason.UNEXPECTED -> R.string.import_failure_unexpected
    }

/** The problem for a download that failed before the importer saw the file. */
fun problemFor(error: RepoError): ImportProblem =
    ImportProblem(
        title = R.string.download_problem_title,
        summary = plainWords(error),
        details =
            when (error) {
                is RepoError.InvalidIndex -> error.report.issues.map(Issue::toString)
                else -> listOfNotNull(error.code?.name, error.toString())
            },
    )

/** One sentence for a failed repository request (index, preview or download). */
@StringRes
fun plainWords(error: RepoError): Int =
    when (error) {
        is RepoError.InsecureUrl -> {
            R.string.repo_error_insecure
        }

        is RepoError.TooManyRedirects -> {
            R.string.repo_error_redirects
        }

        is RepoError.Unauthorized -> {
            R.string.repo_error_unauthorized
        }

        is RepoError.HttpStatus -> {
            when {
                error.status == HTTP_NOT_FOUND || error.status == HTTP_GONE -> R.string.repo_error_not_found
                error.status >= HTTP_SERVER_ERROR -> R.string.repo_error_server
                else -> R.string.repo_error_http
            }
        }

        is RepoError.TooLarge -> {
            R.string.repo_error_too_large
        }

        is RepoError.SizeMismatch -> {
            plainWords(ErrorCode.DOWNLOAD_SIZE_MISMATCH)
        }

        is RepoError.HashMismatch -> {
            plainWords(ErrorCode.DOWNLOAD_HASH_MISMATCH)
        }

        is RepoError.InvalidIndex -> {
            R.string.repo_error_invalid_index
        }

        is RepoError.Network -> {
            R.string.repo_error_network
        }

        is RepoError.Storage -> {
            R.string.import_failure_storage_error
        }
    }

private const val HTTP_NOT_FOUND = 404
private const val HTTP_GONE = 410
private const val HTTP_SERVER_ERROR = 500
