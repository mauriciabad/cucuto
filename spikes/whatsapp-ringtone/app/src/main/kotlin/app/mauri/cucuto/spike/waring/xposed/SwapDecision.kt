package app.mauri.cucuto.spike.waring.xposed

/**
 * What the module knows about WhatsApp's ringing call when WhatsApp asks for its ringtone, read from WhatsApp's own
 * incoming-call notification ([CallNotificationReader]). No JID: WhatsApp's call notification carries none (no
 * shortcut ID, a `Person` with a name only; see docs/whatsapp-ringtone-spike.md, section 4).
 *
 * @property name `Notification.EXTRA_CALL_PERSON`'s name, else `EXTRA_TITLE`. Never logged.
 * @property ageMillis How long ago the notification was posted.
 * @property isGroupCall WhatsApp's own `isGroupCall` extra.
 */
data class IncomingCall(
    val name: String?,
    val ageMillis: Long,
    val isGroupCall: Boolean,
)

/** The outcome for one `RingtoneManager.getRingtone(Context, Uri)` call. [reason] is safe to log. */
sealed interface Decision {
    val reason: String

    data class Keep(
        override val reason: String,
    ) : Decision

    data class Swap(
        val uri: String,
    ) : Decision {
        override val reason: String get() = "swap"
    }
}

/**
 * The swap rule, pure so it is unit-tested on the JVM. Every doubt keeps WhatsApp's own ringtone: the substitution
 * happens only for a 1:1 call that is visibly ringing right now, from the configured test contact, in swap mode.
 */
object SwapDecision {
    /** A call notification older than this is not "the call that is ringing now" (WhatsApp rings for ~45-60 s). */
    const val MAX_CALL_AGE_MILLIS: Long = 90_000

    fun decide(
        config: SpikeConfig,
        originalUri: String?,
        call: IncomingCall?,
    ): Decision {
        if (config.mode != SpikeConfig.Mode.SWAP) return Decision.Keep("mode ${config.mode.name.lowercase()}")
        if (originalUri == null) return Decision.Keep("no uri")
        // Group calls use WhatsApp's bundled tones (android.resource://…/raw/…); v1 leaves them alone.
        if (originalUri.startsWith("android.resource:")) return Decision.Keep("bundled tone")
        if (call == null) return Decision.Keep("no incoming-call notification")
        if (call.isGroupCall) return Decision.Keep("group call")
        if (call.ageMillis < 0 || call.ageMillis > MAX_CALL_AGE_MILLIS) return Decision.Keep("stale call notification")
        val testName = config.testName ?: return Decision.Keep("no test contact configured")
        val ringUri = config.ringUri ?: return Decision.Keep("no ring uri configured")
        if (!ringUri.startsWith("content://")) return Decision.Keep("ring uri is not a content uri")
        if (call.name != testName) return Decision.Keep("another caller")
        if (ringUri == originalUri) return Decision.Keep("already that file")
        return Decision.Swap(ringUri)
    }
}
