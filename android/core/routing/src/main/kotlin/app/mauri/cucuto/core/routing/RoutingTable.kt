package app.mauri.cucuto.core.routing

/**
 * Everything the module needs to swap a WhatsApp alert, built by the app from its database and published through
 * libxposed Remote Preferences (see [RoutingContract] and [RoutingCodec]).
 *
 * The table is immutable. The module keeps one instance in a volatile field and replaces it when the app publishes
 * a new one; lookups ([route], [resolve]) are a few hash map reads, with no parsing or I/O.
 *
 * @property generation Stamp of the publication that produced this table (milliseconds since the epoch). Only used
 *   for diagnostics and to ignore stale copies; equality of content does not depend on it.
 * @property enabled The global switch. When false the module leaves every notification untouched.
 * @property voices What can be played, deduplicated: contacts that share a character with the same clip toggles and
 *   haptic share one entry.
 * @property routes One entry per contact key, pointing into [voices].
 * @property selfTestClip Where the Diagnostics test chirp is, when the app mirrors it for SystemUI (ADR 0018);
 *   null keeps the clip provider's `content://app.mauri.cucuto.clips/selftest/chirp.wav`.
 * @property conditions When conditional clips may play ([Clip.condition]), deduplicated: every distinct pack `when`
 *   once, as instants the app computed (ADR 0037).
 * @property days Where local days start, for "first message of the day" clips ([Moment.firstOfDay], ADR 0059); null
 *   counts days in UTC.
 */
public data class RoutingTable(
    val generation: Long,
    val enabled: Boolean,
    val voices: List<Voice>,
    val routes: List<Route>,
    val selfTestClip: String? = null,
    val conditions: List<TimeCondition> = emptyList(),
    val days: LocalDays? = null,
) {
    private val index: Map<String, Map<String, Route>> = buildIndex(routes)

    init {
        for (route in routes) {
            require(route.voice == Route.NO_VOICE || route.voice in voices.indices) {
                "Route ${route.packageName} points at voice ${route.voice}, but there are ${voices.size}"
            }
        }
        for (voice in voices) {
            for (clip in voice.clips) {
                require(clip.condition == Clip.ALWAYS || clip.condition in conditions.indices) {
                    "Clip ${clip.uri} points at condition ${clip.condition}, but there are ${conditions.size}"
                }
            }
        }
    }

    /** The route for one key of one package, or null when the key is unknown. */
    public fun route(
        packageName: String,
        key: String,
    ): Route? = index[packageName]?.get(key)

    /**
     * Picks the voice for a notification, applying the group policy:
     *
     * - The conversation key (the notification's shortcut ID, the chat JID) is looked up first.
     * - If that route is a group with [Route.isPerSender], the sender's own route wins when it has a voice; the
     *   group's voice (if any) is the fallback.
     * - A conversation without a route is not assigned: the module keeps WhatsApp's tone, even if the sender has a
     *   voice of their own (the default policy is one voice per group).
     *
     * Returns null when nothing should change (unknown conversation, no voice, or the table is disabled).
     */
    public fun resolve(
        packageName: String,
        conversationKey: String,
        senderKey: String? = null,
    ): Match? {
        if (!enabled) return null
        val conversation = route(packageName, conversationKey) ?: return null
        if (conversation.isPerSender && senderKey != null) {
            val sender = route(packageName, senderKey)
            if (sender != null && sender.voice != Route.NO_VOICE) {
                return Match(conversation, sender, voices[sender.voice])
            }
        }
        if (conversation.voice == Route.NO_VOICE) return null
        return Match(conversation, conversation, voices[conversation.voice])
    }

    /**
     * Whether [clip] may play at [nowMs] (epoch milliseconds): always for a clip without a condition, otherwise
     * while its condition is active or unknown (outside the published span). O(1), no allocation.
     */
    public fun isPlayable(
        clip: Clip,
        nowMs: Long,
    ): Boolean = clip.condition == Clip.ALWAYS || conditions[clip.condition].state(nowMs) != TimeCondition.INACTIVE

    /** The local day number of [nowMs] ([LocalDays.dayOf]), for first-of-the-day clips. O(1), no allocation. */
    public fun dayOf(nowMs: Long): Long = (days ?: LocalDays.UTC).dayOf(nowMs)

    /**
     * Result of [resolve].
     *
     * @property conversation The route of the chat that posted the notification.
     * @property speaker The route whose voice plays: [conversation] itself, or the sender's route in a per-sender group.
     * @property voice What to play.
     */
    public data class Match(
        val conversation: Route,
        val speaker: Route,
        val voice: Voice,
    )

    public companion object {
        /** No routes, enabled. What the module uses before the app has published anything. */
        public val EMPTY: RoutingTable =
            RoutingTable(generation = 0, enabled = true, voices = emptyList(), routes = emptyList())

        private fun buildIndex(routes: List<Route>): Map<String, Map<String, Route>> {
            val byPackage = HashMap<String, HashMap<String, Route>>(4)
            for (route in routes) {
                byPackage.getOrPut(route.packageName) { HashMap(routes.size * 2) }[route.key] = route
            }
            return byPackage
        }
    }
}

