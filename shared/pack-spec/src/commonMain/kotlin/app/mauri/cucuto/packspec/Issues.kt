package app.mauri.cucuto.packspec

/** How bad an [Issue] is. Errors block the import; warnings are shown and the import continues. */
enum class Severity { ERROR, WARNING }

/**
 * Validation stages, in order. A validator runs every check of a stage, then stops if that stage produced any error, so
 * all implementations report the same set of codes for the same input.
 */
enum class Stage { ARCHIVE, LAYOUT, MANIFEST, REFERENCES, MEDIA, RUNTIME }

/**
 * Stable codes from `spec/error-codes.json`. Never rename or reuse a code; a test checks this enum against the JSON.
 */
enum class ErrorCode(val severity: Severity, val stage: Stage) {
    ARCHIVE_UNREADABLE(Severity.ERROR, Stage.ARCHIVE),
    ARCHIVE_UNSUPPORTED(Severity.ERROR, Stage.ARCHIVE),
    COMPRESSION_RATIO_EXCEEDED(Severity.ERROR, Stage.ARCHIVE),
    DUPLICATE_ENTRY(Severity.ERROR, Stage.LAYOUT),
    UNSAFE_PATH(Severity.ERROR, Stage.LAYOUT),
    UNSAFE_ENTRY(Severity.ERROR, Stage.LAYOUT),
    INVALID_FILE_NAME(Severity.ERROR, Stage.LAYOUT),
    EXECUTABLE_FILE(Severity.ERROR, Stage.LAYOUT),
    UNKNOWN_FILE_TYPE(Severity.ERROR, Stage.LAYOUT),
    FILE_TOO_LARGE(Severity.ERROR, Stage.LAYOUT),
    TOO_MANY_FILES(Severity.ERROR, Stage.LAYOUT),
    UNPACKED_TOO_LARGE(Severity.ERROR, Stage.LAYOUT),
    MANIFEST_MISSING(Severity.ERROR, Stage.LAYOUT),
    MANIFEST_AMBIGUOUS(Severity.ERROR, Stage.LAYOUT),
    IGNORED_ENTRY(Severity.WARNING, Stage.LAYOUT),
    LARGE_PACK(Severity.WARNING, Stage.LAYOUT),
    MANIFEST_INVALID_JSON(Severity.ERROR, Stage.MANIFEST),
    FORMAT_VERSION_UNSUPPORTED(Severity.ERROR, Stage.MANIFEST),
    SCHEMA_VIOLATION(Severity.ERROR, Stage.MANIFEST),
    DUPLICATE_ID(Severity.ERROR, Stage.REFERENCES),
    FILE_MISSING(Severity.ERROR, Stage.REFERENCES),
    FILE_TYPE_MISMATCH(Severity.ERROR, Stage.REFERENCES),
    BUNDLE_PACK_MISSING(Severity.ERROR, Stage.REFERENCES),
    BUNDLE_LAYOUT_INVALID(Severity.ERROR, Stage.REFERENCES),
    HAPTIC_INVALID(Severity.ERROR, Stage.REFERENCES),
    WHEN_INVALID(Severity.ERROR, Stage.REFERENCES),
    LOOP_POINTS_INVALID(Severity.ERROR, Stage.REFERENCES),
    LICENSE_NOT_SPDX(Severity.WARNING, Stage.REFERENCES),
    UNREFERENCED_FILE(Severity.WARNING, Stage.REFERENCES),
    MEDIA_UNDECODABLE(Severity.ERROR, Stage.MEDIA),
    CLIP_DURATION_OUT_OF_RANGE(Severity.ERROR, Stage.MEDIA),
    RINGTONE_DURATION_OUT_OF_RANGE(Severity.ERROR, Stage.MEDIA),
    IMAGE_TOO_LARGE(Severity.ERROR, Stage.MEDIA),
    SVG_UNSAFE(Severity.ERROR, Stage.MEDIA),
    DOWNLOAD_SIZE_MISMATCH(Severity.ERROR, Stage.RUNTIME),
    DOWNLOAD_HASH_MISMATCH(Severity.ERROR, Stage.RUNTIME),
    BACKUP_DECRYPTION_FAILED(Severity.ERROR, Stage.RUNTIME),
    PACK_DOWNGRADE(Severity.WARNING, Stage.RUNTIME),
    PACK_SAME_VERSION(Severity.WARNING, Stage.RUNTIME),
}

/**
 * One finding. [file] is a path inside the pack or bundle (or the input file for indexes and backups); [pointer] is a
 * JSON Pointer into the manifest when the issue concerns a manifest value. [message] is English and not part of the
 * contract: only [code] is stable.
 */
data class Issue(
    val code: ErrorCode,
    val message: String,
    val file: String? = null,
    val pointer: String? = null,
) {
    val severity: Severity get() = code.severity

    override fun toString(): String = buildString {
        append(if (severity == Severity.ERROR) "error" else "warning")
        append(' ').append(code.name)
        if (file != null) append(' ').append(file)
        if (pointer != null) append(' ').append(pointer)
        append(": ").append(message)
    }
}

/** Result of a validation run. */
class ValidationReport(val issues: List<Issue>) {
    val errors: List<Issue> get() = issues.filter { it.severity == Severity.ERROR }
    val warnings: List<Issue> get() = issues.filter { it.severity == Severity.WARNING }
    val isValid: Boolean get() = errors.isEmpty()

    /** Distinct error codes, sorted by name: what golden fixtures compare. */
    fun errorCodes(): List<String> = errors.map { it.code.name }.distinct().sorted()

    /** Distinct warning codes, sorted by name. */
    fun warningCodes(): List<String> = warnings.map { it.code.name }.distinct().sorted()
}

/** Collects issues and tracks whether the current stage failed. */
internal class IssueSink {
    val issues = mutableListOf<Issue>()
    private var errorsAtStageStart = 0

    fun add(code: ErrorCode, message: String, file: String? = null, pointer: String? = null) {
        issues += Issue(code, message, file, pointer)
    }

    fun beginStage() {
        errorsAtStageStart = errorCount()
    }

    /** True if the stage that began with [beginStage] reported an error. */
    fun stageFailed(): Boolean = errorCount() > errorsAtStageStart

    private fun errorCount() = issues.count { it.severity == Severity.ERROR }

    fun report() = ValidationReport(issues.toList())
}
