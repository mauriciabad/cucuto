package app.mauri.cucuto.core.data.db

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * What the module decided for one WhatsApp notification, for the diagnostics screen. A bounded ring: only the
 * newest [DecisionLogDao.CAPACITY] rows are kept.
 *
 * @property contactId Matched contact, if any. Not a foreign key: the log outlives deleted contacts.
 * @property clipUri The clip that played, when [outcome] is [DecisionOutcome.SWAPPED].
 * @property detail Short reason in English for the log (never message text).
 * @property hookMicros Time spent on the notification path, in microseconds.
 */
@Entity(tableName = "decision_log", indices = [Index("timestamp")])
data class DecisionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val packageName: String,
    val conversationKey: String? = null,
    val senderKey: String? = null,
    val contactId: Long? = null,
    val outcome: DecisionOutcome,
    val clipUri: String? = null,
    val detail: String? = null,
    val hookMicros: Long? = null,
)

/** Why the module did or did not change a notification. New values may be added; they are stored by name. */
enum class DecisionOutcome {
    /** The sound (and maybe the vibration) was replaced. */
    SWAPPED,

    /** No contact with a voice matched; WhatsApp's tone played. */
    UNASSIGNED,

    /** The global switch is off. */
    DISABLED,

    /** The notification had no usable key. */
    NO_KEY,

    /** Something failed; the module degraded to WhatsApp's tone. */
    ERROR,
}

/**
 * A conversation the module has seen alerting, for the "recent senders" feed and one-tap assignment. One row per
 * `(packageName, conversationKey)`.
 *
 * @property senderKey For groups, the key of the last sender (`Person` key), used by per-sender groups.
 */
@Entity(
    tableName = "seen_senders",
    primaryKeys = ["packageName", "conversationKey"],
    indices = [Index("lastSeenAt")],
)
data class SeenSenderEntity(
    val packageName: String,
    val conversationKey: String,
    val displayName: String? = null,
    val isGroup: Boolean = false,
    val senderKey: String? = null,
    val senderName: String? = null,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val messageCount: Int = 1,
)
