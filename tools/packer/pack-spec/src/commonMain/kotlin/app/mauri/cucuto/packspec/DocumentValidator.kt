package app.mauri.cucuto.packspec

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Validates standalone JSON documents: repository indexes, backups and backup envelopes. */
object DocumentValidator {
    /** Size caps for standalone documents. */
    const val REPOSITORY_BYTES = 16 * Limits.MiB
    const val BACKUP_BYTES = 64 * Limits.MiB

    fun validate(kind: DocumentKind, bytes: ByteArray, file: String? = kind.fileName): ValidationReport {
        require(kind != DocumentKind.PACK && kind != DocumentKind.BUNDLE) { "Use PackValidator for packs and bundles" }
        val sink = IssueSink()
        val max = if (kind == DocumentKind.REPOSITORY) REPOSITORY_BYTES else BACKUP_BYTES
        if (bytes.size > max) {
            sink.add(ErrorCode.MANIFEST_INVALID_JSON, "Larger than $max bytes", file = file)
            return sink.report()
        }
        sink.beginStage()
        val element = parseDocument(bytes, file, sink) ?: return sink.report()
        if (!checkFormatVersion(element, file, sink)) return sink.report()
        sink.issues += SpecSchemas.validate(kind, element, file)
        if (sink.stageFailed()) return sink.report()

        val root = element as JsonObject
        when (kind) {
            DocumentKind.REPOSITORY -> {
                val entries = root["packs"] as JsonArray
                ids(entries).let(PackValidator::duplicates).forEach {
                    sink.add(ErrorCode.DUPLICATE_ID, "Entry ID $it appears more than once", file = file, pointer = "/packs")
                }
                entries.forEachIndexed { i, entry ->
                    val chars = (entry as JsonObject)["characters"] as? JsonArray ?: return@forEachIndexed
                    ids(chars).let(PackValidator::duplicates).forEach {
                        sink.add(ErrorCode.DUPLICATE_ID, "Character ID $it appears more than once", file = file, pointer = "/packs/$i/characters")
                    }
                }
            }
            DocumentKind.BACKUP -> {
                ids(root["contacts"] as JsonArray).let(PackValidator::duplicates).forEach {
                    sink.add(ErrorCode.DUPLICATE_ID, "Contact ID $it appears more than once", file = file, pointer = "/contacts")
                }
                ids(root["packs"] as JsonArray).let(PackValidator::duplicates).forEach {
                    sink.add(ErrorCode.DUPLICATE_ID, "Pack ID $it appears more than once", file = file, pointer = "/packs")
                }
            }
            else -> {}
        }
        return sink.report()
    }

    private fun ids(array: JsonArray): List<String> =
        array.mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull }
}
