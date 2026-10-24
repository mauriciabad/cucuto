package app.mauri.cucuto.core.ringtone.sync

import android.util.Log
import app.mauri.cucuto.core.data.db.ContactRingtoneWriteEntity
import app.mauri.cucuto.core.data.db.PhoneRingtoneRow
import app.mauri.cucuto.core.data.db.RingtoneChoice
import app.mauri.cucuto.core.data.db.RingtoneMode
import app.mauri.cucuto.core.data.db.RingtoneRules
import app.mauri.cucuto.core.ringtone.contacts.AndroidContacts
import app.mauri.cucuto.core.ringtone.render.RingFileRenderer
import app.mauri.cucuto.core.ringtone.store.CharacterRingtones
import app.mauri.cucuto.core.ringtone.store.PhoneRingtoneStore
import app.mauri.cucuto.core.ringtone.store.RingtoneCatalog
import app.mauri.cucuto.core.ringtone.store.RingtoneExports
import app.mauri.cucuto.core.ringtone.variants.RingtoneVariant
import app.mauri.cucuto.core.ringtone.variants.RuleContext
import app.mauri.cucuto.core.ringtone.variants.VariantRules
import kotlinx.coroutines.CancellationException
import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime
import kotlin.random.Random

/** Why a sync runs. */
enum class SyncReason {
    /** Something changed (a setting, a link, a voice, a pack), the app started, a boundary passed: render what is missing. */
    Changed,

    /** A call ended: re-roll "random per call" contacts. Never renders (it runs in a broadcast receiver). */
    CallEnded,
}

/** Why a contact's phone ringtone is not ringing the way the user chose. */
enum class RingtoneProblem {
    /** No Android contact linked yet. */
    NotLinked,

    /** The linked Android contact no longer exists. */
    ContactMissing,

    /** The character has no ringtones, or its pack is not installed. */
    NoRingtones,

    /** The ring file could not be rendered or exported. */
    RenderFailed,

    /** Android refused the contact change. */
    WriteFailed,

    /** Someone changed the ringtone in Contacts after Cucuto; Cucuto leaves it alone until the user takes over. */
    ChangedElsewhere,

    /** The contacts permission is missing. */
    NoPermission,
}

/** The outcome of a sync: problems per Cucuto contact, and when a time-based rule needs the next sync. */
data class SyncResult(
    val problems: Map<Long, RingtoneProblem> = emptyMap(),
    val nextBoundary: LocalDateTime? = null,
)

/**
 * Makes Android contacts ring the way Cucuto's phone ringtones say (ADR 0039). Idempotent: every run compares what each
 * linked Android contact has with what it should have and changes only the difference.
 *
 * - **Android default** (ADR 0073). A contact whose choice is the phone's default gets a ringtone of its own cleared,
 *   recorded like any write; one without a ringtone is never written.
 * - **Undo.** Before the first write to an Android contact, its own ringtone is recorded ([ContactRingtoneWriteEntity]).
 *   An Android contact no linked chat wants any more (phone ringtone removed or "Leave as is", chat unlinked or
 *   linked elsewhere) gets that ringtone back from the record alone, whether or not its chat is still linked, unless
 *   someone changed it in the meantime (#369).
 * - **Respect.** A ringtone that is neither what Cucuto wrote nor one of its ring files was set by the user (or another
 *   app); Cucuto stops writing that contact and reports [RingtoneProblem.ChangedElsewhere].
 * - **Ring files.** Every variant of a used character is exported once (shared by every contact with that character),
 *   so a re-roll or a boundary is only a contact write. Files no longer used are deleted.
 */
