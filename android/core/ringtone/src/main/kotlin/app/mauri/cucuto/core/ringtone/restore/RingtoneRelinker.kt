package app.mauri.cucuto.core.ringtone.restore

import app.mauri.cucuto.core.data.db.ContactRingtoneWriteEntity
import app.mauri.cucuto.core.ringtone.contacts.AndroidContact
import app.mauri.cucuto.core.ringtone.contacts.AndroidContacts
import app.mauri.cucuto.core.ringtone.store.PhoneRingtoneStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * A phone ringtone a backup restore put in the database, waiting for its Android contact on this phone (ADR 0057).
 * Nothing here names a person beyond what the database already holds; never log it.
 *
 * @property contactId The Cucuto contact.
 * @property displayName The Cucuto contact's name, for the restore summary.
 * @property androidName The Android contact's name on the phone that wrote the backup (a tie-break when matching).
 * @property originalRingtone The backup's undo record: the Android contact's own ringtone before Cucuto (null: the
 *   default ringtone).
 * @property writtenUri The backup's undo record: the ring file Cucuto wrote last on that phone, or null when the backup
 *   has no undo record.
 */
data class RestoredRingtone(
    val contactId: Long,
    val displayName: String? = null,
    val androidName: String? = null,
    val originalRingtone: String? = null,
    val writtenUri: String? = null,
) {
    companion object {
        /** JSON for the pending list kept in settings until the contacts permission is granted. */
        fun encode(items: List<RestoredRingtone>): String =
            buildJsonArray {
                for (item in items) {
                    add(
                        buildJsonObject {
                            put("contactId", item.contactId)
                            item.displayName?.let { put("displayName", it) }
                            item.androidName?.let { put("androidName", it) }
                            item.originalRingtone?.let { put("originalRingtone", it) }
                            item.writtenUri?.let { put("writtenUri", it) }
                        },
                    )
                }
            }.toString()

        /** The list [encode] wrote; empty for anything unreadable (a pending relink is a convenience, not data). */
        fun decode(json: String?): List<RestoredRingtone> {
            if (json.isNullOrBlank()) return emptyList()
            val array =
                runCatching { Json.parseToJsonElement(json) as? JsonArray }.getOrNull()
                    ?: return emptyList()
            return array.mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null

                fun text(key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                val id = o["contactId"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                RestoredRingtone(
                    id,
                    text("displayName"),
                    text("androidName"),
                    text("originalRingtone"),
                    text("writtenUri"),
                )
            }
        }
    }
}

/** Where the relinker reads and changes Cucuto contacts' Android links. */
interface RingtoneLinks {
    /** The lookup key [contactId] is linked to, or null. */
    suspend fun linkedKey(contactId: Long): String?

    /** E.164 numbers known for [contactId]: its phone keys and the numbers of its WhatsApp phone JIDs. */
    suspend fun phoneNumbers(contactId: Long): List<String>

    /** Links [contactId] to [contact], or unlinks it when null. */
    suspend fun link(
        contactId: Long,
        contact: AndroidContact?,
    )
}

/** How a restored phone ringtone found its Android contact. */
enum class RelinkOutcome {
    /** The link the database already had points at the right Android contact. */
    KEPT,

    /** Linked to another Android contact on this phone (matched by phone number or name). */
    LINKED,

    /** No Android contact on this phone matches: unlinked, the user links it from the chat. */
    MISSING,
}

data class RelinkResult(
    val contactId: Long,
    val outcome: RelinkOutcome,
    val lookupKey: String?,
    /** The backup's undo record was adopted (the same phone, after a reinstall). */
    val undoSeeded: Boolean,
)

/**
 * Finds the Android contact of each restored phone ringtone on this phone, and adopts the backup's undo record when
 * the contact still rings with Cucuto's old file (ADR 0057). Needs the contacts permission; the caller checks it.
 *
 * Matching, in order (brief, "Restore on a new phone"; #198):
 * 1. **Phone numbers.** Lookup keys are per device, numbers are not. A contact whose current link is among the
 *    matches keeps it; one match wins; several matches are narrowed by the old phone's contact name.
 * 2. **The lookup key** the database has (this phone's own link, or the backup's): kept when it still resolves.
 * 3. **The exact name** of the Android contact on the old phone, when exactly one contact has it.
 *
 * Nothing found: the stale link is removed, so contact detail offers to link it instead of reporting a missing
 * contact, and the ringtone choice stays.
 */
class RingtoneRelinker(
    private val contacts: AndroidContacts,
    private val links: RingtoneLinks,
    private val store: PhoneRingtoneStore,
    private val wallClock: () -> Long,
) {
    suspend fun relink(item: RestoredRingtone): RelinkResult {
        val stored = links.linkedKey(item.contactId)
        val current = stored?.let { contacts.find(it) }?.let { AndroidContact(it.lookupKey, it.displayName) }
        val byNumber =
            links
                .phoneNumbers(item.contactId)
                .flatMap { contacts.byPhoneNumber(it) }
                .distinctBy { it.lookupKey }
        val chosen =
            when {
                byNumber.isNotEmpty() -> {
                    byNumber.firstOrNull { it.lookupKey == current?.lookupKey }
                        ?: byNumber.singleOrNull()
                        ?: byNumber.singleOrNull { sameName(it.displayName, item.androidName) }
                        ?: current
                }

                current != null -> {
                    current
                }

                else -> {
                    item.androidName?.let { contacts.byName(it) }?.singleOrNull()
                }
            }
        val outcome =
            when {
                chosen == null -> RelinkOutcome.MISSING
                chosen.lookupKey == stored -> RelinkOutcome.KEPT
                else -> RelinkOutcome.LINKED
            }
        if (outcome != RelinkOutcome.KEPT) links.link(item.contactId, chosen)
        val seeded = chosen != null && seedUndo(item, chosen.lookupKey)
        return RelinkResult(item.contactId, outcome, chosen?.lookupKey, seeded)
    }

    /**
     * Adopts the backup's undo record when this Android contact has none here and still rings with exactly the file
     * Cucuto wrote before: then this is the phone that wrote the backup, and the file there is Cucuto's, not the
     * user's. The next sync replaces it and keeps the contact's own ringtone for later. On any other phone the URIs
     * mean nothing and are ignored.
     */
    private suspend fun seedUndo(
        item: RestoredRingtone,
        lookupKey: String,
    ): Boolean {
        val written = item.writtenUri ?: return false
        if (store.writes().any { it.lookupKey == lookupKey }) return false
        if (contacts.find(lookupKey)?.ringtone != written) return false
        store.upsertWrite(
            ContactRingtoneWriteEntity(
                lookupKey = lookupKey,
                contactId = item.contactId,
                originalRingtone = item.originalRingtone,
                writtenUri = written,
                variant = null,
                userChanged = false,
                writtenAt = wallClock(),
            ),
        )
        return true
    }

    private fun sameName(
        a: String?,
        b: String?,
    ): Boolean = a != null && b != null && a.trim().equals(b.trim(), ignoreCase = true)
}
