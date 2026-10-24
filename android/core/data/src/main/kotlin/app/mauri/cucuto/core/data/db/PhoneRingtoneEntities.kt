package app.mauri.cucuto.core.data.db

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.PrimaryKey

/**
 * A contact's phone-call ringtone (#52): which character rings and how its variant is chosen. The ringtone reaches
 * Android through the contact's linked Android contact ([ContactEntity.androidLookupKey]), whose `CUSTOM_RINGTONE`
 * `:core:ringtone` sets (ADR 0039).
 *
 * @property packId The character's pack, or null for the contact's voice. The voice and the ringtone are independent
 *   choices (ADR 0063): with [followsVoice] this is the character kept while the voice has no ringtones (null when
 *   none), without it the character the user picked in the ringtone picker.
 * @property characterId The character, null together with [packId].
 * @property mode How the variant is chosen (backup schema `ringtone.rule.mode`).
 * @property variant Ringtone file (path from `pack.json`) for [RingtoneMode.FIXED]; null means the first variant.
 * @property rules The contact's own rules for [RingtoneMode.CUSTOM] (ADR 0074): a JSON array of [RingtoneRuleEntry]
 *   ([RingtoneRules] reads and writes it), null for every other mode.
 * @property followsVoice The user never picked a ringtone: it rings with the voice's character whenever that has
 *   ringtones, and the chat directory updates [packId] when the voice changes ([app.mauri.cucuto.core.data.chats.PhoneRingtoneFollow]). False once the
 *   user picks a character other than the voice's. Defaults to "no character of its own", the meaning before ADR 0063.
 * @property choice What rings (ADR 0073): a pack character (the fields above), the phone's default ringtone, or
 *   whatever the Android contact has ([RingtoneChoice.UNCHANGED], which the sync treats like no row). The last two
 *   store no character and never follow the voice.
 */
@Entity(
    tableName = "phone_ringtones",
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["id"],
            childColumns = ["contactId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PhoneRingtoneEntity(
    @PrimaryKey val contactId: Long,
    val packId: String? = null,
    val characterId: String? = null,
    val mode: RingtoneMode = RingtoneMode.PACK,
    val variant: String? = null,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "1") val followsVoice: Boolean = packId == null,
    val rules: String? = null,
    @ColumnInfo(defaultValue = "CHARACTER") val choice: RingtoneChoice = RingtoneChoice.CHARACTER,
)

/** What a contact's phone ringtone is (ADR 0073, owner #298). */
enum class RingtoneChoice {
    /** A pack character ([PhoneRingtoneEntity.packId], or the voice's while it follows). */
    CHARACTER,

    /**
     * The phone's default ringtone: Cucuto clears a ringtone of the Android contact's own (recording it for undo
     * first), and writes nothing when there is none.
     */
    ANDROID_DEFAULT,

    /** Leave as is: Cucuto never touches the Android contact's ringtone, and gives back its own if it changed it. */
    UNCHANGED,
}

/** How a phone ringtone picks its variant (docs/pack-format.md, "Variant resolution"; backup `rule.mode`). */
enum class RingtoneMode {
    /** One variant, [PhoneRingtoneEntity.variant]. */
    FIXED,

    /** A new variant by `weight` after every call (no hook: re-rolled when the call ends, #144). */
    RANDOM,

    /** The variant tagged `day` or `night` for the time of day. */
    TIME,

    /** The variant tagged with the current season. */
    SEASON,

    /** Follow the pack's `when` conditions (default). */
    PACK,

    /**
     * The contact's own rules ([PhoneRingtoneEntity.rules], ADR 0074): a copy of the pack's conditions the user edited,
     * resolved like the pack's.
     */
    CUSTOM,
}

/**
 * What Cucuto changed on an Android contact, so it can be undone: the contact's own ringtone before Cucuto wrote one,
 * and what Cucuto wrote last. Keyed by the Android contact (not by the Cucuto contact, and with no foreign key), so
 * turning phone ringtones off can still restore a contact whose Cucuto contact is gone.
 *
 * @property lookupKey `ContactsContract.Contacts.LOOKUP_KEY` of the Android contact.
 * @property contactId The Cucuto contact that rang through it when it was written (informational).
 * @property originalRingtone The contact's `CUSTOM_RINGTONE` before Cucuto (null: the default ringtone).
 * @property writtenUri What Cucuto last wrote, or null before the first write. [DEFAULT_RINGTONE] means Cucuto
 *   cleared the contact's ringtone (ADR 0073). A different value on the contact means someone else changed it, and
 *   Cucuto stops writing ([userChanged]).
 * @property variant The ringtone file (path from `pack.json`) behind [writtenUri].
 * @property userChanged The user (or another app) changed the ringtone after Cucuto; Cucuto leaves it alone until the
 *   user takes it over again, and never restores over it.
 */
@Entity(tableName = "contact_ringtone_writes")
data class ContactRingtoneWriteEntity(
    @PrimaryKey val lookupKey: String,
    val contactId: Long,
    val originalRingtone: String?,
    val writtenUri: String? = null,
    val variant: String? = null,
    val userChanged: Boolean = false,
    val writtenAt: Long,
) {
    companion object {
        /** [writtenUri] after Cucuto cleared the contact's ringtone for "Android default" (ADR 0073). */
        const val DEFAULT_RINGTONE: String = "cucuto:default"
    }
}
