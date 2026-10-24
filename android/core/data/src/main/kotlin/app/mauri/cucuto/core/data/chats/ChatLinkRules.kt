package app.mauri.cucuto.core.data.chats

import java.text.Normalizer
import java.util.Locale

/**
 * A chat added from Android's contacts that is waiting for its first message: a 1:1 contact whose only messenger keys
 * are phone JIDs, which never reach the module (ADR 0009, ADR 0065).
 *
 * @property lookupKey Its Android contact, if still linked; without one, its name cannot be checked for namesakes.
 */
data class WaitingChat(
    val contactId: Long,
    val packageName: String,
    val name: String?,
    val lookupKey: String?,
)

/** A recent 1:1 sender whose key belongs to no contact yet. */
data class UnlinkedSighting(
    val key: ChatKey,
    val name: String?,
)

/**
 * What [ChatLinkRules.plan] decided.
 *
 * @property links Sightings to link to a waiting chat now (sighting key to contact ID).
 * @property questions Sightings that match waiting chats by name but not uniquely: the user picks (candidate contact
 *   IDs, in the order of the waiting chats).
 */
data class LinkPlan(
    val links: Map<ChatKey, Long> = emptyMap(),
    val questions: Map<ChatKey, List<Long>> = emptyMap(),
)

/**
 * The linking rules of ADR 0065 ("Linking rules and name collisions"). Pure, so every collision case is tested on the
 * JVM. A sighting links to a waiting chat by itself only when, within one messenger package:
 *
 * 1. their names are equal ([nameKey]);
 * 2. no other waiting chat has that name;
 * 3. no other unlinked sighting has that name;
 * 4. every WhatsApp entry with that name in Android's contacts belongs to the waiting chat's own Android contact, or to
 *    someone whose chat is already linked (no namesake the user did not add).
 *
 * Any other sighting with at least one waiting chat of the same name becomes a question for the user.
 */
object ChatLinkRules {
    /** A name as compared for linking: NFC, trimmed, lowercased. Null for a missing or blank name. */
    fun nameKey(name: String?): String? =
        name
            ?.let { Normalizer.normalize(it, Normalizer.Form.NFC) }
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() }

    /**
     * Plans the links. [namesakes] gives the lookup keys of the Android contacts whose WhatsApp entry for a package has
     * a name ([nameKey]); it returns null when contacts cannot be read, and then nothing links by itself (rule 4 cannot
     * hold), so every match becomes a question.
     */
    fun plan(
        waiting: List<WaitingChat>,
        sightings: List<UnlinkedSighting>,
        namesakes: ((packageName: String, nameKey: String) -> Set<String>?)?,
    ): LinkPlan {
        if (waiting.isEmpty() || sightings.isEmpty()) return LinkPlan()
        val waitingByName = waiting.groupBy { it.packageName to nameKey(it.name) }
        val sightingsByName = sightings.groupBy { it.key.packageName to nameKey(it.name) }
        val links = LinkedHashMap<ChatKey, Long>()
        val questions = LinkedHashMap<ChatKey, List<Long>>()
        for ((group, named) in sightingsByName) {
            val name = group.second ?: continue
            val candidates = waitingByName[group].orEmpty()
            if (candidates.isEmpty()) continue
            val only = candidates.singleOrNull()
            val sighting = named.singleOrNull()
            val unique =
                only != null &&
                    sighting != null &&
                    only.lookupKey != null &&
                    namesakes?.invoke(group.first, name)?.let { it == setOf(only.lookupKey) } == true
            if (unique) {
                links[sighting.key] = only.contactId
            } else {
                for (each in named) questions[each.key] = candidates.map { it.contactId }
            }
        }
        return LinkPlan(links, questions)
    }

    /**
     * Lookup keys by `(package, name key)`, from the WhatsApp entries of Android's contacts, for [plan]. Entries in
     * [settled] (`(package, lookup key)` of Android contacts whose chat is already linked) are left out: a sighting
     * that belongs to no contact cannot be them.
     */
    fun namesakesOf(
        entries: List<WhatsAppContact>,
        settled: Set<Pair<String, String>> = emptySet(),
    ): (String, String) -> Set<String> {
        val index =
            entries
                .filter { (it.packageName to it.lookupKey) !in settled }
                .mapNotNull { entry ->
                    nameKey(entry.displayName)?.let { (entry.packageName to it) to entry.lookupKey }
                }.groupBy({ it.first }, { it.second })
                .mapValues { it.value.toSet() }
        return { packageName, name -> index[packageName to name].orEmpty() }
    }
}