class RingtoneSync(
    private val store: PhoneRingtoneStore,
    private val contacts: AndroidContacts,
    private val exports: RingtoneExports,
    private val catalog: RingtoneCatalog,
    private val renderer: RingFileRenderer,
    private val workDir: File,
    private val wallClock: () -> Long,
    private val ruleContext: suspend () -> RuleContext,
    private val random: Random = Random.Default,
) {
    /** Brings every linked Android contact in line. Call with one sync at a time. */
    suspend fun sync(reason: SyncReason): SyncResult {
        val rows = store.rows()
        if (!contacts.canReadAndWrite()) {
            return SyncResult(problems = rows.associate { it.contactId to RingtoneProblem.NoPermission })
        }
        val context = ruleContext()
        val writes = store.writes().associateBy { it.lookupKey }.toMutableMap()
        val existing = exports.list()
        val ours = existing.mapTo(HashSet()) { it.uri }
        val byBase = existing.associateBy { baseOf(it.displayName) }
        val problems = HashMap<Long, RingtoneProblem>()
        val used = HashSet<String>()
        var boundary: LocalDateTime? = null

        // One Android contact rings for one Cucuto contact: the oldest link wins.
        val linked = rows.filter { it.lookupKey != null }.distinctBy { it.lookupKey }
        val wanted = linked.mapTo(HashSet()) { it.lookupKey!! }
        for (row in rows) if (row.lookupKey == null) problems[row.contactId] = RingtoneProblem.NotLinked

        for (write in writes.values.toList()) {
            if (write.lookupKey !in wanted) {
                restore(write, ours)
                writes.remove(write.lookupKey)
            }
        }

        // Android default first: it never renders, so it never waits behind ring files still to render (#369).
        for (row in linked.sortedBy { it.choice != RingtoneChoice.ANDROID_DEFAULT }) {
            val lookupKey = row.lookupKey!!
            if (row.choice == RingtoneChoice.ANDROID_DEFAULT) {
                syncDefault(row, lookupKey, writes, ours)?.let { problems[row.contactId] = it }
                continue
            }
            val character =
                if (row.packId != null &&
                    row.characterId != null
                ) {
                    catalog.character(row.packId!!, row.characterId!!)
                } else {
                    null
                }
            if (character == null || character.variants.isEmpty()) {
                problems[row.contactId] = RingtoneProblem.NoRingtones
                continue
            }
            val android = contacts.find(lookupKey)
            if (android == null) {
                problems[row.contactId] = RingtoneProblem.ContactMissing
                continue
            }
            val write = writes[lookupKey]
            if (write?.userChanged == true) {
                problems[row.contactId] = RingtoneProblem.ChangedElsewhere
                continue
            }
            val current = android.ringtone
            if (write?.writtenUri != null && !write.stillOn(current, ours)) {
                store.upsertWrite(write.copy(userChanged = true, writtenAt = wallClock()))
                problems[row.contactId] = RingtoneProblem.ChangedElsewhere
                continue
            }

            val uris = LinkedHashMap<String, String>()
            var renderFailed = false
            for (variant in character.variants) {
                if (variant.file in uris) continue
                val uri =
                    byBase[exportBase(character, variant)]?.uri
                        ?: if (reason == SyncReason.Changed) export(character, variant, ours) else null
                if (uri != null) uris[variant.file] = uri else renderFailed = true
            }
            used += uris.values

            val available = character.variants.filter { it.file in uris }
            val rules = RingtoneRules.decode(row.rules)
            val pick =
                VariantRules.pick(
                    variants = available,
                    mode = row.mode,
                    fixed = row.variant,
                    context = context,
                    current = write?.variant,
                    reroll = reason == SyncReason.CallEnded,
                    random = random,
                    rules = rules,
                )
            if (pick == null) {
                problems[row.contactId] = RingtoneProblem.RenderFailed
                continue
            }
            if (renderFailed) problems[row.contactId] = RingtoneProblem.RenderFailed
            val uri = uris.getValue(pick.file)
            if (row.mode != RingtoneMode.FIXED && row.mode != RingtoneMode.RANDOM) {
                VariantRules.nextBoundary(character.variants, row.mode, context, rules)?.let { next ->
                    if (boundary == null || next < boundary) boundary = next
                }
            }
            if (current == uri && write?.writtenUri == uri) continue

            // Record first: a crash between the two writes leaves one of Cucuto's files on the contact, which the
            // check above recognizes as Cucuto's (not a user change).
            val original = write?.originalRingtone ?: current?.takeIf { write == null && it !in ours }
            val record =
                ContactRingtoneWriteEntity(
                    lookupKey = lookupKey,
                    contactId = row.contactId,
                    originalRingtone = original,
                    writtenUri = uri,
                    variant = pick.file,
                    userChanged = false,
                    writtenAt = wallClock(),
                )
            store.upsertWrite(record)
            writes[lookupKey] = record
            if (current != uri && !contacts.setRingtone(lookupKey, uri)) {
                problems[row.contactId] = RingtoneProblem.WriteFailed
            }
        }

        // Keep files a contact still points at even when its character is gone (pack removed): deleting them would
        // silently drop the contact to the default ringtone.
        val keep = used + writes.values.filter { !it.userChanged }.mapNotNull { it.writtenUri }
        if (reason == SyncReason.Changed) {
            for (export in existing) if (export.uri !in keep) exports.delete(export.uri)
        }
        return SyncResult(problems, boundary)
    }

    /** Gives every Android contact Cucuto changed its own ringtone back and deletes every ring file (feature off). */
    suspend fun restoreAll(): Boolean {
        if (!contacts.canReadAndWrite()) return false
        val ours = exports.list()
        val uris = ours.mapTo(HashSet()) { it.uri }
        for (write in store.writes()) restore(write, uris)
        for (export in ours) exports.delete(export.uri)
        return true
    }

    /**
     * The user wants Cucuto to manage [lookupKey] again after changing its ringtone elsewhere: their latest own
     * ringtone becomes the one to restore later, and the next sync writes Cucuto's.
     */
    suspend fun takeOver(lookupKey: String) {
        val write = store.writes().firstOrNull { it.lookupKey == lookupKey } ?: return
        val current = contacts.find(lookupKey)?.ringtone
        store.upsertWrite(
            write.copy(originalRingtone = current, writtenUri = null, userChanged = false, writtenAt = wallClock()),
        )
    }

    /**
     * "Android default" (ADR 0073): the contact rings with the phone's default ringtone. A ringtone of its own (the
     * user's, or one of Cucuto's files) is recorded for undo, then cleared. A contact without one is never written;
     * the record alone notes that Cucuto stands behind "no ringtone", so a ringtone set later counts as the user's.
     */
    private suspend fun syncDefault(
        row: PhoneRingtoneRow,
        lookupKey: String,
        writes: MutableMap<String, ContactRingtoneWriteEntity>,
        ours: Set<String>,
    ): RingtoneProblem? {
        val android = contacts.find(lookupKey) ?: return RingtoneProblem.ContactMissing
        val write = writes[lookupKey]
        if (write?.userChanged == true) return RingtoneProblem.ChangedElsewhere
        val current = android.ringtone
        if (current == null) {
            if (write?.writtenUri != DEFAULT) {
                val record =
                    (write ?: ContactRingtoneWriteEntity(lookupKey, row.contactId, null, writtenAt = 0)).copy(
                        contactId = row.contactId,
                        writtenUri = DEFAULT,
                        variant = null,
                        writtenAt = wallClock(),
                    )
                store.upsertWrite(record)
                writes[lookupKey] = record
            }
            return null
        }
        if (write?.writtenUri != null && !write.stillOn(current, ours)) {
            store.upsertWrite(write.copy(userChanged = true, writtenAt = wallClock()))
            return RingtoneProblem.ChangedElsewhere
        }
        // Record first, as for ring files: a crash between the two writes leaves the contact as it was.
        val record =
            ContactRingtoneWriteEntity(
                lookupKey = lookupKey,
                contactId = row.contactId,
                originalRingtone = write?.originalRingtone ?: current.takeIf { write == null && it !in ours },
                writtenUri = DEFAULT,
                variant = null,
                userChanged = false,
                writtenAt = wallClock(),
            )
        store.upsertWrite(record)
        writes[lookupKey] = record
        return if (contacts.setRingtone(lookupKey, null)) null else RingtoneProblem.WriteFailed
    }

    private suspend fun restore(
        write: ContactRingtoneWriteEntity,
        ours: Set<String>,
    ) {
        val android = contacts.find(write.lookupKey)
        if (android != null && !write.userChanged) {
            val current = android.ringtone
            if (write.stillOn(current, ours) && current != write.originalRingtone) {
                contacts.setRingtone(write.lookupKey, write.originalRingtone)
            }
        }
        store.deleteWrite(write.lookupKey)
    }

    /**
     * Whether the contact still has what Cucuto left there: [ContactRingtoneWriteEntity.writtenUri], no ringtone after
     * a clear ([DEFAULT]), or any of Cucuto's ring files. Anything else was set by someone else.
     */
    private fun ContactRingtoneWriteEntity.stillOn(
        current: String?,
        ours: Set<String>,
    ): Boolean = current == writtenUri || (writtenUri == DEFAULT && current == null) || current in ours

    private suspend fun export(
        character: CharacterRingtones,
        variant: RingtoneVariant,
        ours: MutableSet<String>,
    ): String? {
        val source = character.fileOf(variant) ?: return null
        return try {
            val rendered = renderer.render(source, variant.loop, variant.fadeInMs, workDir)
            try {
                val name = "${exportBase(character, variant)}.${rendered.extension}"
                exports.publish(rendered.file, name, title(character, variant), rendered.mimeType).also { ours += it }
            } finally {
                rendered.file.delete()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Ring file failed for ${character.packId}/${character.characterId}", e)
            null
        }
    }

    companion object {
        private const val TAG = "cucuto"

        /** The undo record's marker for a contact Cucuto cleared (ADR 0073). */
        private const val DEFAULT = ContactRingtoneWriteEntity.DEFAULT_RINGTONE

        /**
         * The [exportBase] of a ring file's display name. MediaStore adds " (1)", " (2)", … when the name is taken,
         * which happens when Cucuto's earlier files outlived its ownership of them (a data clear or a reinstall leaves
         * them in `Ringtones/cucuto/` with no owner, invisible to Cucuto). Without stripping it, every sync would
         * render every file again (#369).
         */
        internal fun baseOf(displayName: String): String =
            displayName.substringBeforeLast('.').replace(MEDIASTORE_SUFFIX, "")

        private val MEDIASTORE_SUFFIX = Regex(" \\(\\d+\\)$")

        /** Bump when rendering changes, so every phone renders its ring files again. */
        const val RENDER_VERSION = 1

        /**
         * File name (without extension) of a variant's ring file. Readable (character and variant) plus a hash of
         * everything the file depends on, so a pack update or a new renderer gives a new file and never reuses a stale
         * one.
         */
        fun exportBase(
            character: CharacterRingtones,
            variant: RingtoneVariant,
        ): String {
            val digest =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(
                        listOf(
                            character.packId,
                            character.packVersion,
                            character.characterId,
                            variant.file,
                            variant.loop?.let { "${it.startMs}-${it.endMs}" } ?: "once",
                            variant.fadeInMs.toString(),
                            RENDER_VERSION.toString(),
                        ).joinToString("\u0000").toByteArray(),
                    ).joinToString("") { "%02x".format(it) }
                    .take(HASH_CHARS)
            val label = variant.label ?: variant.file.substringAfterLast('/').substringBeforeLast('.')
            return "${slug(character.characterId)}-${slug(label)}-$digest"
        }

        /** What the Contacts app and the system ringtone picker show: "Character · Variant". */
        fun title(
            character: CharacterRingtones,
            variant: RingtoneVariant,
        ): String =
            if (character.variants.size == 1 || variant.label == null) {
                character.characterName
            } else {
                "${character.characterName} · ${variant.label}"
            }

        private const val HASH_CHARS = 10

        private fun slug(value: String): String =
            value
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .take(MAX_SLUG)
                .ifEmpty { "ringtone" }

        private const val MAX_SLUG = 32

        /** For the UI: the variant [write] put on its contact. */
        fun variantOf(
            write: ContactRingtoneWriteEntity?,
            variants: List<RingtoneVariant>,
        ): RingtoneVariant? = variants.firstOrNull { it.file == write?.variant }
    }
}
