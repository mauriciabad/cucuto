package app.mauri.cucuto.xposed.route

import app.mauri.cucuto.core.haptics.forClip
import app.mauri.cucuto.core.routing.ContactKeys
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import app.mauri.cucuto.core.routing.Route
import app.mauri.cucuto.core.routing.RoutingTable
import app.mauri.cucuto.xposed.events.Outcome

/** The packages Cucuto acts on, and the app's own self-test notification. */
object Targets {
    /** Signal and Molly, the Signal fork (ADR 0081). Their 1:1 notifications name the Android contact. */
    val SIGNAL_FAMILY: Set<String> = ContactKeys.PACKAGES.toSet()

    /**
     * WhatsApp, WhatsApp Business, Signal and Molly. Every other package is ignored without being read. WhatsApp
     * stays first: the warm-up routes under it.
     */
    val MESSENGERS: Set<String> = setOf("com.whatsapp", "com.whatsapp.w4b") + SIGNAL_FAMILY

    /** Whether [packageName] is Signal or Molly, whose notifications name the Android contact. */
    fun isSignalFamily(packageName: String): Boolean = ContactKeys.isSignalFamily(packageName)

    /** The app itself, which posts the Diagnostics screen's test notification. */
    const val SELF_PACKAGE: String = "app.mauri.cucuto"

    /** Channel of the test notification. Only this channel and [SIMULATE_CHANNEL] of [SELF_PACKAGE] are touched. */
    const val SELF_TEST_CHANNEL: String = "cucuto_self_test"

    /**
     * Channel of "Simulate message" (contact detail, #26): the app asks the module to play one chat's next alert on
     * its own notification. The chat is named by [EXTRA_SIMULATE_PACKAGE] and [EXTRA_SIMULATE_KEY]. Only a
     * notification that `system_server` attributes to [SELF_PACKAGE] is read, and only its own sound and vibration
     * change, so no other app can trigger this or affect anyone else's notifications (ADR 0025).
     */
    const val SIMULATE_CHANNEL: String = "cucuto_simulate"

    /** String extra of a simulate notification: the messenger package of the chat to simulate. */
    const val EXTRA_SIMULATE_PACKAGE: String = "app.mauri.cucuto.simulate.package"

    /** String extra of a simulate notification: the chat's conversation key, as stored in the routing table. */
    const val EXTRA_SIMULATE_KEY: String = "app.mauri.cucuto.simulate.key"

    /**
     * Clip the provider synthesises for the self-test, used when the table names no mirrored copy
     * ([RoutingTable.selfTestClip], ADR 0018). `selftest` is not a valid pack ID, so it cannot collide.
     */
    const val SELF_TEST_CLIP: String = "content://app.mauri.cucuto.clips/selftest/chirp.wav"

    /** A two-beat signature (the M0 spike's): a firm click, then a lighter tick. */
    val SELF_TEST_HAPTIC: Haptic.Custom =
        Haptic.Custom(
            listOf(
                HapticEvent.Preset(atMs = 0, preset = HapticPreset.CLICK, milliScale = 900),
                HapticEvent.Preset(atMs = 130, preset = HapticPreset.TICK, milliScale = 500),
            ),
        )

    fun isTarget(packageName: String): Boolean = packageName in MESSENGERS || packageName == SELF_PACKAGE
}

/**
 * What the module does with one alerting notification. Computed once per record, shared by the sound and vibration
 * hooks, and reported to the app's decision log.
 *
 * @property clipUri The replacement sound, or null to keep WhatsApp's.
 * @property haptic The replacement vibration: null or [Haptic.Keep] keep WhatsApp's, [Haptic.Off] silences it,
 *   [Haptic.Custom] replaces it. Never [Haptic.Auto] (resolved against the clip here).
 */
