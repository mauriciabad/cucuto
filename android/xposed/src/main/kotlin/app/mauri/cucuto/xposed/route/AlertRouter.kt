package app.mauri.cucuto.xposed.route

import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import app.mauri.cucuto.core.routing.RoutingTable
import app.mauri.cucuto.xposed.events.Outcome

/** The packages Cucuto acts on, and the app's own self-test notification. */
object Targets {
    /** WhatsApp and WhatsApp Business. Every other package is ignored without being read. */
    val MESSENGERS: Set<String> = setOf("com.whatsapp", "com.whatsapp.w4b")

    /** The app itself, which posts the Diagnostics screen's test notification. */
    const val SELF_PACKAGE: String = "app.mauri.cucuto"

    /** Channel of the test notification; nothing else from [SELF_PACKAGE] is touched. */
    const val SELF_TEST_CHANNEL: String = "cucuto_self_test"

    /** Clip the provider synthesises for the self-test. `selftest` is not a valid pack ID, so it cannot collide. */
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
 * Picks the clip and haptic for a notification from the in-memory routing table. Pure apart from the shuffle bags:
 * no IO, no reflection, no Android types, so it is cheap on the notification path and unit-tested on the JVM.
 */
class AlertRouter(
    private val bags: ShuffleBags = ShuffleBags(),
) {
    /** Returns null for packages Cucuto never touches; otherwise a [Decision], possibly "leave it alone". */
    fun decide(
        snapshot: NotificationSnapshot,
        table: RoutingTable,
    ): Decision? {
        val pkg = snapshot.packageName
        if (pkg == Targets.SELF_PACKAGE) return selfTest(snapshot)
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
            val uris = ArrayList<String>(voice.clips.size)
            val weights = IntArray(voice.clips.size)
            voice.clips.forEachIndexed { i, clip ->
                uris += clip.uri
                weights[i] = clip.milliWeight
            }
            val speaker = match.speaker
            val index = bags.next("$pkg\n${speaker.key}", uris, weights, speaker.seed)
            val clip = voice.clips[index]
            val haptic =
                when (val h = voice.haptic) {
                    Haptic.Auto -> clip.haptic ?: Haptic.Keep
                    else -> h
                }
            return Decision(
                outcome = Outcome.SWAPPED,
                packageName = pkg,
                identity = identity,
                matchedKey = key,
                contactId = speaker.contactId,
                clipUri = clip.uri,
                haptic = haptic,
                detail = if (speaker !== match.conversation) "per-sender" else null,
            )
        }
        val known = identity.keys.firstOrNull { table.route(pkg, it) != null }
        return Decision(
            Outcome.UNASSIGNED,
            pkg,
            identity,
            matchedKey = known,
            contactId = known?.let { table.route(pkg, it)?.contactId },
            detail = if (known != null) "contact has no voice" else null,
        )
    }

    private fun selfTest(snapshot: NotificationSnapshot): Decision? {
        if (snapshot.channelId != Targets.SELF_TEST_CHANNEL) return null
        return Decision(
            outcome = Outcome.SWAPPED,
            packageName = snapshot.packageName,
            identity = ConversationIdentity(listOf(SELF_TEST_KEY), false, "Cucuto self-test", null, null),
            matchedKey = SELF_TEST_KEY,
            clipUri = Targets.SELF_TEST_CLIP,
            haptic = Targets.SELF_TEST_HAPTIC,
            detail = "self-test",
        )
    }

    companion object {
        const val SELF_TEST_KEY: String = "cucuto:self-test"
    }
}
