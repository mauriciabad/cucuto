@file:OptIn(ExperimentalJsExport::class)

package app.mauri.cucuto.packspec

import app.mauri.cucuto.packspec.zip.ByteArrayInput
import app.mauri.cucuto.packspec.zip.CommonInflater

// The browser API of pack-spec (ADR 0022). The website imports the compiled ES module through
// web/src/lib/pack-spec/, which adds TypeScript string unions on top. Keep this surface small and stable: it is the
// contract between the Kotlin rules and the site. Kotlin ByteArray is a JavaScript Int8Array, so archive bytes are
// passed without copying. No Long values: exported declarations use Int and Double.

/** One finding, as in `spec/error-codes.json`. Only [code] is stable; [message] is English and may change. */
@JsExport
class PackIssue internal constructor(
    /** A code from spec/error-codes.json, for example `FILE_MISSING`. */
    val code: String,
    /** `error` or `warning`. */
    val severity: String,
    /** `archive`, `layout`, `manifest`, `references`, `media` or `runtime`. */
    val stage: String,
    val message: String,
    /** Path inside the pack or bundle, when the issue concerns a file. */
    val file: String?,
    /** JSON Pointer into the manifest, when the issue concerns a manifest value. */
    val pointer: String?,
)

/** A pack found in a validated archive. [root] is "" for a single pack, or its folder inside a bundle. */
@JsExport
class PackSummary internal constructor(
    val root: String,
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val license: String,
    val characters: Int,
)

/** The result of a validation. [errorCodes] and [warningCodes] are distinct and sorted: what golden fixtures compare. */
@JsExport
class PackSpecResult internal constructor(
    val valid: Boolean,
    val issues: Array<PackIssue>,
    val errorCodes: Array<String>,
    val warningCodes: Array<String>,
    /** `pack`, `bundle`, or null when the manifests could not be read (and for standalone documents). */
    val kind: String?,
    val packs: Array<PackSummary>,
)

/** The newest pack format version this build reads. */
@JsExport
fun packFormatVersion(): Int = Limits.FORMAT_VERSION

/**
 * Validates a pack or bundle archive (any `.zip`; the type comes from the manifest at its root). Runs every stage of
 * docs/pack-format.md, media headers included, exactly like packer and the app.
 */
@JsExport
fun validateArchive(bytes: ByteArray): PackSpecResult =
    PackValidator().validateArchive(ByteArrayInput(bytes), CommonInflater).toResult()

/**
 * Validates an unpacked pack or bundle: [paths] (relative, '/'-separated, for example from a folder picker or the pack
 * creator) and their [contents], in the same order.
 */
@JsExport
fun validateFiles(paths: Array<String>, contents: Array<ByteArray>): PackSpecResult {
    require(paths.size == contents.size) { "paths and contents differ in length" }
    return PackValidator().validate(InMemoryPackSource(paths.indices.associate { paths[it] to contents[it] })).toResult()
}

/** Validates a standalone document. [kind] is `repository`, `backup` or `backup-envelope`. */
@JsExport
fun validateDocument(kind: String, bytes: ByteArray): PackSpecResult {
    val documentKind = when (kind) {
        "repository" -> DocumentKind.REPOSITORY
        "backup" -> DocumentKind.BACKUP
        "backup-envelope" -> DocumentKind.BACKUP_ENVELOPE
        else -> throw IllegalArgumentException("Unknown document kind $kind")
    }
    return DocumentValidator.validate(documentKind, bytes).toResult(null, emptyArray())
}

internal fun PackValidation.toResult(): PackSpecResult {
    val kind = when (content) {
        is PackContent.Single -> "pack"
        is PackContent.Bundle -> "bundle"
        null -> null
    }
    val packs = content?.packs.orEmpty().map { (root, m) ->
        PackSummary(root, m.id, m.name, m.version, m.author, m.license, m.characters.size)
    }
    return report.toResult(kind, packs.toTypedArray())
}

private fun ValidationReport.toResult(kind: String?, packs: Array<PackSummary>) = PackSpecResult(
    valid = isValid,
    issues = issues.map {
        PackIssue(it.code.name, it.severity.name.lowercase(), it.code.stage.name.lowercase(), it.message, it.file, it.pointer)
    }.toTypedArray(),
    errorCodes = errorCodes().toTypedArray(),
    warningCodes = warningCodes().toTypedArray(),
    kind = kind,
    packs = packs,
)
