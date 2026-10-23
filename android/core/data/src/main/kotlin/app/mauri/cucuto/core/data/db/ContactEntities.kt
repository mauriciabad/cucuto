package app.mauri.cucuto.core.data.db

import androidx.room3.ColumnInfo
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.Relation

/**
 * A person or group the user can give a voice to. A contact is identified by any of its [ContactKeyEntity] rows:
 * WhatsApp moves people from phone JIDs to `@lid` JIDs, so every key ever seen is kept and any of them matches.
 *
 * @property displayName Name shown in the app (from the notification, the Android contact or the user).
 * @property groupPolicy Groups only: one voice for the whole group, or each sender's own voice.
 * @property androidLookupKey `ContactsContract.Contacts.LOOKUP_KEY` of the linked Android contact, if any.
 * @property shuffleSeed Stable random seed for the module's per-contact shuffle bag.
 */
@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String?,
    @ColumnInfo(defaultValue = "0") val isGroup: Boolean = false,
    @ColumnInfo(defaultValue = "GROUP") val groupPolicy: GroupPolicy = GroupPolicy.GROUP,
    val androidLookupKey: String? = null,
    val androidDisplayName: String? = null,
    val shuffleSeed: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

/** How a group chooses its voice (backup schema `groupPolicy`). */
enum class GroupPolicy {
    /** One voice for the whole group (default). */
    GROUP,

    /** Each sender speaks with their own voice; the group's voice, if any, is the fallback. */
    PER_SENDER,
}

/**
 * One identity of a contact. `(packageName, value)` is unique across all contacts.
 *
 * @property packageName Messenger package (`com.whatsapp`, `com.whatsapp.w4b`), or [NO_PACKAGE] for identities that
 *   belong to no messenger, such as phone numbers.
 * @property value The key as the module reports it (shortcut ID / JID, sender `Person` key) or an E.164 number.
 * @property displayName Name last seen with this key.
 */
@Entity(
    tableName = "contact_keys",
    indices = [Index("packageName", "value", unique = true), Index("contactId")],
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["id"],
            childColumns = ["contactId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ContactKeyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contactId: Long,
    val packageName: String,
    val value: String,
    val kind: ContactKeyKind,
    val displayName: String? = null,
    val lastSeenAt: Long? = null,
) {
    companion object {
        const val NO_PACKAGE: String = ""
    }
}

/** What a key looks like. Derived from the key itself with [classify]; stored for queries and the UI. */
enum class ContactKeyKind {
    /** `<number>@s.whatsapp.net` (or the legacy `@c.us`). */
    PHONE_JID,

    /** `<id>@lid`, WhatsApp's privacy-preserving identity. */
    LID,

    /** `<id>@g.us`, a group chat. */
    GROUP_JID,

    /** An E.164 phone number (`+34…`), used to re-match Android contacts. Has no package. */
    PHONE_NUMBER,

    /** Anything else (a shortcut ID that is not a JID, a `Person` key, a title fallback). */
    OTHER,
    ;

    companion object {
        private val e164 = Regex("^\\+[1-9][0-9]{4,14}$")

        fun classify(value: String): ContactKeyKind =
            when {
                value.endsWith("@s.whatsapp.net") || value.endsWith("@c.us") -> PHONE_JID
                value.endsWith("@lid") -> LID
                value.endsWith("@g.us") -> GROUP_JID
                e164.matches(value) -> PHONE_NUMBER
                else -> OTHER
            }
    }
}

/** A contact with all of its keys. */
data class ContactWithKeys(
    @Embedded val contact: ContactEntity,
    @Relation(parentColumns = ["id"], entityColumns = ["contactId"])
    val keys: List<ContactKeyEntity>,
)