/**
 * One contact key mapped to a voice.
 *
 * @property packageName Messenger package (`com.whatsapp`, `com.whatsapp.w4b`).
 * @property key Conversation key as the module sees it: the notification's shortcut ID (a phone JID such as
 *   `…@s.whatsapp.net`, an `@lid` JID or a group `…@g.us`), or a sender `Person` key for per-sender groups.
 * @property voice Index into [RoutingTable.voices], or [NO_VOICE] (a per-sender group without a voice of its own).
 * @property seed Stable per-contact seed for the module's shuffle bag.
 * @property flags Bit set of [FLAG_GROUP] and [FLAG_PER_SENDER]; unknown bits are preserved.
 * @property contactId Row ID of the contact in the app database, reported back in decision-log events.
 */
public data class Route(
    val packageName: String,
    val key: String,
    val voice: Int,
    val seed: Int,
    val flags: Int,
    val contactId: Long,
) {
    val isGroup: Boolean get() = flags and FLAG_GROUP != 0
    val isPerSender: Boolean get() = flags and FLAG_PER_SENDER != 0

    public companion object {
        public const val NO_VOICE: Int = -1
        public const val FLAG_GROUP: Int = 1
        public const val FLAG_PER_SENDER: Int = 1 shl 1
    }
}

/**
 * A character as assigned to a contact: its enabled clips and the haptic to play with them.
 *
 * @property clips Never empty for a published voice.
 * @property haptic Vibration for every clip of this voice; [Haptic.Auto] defers to each clip's own [Clip.haptic].
 */
public data class Voice(
    val packId: String,
    val characterId: String,
    val clips: List<Clip>,
    val haptic: Haptic,
) {
    /** True when at least one clip has a [Clip.condition], so the module must check the time before drawing. */
    val isConditional: Boolean = clips.any { it.condition != Clip.ALWAYS }

    /** True when at least one clip has a [Clip.moment], so the module keeps the chat's rhythm and checks them first. */
    val hasMoments: Boolean = clips.any { it.moment.isSpecial }
}

/**
 * One playable clip.
 *
 * @property uri What SystemUI opens: a `file://` URI into the app's clip mirror, readable without starting the app
 *   (ADR 0018), or the `content://` URI of the app's clip provider when the clip is not mirrored.
 * @property milliWeight Relative probability in thousandths (the pack's `weight` × 1000; default 1000).
 * @property haptic The clip's own envelope ("haptics follow the voice"), used when the voice's haptic is
 *   [Haptic.Auto]. Null when the app has not analysed the clip.
 * @property condition Index into [RoutingTable.conditions] (the clip's pack `when`, such as night only), or
 *   [ALWAYS]. A conditional clip joins the pool only while its condition is active or unknown.
 * @property moment When the clip plays outside the shuffle bag (a rare clip, the first message of the day, a burst),
 *   or [Moment.NONE] for an ordinary clip (ADR 0059).
 */
public data class Clip(
    val uri: String,
    val milliWeight: Int = DEFAULT_MILLI_WEIGHT,
    val haptic: Haptic.Custom? = null,
    val condition: Int = ALWAYS,
    val moment: Moment = Moment.NONE,
) {
    public companion object {
        public const val DEFAULT_MILLI_WEIGHT: Int = 1000

        /** [condition] of a clip without a `when`: it always joins the pool. */
        public const val ALWAYS: Int = -1
    }
}

/**
 * A special clip (ADR 0059): it never joins the shuffle bag, and plays instead of a draw when its moment comes.
 *
 * - [firstOfDay]: the chat's first message of the local day ([RoutingTable.days]).
 * - [burst]: the chat's [burst]th message in a row, each within [BURST_GAP_MS] of the one before (0: no burst).
 * - [oneIn]: the clip plays on about one message in [oneIn] while the rest of its condition holds (0: always).
 *
 * A clip with [firstOfDay] or [burst] is a *moment* clip; one with only [oneIn] is a *rare* clip. The clip's time
 * [Clip.condition] must hold too.
 */
public data class Moment(
    val firstOfDay: Boolean = false,
    val burst: Int = 0,
    val oneIn: Int = 0,
) {
    init {
        require(burst == 0 || burst in MIN_BURST..MAX_BURST) { "Burst $burst out of range" }
        require(oneIn == 0 || oneIn in MIN_ONE_IN..MAX_ONE_IN) { "One in $oneIn out of range" }
    }

    /** Anything but an ordinary clip. */
    val isSpecial: Boolean get() = firstOfDay || burst != 0 || oneIn != 0

    /** Tied to a moment of the conversation (first of the day, a burst), not only to chance. */
    val isMoment: Boolean get() = firstOfDay || burst != 0

    public companion object {
        public val NONE: Moment = Moment()

        /** Longest gap between two messages of one burst. */
        public const val BURST_GAP_MS: Long = 60_000L

        public const val MIN_BURST: Int = 2
        public const val MAX_BURST: Int = 50
        public const val MIN_ONE_IN: Int = 2
        public const val MAX_ONE_IN: Int = 10_000
    }
}
