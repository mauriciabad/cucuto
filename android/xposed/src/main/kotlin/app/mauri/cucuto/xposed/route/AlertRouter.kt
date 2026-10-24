package app.mauri.cucuto.xposed.route

import app.mauri.cucuto.core.haptics.forClip
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import app.mauri.cucuto.core.routing.Route
import app.mauri.cucuto.core.routing.RoutingTable
import app.mauri.cucuto.core.routing.Voice
import app.mauri.cucuto.xposed.events.Outcome

/** The packages Cucuto acts on, and the app's own self-test notification. */
object Targets {
    /** WhatsApp and WhatsApp Business. Every other package is ignored without being read. */
    val MESSENGERS: Set<String> = setOf("com.whatsapp", "com.whatsapp.w4b")

    /** The app itself, which posts the Diagnostics screen's test notification. */
    const val SELF_PACKAGE: String = "app.mauri.cucuto"

    /** Channel of the test notification; nothing else from [SELF_PACKAGE] is touched. */
    const val SELF_TEST_CHANNEL: String = "cucuto_self_test"

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
 * Picks the clip and haptic for a notification from the in-memory routing table. Pure apart from the shuffle bags
 * and the clock: no IO, no reflection, no Android types, so it is cheap on the notification path and unit-tested on
 * the JVM.
 *
 * @param now Wall clock in epoch milliseconds, read only for voices with conditional clips.
 */
class AlertRouter(
    private val bags: ShuffleBags = ShuffleBags(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * Indices of [voice]'s clips that may play at [nowMs] ([RoutingTable.isPlayable]), or null to use them all:
     * when every clip is playable, or when none is (a pack whose conditions leave a gap still plays something).
     */
    private fun playablePool(
        table: RoutingTable,
        voice: Voice,
        nowMs: Long,
    ): IntArray? {
        val clips = voice.clips
        var count = 0
        for (clip in clips) if (table.isPlayable(clip, nowMs)) count++
        if (count == 0 || count == clips.size) return null
        val pool = IntArray(count)
        var j = 0
        clips.forEachIndexed { i, clip -> if (table.isPlayable(clip, nowMs)) pool[j++] = i }
        return pool
    }

    /** Returns null for packages Cucuto never touches; otherwise a [Decision], possibly "leave it alone". */
    fun decide(
        snapshot: NotificationSnapshot,
        table: RoutingTable,
    ): Decision? {
        val pkg = snapshot.packageName
        if (pkg == Targets.SELF_PACKAGE) return selfTest(snapshot, table)
        if (pkg !in Targets.MESSENGERS) return null

        val identity = ConversationKeys.identify(snapshot)
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
            // Day and night clips (ADR 0037): only the clips whose condition holds now join the draw.
            val pool = if (voice.isConditional) playablePool(table, voice, now()) else null
            val size = pool?.size ?: voice.clips.size
            val uris = ArrayList<String>(size)
            val weights = IntArray(size)
            for (i in 0 until size) {
                val clip = voice.clips[pool?.get(i) ?: i]
                uris += clip.uri
                weights[i] = clip.milliWeight
            }
            val speaker = match.speaker
            val drawn = bags.next("$pkg\n${speaker.key}", uris, weights, speaker.seed)
            val clip = voice.clips[pool?.get(drawn) ?: drawn]
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
    ): Decision? {
        if (snapshot.channelId != Targets.SELF_TEST_CHANNEL) return null
        return Decision(
            outcome = Outcome.SWAPPED,
            packageName = snapshot.packageName,
            identity = ConversationIdentity(listOf(SELF_TEST_KEY), false, "Cucuto self-test", null, null),
            matchedKey = SELF_TEST_KEY,
            clipUri = table.selfTestClip ?: Targets.SELF_TEST_CLIP,
            haptic = Targets.SELF_TEST_HAPTIC,
            detail = "self-test",
        )
    }

    companion object {
        const val SELF_TEST_KEY: String = "cucuto:self-test"

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
