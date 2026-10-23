package app.mauri.cucuto.xposed.events

/**
 * Why the module did or did not change a notification. The names match `:core:data`'s `DecisionOutcome`, which
 * stores them by name; the app maps names it does not know to an error entry.
 */
enum class Outcome {
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
 * One decision, as sent from `system_server` to the app (issue #17). Raw keys and names travel only over this
 * uid-checked Binder call into the app's private database; they are never written to any log.
 *
 * @property timeMillis Wall-clock time of the alert.
 * @property conversationKey The key that matched, or the strongest candidate when nothing matched.
 * @property contactId App database row of the matched contact, or null.
 * @property hookMicros Time the module spent on the notification path for this alert.
 */
data class DecisionEvent(
    val timeMillis: Long,
    val packageName: String,
    val conversationKey: String?,
    val displayName: String?,
    val isGroup: Boolean,
    val senderKey: String?,
    val senderName: String?,
    val contactId: Long?,
    val outcome: String,
    val clipUri: String?,
    val detail: String?,
    val hookMicros: Long,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            EventContract.F_TIME to timeMillis,
            EventContract.F_PACKAGE to packageName,
            EventContract.F_KEY to conversationKey,
            EventContract.F_NAME to displayName,
            EventContract.F_GROUP to isGroup,
            EventContract.F_SENDER_KEY to senderKey,
            EventContract.F_SENDER_NAME to senderName,
            EventContract.F_CONTACT to (contactId ?: NO_CONTACT),
            EventContract.F_OUTCOME to outcome,
            EventContract.F_CLIP to clipUri,
            EventContract.F_DETAIL to detail,
            EventContract.F_MICROS to hookMicros,
        )

    companion object {
        private const val NO_CONTACT = -1L

        /** Reads an event written by [toMap]; null when a required field is missing or has the wrong type. */
        fun fromMap(get: (String) -> Any?): DecisionEvent? {
            val time = get(EventContract.F_TIME) as? Long ?: return null
            val pkg = (get(EventContract.F_PACKAGE) as? String)?.takeIf { it.isNotEmpty() } ?: return null
            val outcome = get(EventContract.F_OUTCOME) as? String ?: return null
            return DecisionEvent(
                timeMillis = time,
                packageName = pkg,
                conversationKey = get(EventContract.F_KEY) as? String,
                displayName = get(EventContract.F_NAME) as? String,
                isGroup = get(EventContract.F_GROUP) as? Boolean ?: false,
                senderKey = get(EventContract.F_SENDER_KEY) as? String,
                senderName = get(EventContract.F_SENDER_NAME) as? String,
                contactId = (get(EventContract.F_CONTACT) as? Long)?.takeIf { it >= 0 },
                outcome = outcome,
                clipUri = get(EventContract.F_CLIP) as? String,
                detail = get(EventContract.F_DETAIL) as? String,
                hookMicros = get(EventContract.F_MICROS) as? Long ?: 0L,
            )
        }
    }
}

/**
 * The module's own health, piggybacked on every delivery so the Diagnostics screen can show "hook active" without
 * any extra wake-up of the app.
 *
 * @property hooks The hooks installed in this boot, e.g. `playSound, playVibration`.
 * @property tableGeneration Generation of the routing table the module holds (0 before the app published one).
 * @property dropped Events dropped since boot because the queue was full or delivery kept failing.
 */
data class ModuleStatus(
    val bootTimeMillis: Long,
    val hooks: List<String>,
    val missingHooks: List<String>,
    val tableGeneration: Long,
    val tableRoutes: Int,
    val decisions: Long,
    val slowDecisions: Long,
    val maxHookMicros: Long,
    val dropped: Long,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            EventContract.S_BOOT to bootTimeMillis,
            EventContract.S_HOOKS to hooks.joinToString(","),
            EventContract.S_MISSING to missingHooks.joinToString(","),
            EventContract.S_GENERATION to tableGeneration,
            EventContract.S_ROUTES to tableRoutes,
            EventContract.S_DECISIONS to decisions,
            EventContract.S_SLOW to slowDecisions,
            EventContract.S_MAX_MICROS to maxHookMicros,
            EventContract.S_DROPPED to dropped,
        )

    companion object {
        fun fromMap(get: (String) -> Any?): ModuleStatus? {
            val boot = get(EventContract.S_BOOT) as? Long ?: return null
            return ModuleStatus(
                bootTimeMillis = boot,
                hooks = split(get(EventContract.S_HOOKS)),
                missingHooks = split(get(EventContract.S_MISSING)),
                tableGeneration = get(EventContract.S_GENERATION) as? Long ?: 0L,
                tableRoutes = get(EventContract.S_ROUTES) as? Int ?: 0,
                decisions = get(EventContract.S_DECISIONS) as? Long ?: 0L,
                slowDecisions = get(EventContract.S_SLOW) as? Long ?: 0L,
                maxHookMicros = get(EventContract.S_MAX_MICROS) as? Long ?: 0L,
                dropped = get(EventContract.S_DROPPED) as? Long ?: 0L,
            )
        }

        private fun split(value: Any?): List<String> =
            (value as? String)?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
    }
}

/**
 * The module → app channel: `ContentResolver.call(content://app.mauri.cucuto.events, METHOD_REPORT, null, extras)`
 * from `system_server`. The app's provider accepts only uid 1000 (the system). See ADR 0010.
 */
object EventContract {
    const val AUTHORITY: String = "app.mauri.cucuto.events"
    const val METHOD_REPORT: String = "report"
    const val VERSION: Int = 1

    /** Extras: format version (Int). */
    const val EXTRA_VERSION: String = "v"

    /** Extras: `ArrayList<Bundle>` of [DecisionEvent]s, oldest first. */
    const val EXTRA_EVENTS: String = "events"

    /** Extras: a [ModuleStatus] Bundle. */
    const val EXTRA_STATUS: String = "status"

    /** Result: how many events the app accepted (Int). */
    const val RESULT_ACCEPTED: String = "accepted"

    /** Most events per call; keeps each Binder transaction small. */
    const val MAX_EVENTS_PER_CALL: Int = 50

    const val F_TIME = "t"
    const val F_PACKAGE = "p"
    const val F_KEY = "k"
    const val F_NAME = "n"
    const val F_GROUP = "g"
    const val F_SENDER_KEY = "sk"
    const val F_SENDER_NAME = "sn"
    const val F_CONTACT = "c"
    const val F_OUTCOME = "o"
    const val F_CLIP = "u"
    const val F_DETAIL = "d"
    const val F_MICROS = "us"

    const val S_BOOT = "boot"
    const val S_HOOKS = "hooks"
    const val S_MISSING = "missing"
    const val S_GENERATION = "gen"
    const val S_ROUTES = "routes"
    const val S_DECISIONS = "decisions"
    const val S_SLOW = "slow"
    const val S_MAX_MICROS = "maxUs"
    const val S_DROPPED = "dropped"
}
