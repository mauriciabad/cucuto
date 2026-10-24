package app.mauri.cucuto.core.data.db

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * One entry of a contact's own ringtone rules (ADR 0074): a version of the character's ringtone and the condition
 * under which it rings, with the shape of the pack's `when` (docs/pack-format.md, "Ringtones and variants"). The same
 * shape is the backup's `ringtone.rule.rules[]`.
 *
 * @property variant The ringtone file (path from `pack.json`).
 * @property condition The `when` condition, or null: always matches.
 */
@Serializable
data class RingtoneRuleEntry(
    val variant: String,
    @SerialName("when") val condition: JsonObject? = null,
)

/**
 * Reads and writes [PhoneRingtoneEntity.rules]. The list is resolved like the pack's conditions: the matching entries,
 * the most specific, the first in order; the first entry when nothing matches. Versions not listed never ring.
 */
object RingtoneRules {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    private val serializer = ListSerializer(RingtoneRuleEntry.serializer())

    /** The stored text of [rules], or null for an empty list (no rules of its own). */
    fun encode(rules: List<RingtoneRuleEntry>): String? = if (rules.isEmpty()) null else json.encodeToString(serializer, rules)

    /** The rules in [text]; empty when there are none or the text cannot be read (the pack's rules then apply). */
    fun decode(text: String?): List<RingtoneRuleEntry> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(serializer, text)
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }
}
