package app.mauri.cucuto.packspec

import io.github.optimumcode.json.schema.JsonSchema
import io.github.optimumcode.json.schema.JsonSchemaLoader
import io.github.optimumcode.json.schema.ValidationError
import kotlinx.serialization.json.JsonElement

/** The JSON documents defined by `spec/`. */
enum class DocumentKind(val fileName: String) {
    PACK("pack.json"),
    BUNDLE("bundle.json"),
    REPOSITORY("repository.json"),
    BACKUP("backup.json"),
    BACKUP_ENVELOPE("envelope.json"),
}

/** JSON Schema 2020-12 validation against the schema files embedded from spec/. */
object SpecSchemas {
    const val BASE_ID = "https://cucuto.mauri.app/spec/v1/"

    /** Raw schema text by file name, for tools that publish or copy the schemas. */
    val sources: Map<String, String> get() = SpecResources.schemas

    /** Raw `spec/error-codes.json`. */
    val errorCodesJson: String get() = SpecResources.errorCodes

    private fun text(name: String) = SpecResources.schemas[name] ?: error("Schema $name is not embedded")

    private val schemas = mutableMapOf<DocumentKind, JsonSchema>()

    private fun schema(kind: DocumentKind): JsonSchema = schemas.getOrPut(kind) {
        val loader = JsonSchemaLoader.create().register(text("common.schema.json"))
        when (kind) {
            DocumentKind.PACK -> loader.fromDefinition(text("pack.schema.json"))
            DocumentKind.BUNDLE -> loader.fromDefinition(text("bundle.schema.json"))
            DocumentKind.REPOSITORY -> loader.register(text("pack.schema.json")).fromDefinition(text("repository.schema.json"))
            DocumentKind.BACKUP -> loader.register(text("pack.schema.json")).fromDefinition(text("backup.schema.json"))
            DocumentKind.BACKUP_ENVELOPE -> loader.register(text("pack.schema.json")).register(text("backup.schema.json"))
                .fromDefinition(
                    """{"${'$'}schema":"https://json-schema.org/draft/2020-12/schema",""" +
                        """"${'$'}ref":"${BASE_ID}backup.schema.json#/${'$'}defs/envelope"}""",
                )
        }
    }

    /** Validates [element] and returns one SCHEMA_VIOLATION issue per schema error (at most [limit]). */
    fun validate(kind: DocumentKind, element: JsonElement, file: String?, limit: Int = 50): List<Issue> {
        val errors = mutableListOf<ValidationError>()
        schema(kind).validate(element, errors::add)
        return errors
            .sortedWith(compareBy({ it.objectPath.toString() }, { it.message }))
            .take(limit)
            .map { Issue(ErrorCode.SCHEMA_VIOLATION, it.message, file = file, pointer = it.objectPath.toString().ifEmpty { "/" }) }
    }
}