data class Decision(
    val outcome: Outcome,
    val packageName: String,
    val identity: ConversationIdentity,
    val matchedKey: String? = null,
    val contactId: Long? = null,
    val clipUri: String? = null,
    val haptic: Haptic? = null,
    val detail: String? = null,
) {
    val swapsSound: Boolean get() = clipUri != null
}

/**
 * The wall clock in epoch milliseconds. A `fun interface` rather than `() -> Long`, whose generic `invoke` boxes
 * every reading into a new `Long` on the notification path.
 */
fun interface WallClock {
    fun nowMs(): Long
}

/**
 * Picks the clip and haptic for a notification from the in-memory routing table. Pure apart from the shuffle bags
 * and the clock: no IO, no reflection, no Android types, so it is cheap on the notification path and unit-tested on
 * the JVM.
 *
 * @param now Wall clock in epoch milliseconds, read only for voices with conditional or special clips.
 */
class AlertRouter(
    private val bags: ShuffleBags = ShuffleBags(),
    private val now: WallClock = WallClock(System::currentTimeMillis),
) {
    /** Returns null for packages Cucuto never touches; otherwise a [Decision], possibly "leave it alone". */
    fun decide(
        snapshot: NotificationSnapshot,
        table: RoutingTable,
    ): Decision? {
        val pkg = snapshot.packageName
        if (pkg == Targets.SELF_PACKAGE) {
            return when (snapshot.channelId) {
                Targets.SELF_TEST_CHANNEL -> selfTest(snapshot, table)
                Targets.SIMULATE_CHANNEL -> simulate(snapshot, table)
                else -> null
            }
        }
        if (pkg !in Targets.MESSENGERS) return null
        return route(pkg, ConversationKeys.identify(snapshot), table)
    }

    /** The messenger path: [identity]'s keys against the table, drawing from the speaker's shuffle bag. */
    private fun route(
        pkg: String,
        identity: ConversationIdentity,
        table: RoutingTable,
    ): Decision {
        if (!table.enabled) return Decision(Outcome.DISABLED, pkg, identity, detail = "global switch off")
        if (identity.keys.isEmpty()) return Decision(Outcome.NO_KEY, pkg, identity, detail = "no shortcut or title")

        for (key in identity.keys) {
            val match = table.resolve(pkg, key, identity.senderKey) ?: continue
            val voice = match.voice
            if (voice.clips.isEmpty()) {
                return Decision(
                    Outcome.UNASSIGNED,
                    pkg,
                    identity,
                    key,
                    match.speaker.contactId,
                    detail = "voice has no clips",
                )
            }
            // Day and night clips (ADR 0037): only the clips whose condition holds now join the draw. Rare, first of
            // the day and burst clips (ADR 0059) follow the speaker's rhythm, which needs the time too.
            val speaker = match.speaker
            val nowMs = if (voice.isConditional || voice.hasMoments) now.nowMs() else 0L
            val clip = voice.clips[bags.next(pkg, speaker.key, voice, table, nowMs, speaker.seed)]
            // Resolved against the clip drawn for the sound, so an auto envelope matches what plays.
            val haptic = voice.haptic.forClip(clip.haptic)
            return Decision(
                outcome = Outcome.SWAPPED,
                packageName = pkg,
                identity = identity,
                matchedKey = key,
                contactId = speaker.contactId,
                clipUri = clip.uri,
                haptic = haptic,
                detail = groupDetail(match.conversation, speaker, identity.senderKey),
            )
        }
        val known = identity.keys.firstOrNull { table.route(pkg, it) != null }
        val knownRoute = known?.let { table.route(pkg, it) }
        return Decision(
            Outcome.UNASSIGNED,
            pkg,
            identity,
            matchedKey = known,
            contactId = knownRoute?.contactId,
            detail =
                when {
                    knownRoute == null -> null
                    !knownRoute.isPerSender -> DETAIL_NO_VOICE
                    identity.senderKey == null -> DETAIL_NO_SENDER_NO_GROUP_VOICE
                    else -> DETAIL_SENDER_UNASSIGNED_NO_GROUP_VOICE
                },
        )
    }

    /**
     * "Simulate message": decides exactly as for a real alert of the named chat, with the same table and the same
     * shuffle bag, so the simulated clip counts as played and the chat's next real message will not repeat it. The
     * decision is reported under [Targets.SELF_PACKAGE], so the app never mistakes it for a real message.
     */
    private fun simulate(
        snapshot: NotificationSnapshot,
        table: RoutingTable,
    ): Decision {
        val target = snapshot.simulatePackage?.takeIf { it in Targets.MESSENGERS }
        val key =
            snapshot.simulateKey
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= ConversationKeys.MAX_KEY_LENGTH }
        if (target == null || key == null) {
            return Decision(Outcome.NO_KEY, Targets.SELF_PACKAGE, NO_IDENTITY, detail = SIMULATED_DETAIL)
        }
        val isGroup = table.route(target, key)?.isGroup ?: key.endsWith(ConversationKeys.GROUP_JID_SUFFIX)
        val decision = route(target, ConversationIdentity(listOf(key), isGroup, null, null, null), table)
        return decision.copy(
            packageName = Targets.SELF_PACKAGE,
            matchedKey = decision.matchedKey ?: key,
            detail = listOfNotNull(SIMULATED_DETAIL, decision.detail).joinToString(", "),
        )
    }

    /**
     * Why a per-sender group sounded the way it did, for the decision log and the device check (#20): whether the
     * alerting record carried a sender at all, and whether that sender has a voice. Constant strings: nothing is
     * allocated on the notification path. Null for everything that is not a per-sender group.
     */
    private fun groupDetail(
        conversation: Route,
        speaker: Route,
        senderKey: String?,
    ): String? =
        when {
            !conversation.isPerSender -> null
            speaker !== conversation -> DETAIL_PER_SENDER
            senderKey == null -> DETAIL_NO_SENDER
            else -> DETAIL_SENDER_UNASSIGNED
        }

    private fun selfTest(
        snapshot: NotificationSnapshot,
        table: RoutingTable,
    ): Decision =
        Decision(
            outcome = Outcome.SWAPPED,
            packageName = snapshot.packageName,
            identity = ConversationIdentity(listOf(SELF_TEST_KEY), false, "Cucuto self-test", null, null),
            matchedKey = SELF_TEST_KEY,
            clipUri = table.selfTestClip ?: Targets.SELF_TEST_CLIP,
            haptic = Targets.SELF_TEST_HAPTIC,
            detail = "self-test",
        )

    companion object {
        const val SELF_TEST_KEY: String = "cucuto:self-test"

        /** First word of every simulated decision's detail; the app recognises simulations by it. */
        const val SIMULATED_DETAIL: String = "simulated"

        private val NO_IDENTITY = ConversationIdentity(emptyList(), false, null, null, null)

        /** A per-sender group played its sender's own voice. */
        const val DETAIL_PER_SENDER: String = "per-sender"

        /** A per-sender group played its own voice because the notification named no sender. */
        const val DETAIL_NO_SENDER: String = "per-sender, no sender on the notification: group voice"

        /** A per-sender group played its own voice because the sender has none. */
        const val DETAIL_SENDER_UNASSIGNED: String = "per-sender, sender has no voice: group voice"

        /** A per-sender group without a voice of its own, and the notification named no sender. */
        const val DETAIL_NO_SENDER_NO_GROUP_VOICE: String = "per-sender, no sender on the notification"

        /** A per-sender group without a voice of its own, and the sender has none either. */
        const val DETAIL_SENDER_UNASSIGNED_NO_GROUP_VOICE: String = "per-sender, sender has no voice"

        /** A contact (or a default-policy group) that is known but has no voice. */
        const val DETAIL_NO_VOICE: String = "contact has no voice"
    }
}
