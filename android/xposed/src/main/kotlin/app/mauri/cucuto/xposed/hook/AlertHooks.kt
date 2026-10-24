package app.mauri.cucuto.xposed.hook

import android.net.Uri
import android.os.SystemClock
import android.os.VibrationEffect
import app.mauri.cucuto.core.haptics.HapticCapabilities
import app.mauri.cucuto.core.haptics.HapticEffects
import app.mauri.cucuto.core.routing.Clip
import app.mauri.cucuto.core.routing.ControlPoint
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.Route
import app.mauri.cucuto.core.routing.RoutingTable
import app.mauri.cucuto.core.routing.Voice
import app.mauri.cucuto.xposed.ModuleLog
import app.mauri.cucuto.xposed.events.DecisionEvent
import app.mauri.cucuto.xposed.events.Outcome
import app.mauri.cucuto.xposed.route.AlertRouter
import app.mauri.cucuto.xposed.route.ConversationIdentity
import app.mauri.cucuto.xposed.route.Decision
import app.mauri.cucuto.xposed.route.NotificationSnapshot
import app.mauri.cucuto.xposed.route.Targets
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicLong

/**
 * The production hooks (issue #15), on `com.android.server.notification.NotificationAttentionHelper`:
 *
 * - `playSound(NotificationRecord, Uri)`: argument 1 becomes the contact's clip.
 * - `playVibration(NotificationRecord, VibrationEffect, boolean)`: argument 1 becomes the contact's haptic, built by
 *   `:core:haptics` ([HapticVibrations], #96). The clip is the one `playSound` chose (the decision is shared), so an
 *   `auto` haptic follows that clip's envelope.
 *
 * Both run only after the framework has decided the record alerts (DND, muted chats, ringer mode, burst limiter,
 * alert-once and group-alert rules are all upstream), and both only ever substitute an argument (M0 report §2).
 * WhatsApp's alerting record is the group summary, whose shortcut ID is the conversation that just alerted.
 *
 * Safety, three layers: every hook body is wrapped in `try/catch(Throwable)` and falls back to the original
 * arguments; every hook is registered with [XposedInterface.ExceptionMode.PROTECTIVE]; and methods are resolved by
 * name and arity, so a changed signature is a logged no-op, never a crash.
 *
 * Speed: the notification path does a package check, a handful of reflective getters, hash map lookups and a
 * shuffle-bag draw; each haptic's effect is built once and cached. The vibrator's capabilities are read on the
 * background thread after boot ([CapabilitiesLoader], #190) and handed over with [useCapabilities]; until then a
 * custom haptic keeps WhatsApp's vibration, so the path never calls the vibrator service. No IO, no binder call, no
 * allocation beyond a few small objects. Every alert is timed with [SystemClock.elapsedRealtimeNanos]; anything over
 * [SLOW_NANOS] is logged.
 */
internal class AlertHooks(
    private val module: XposedInterface,
    private val table: () -> RoutingTable,
    private val report: (DecisionEvent) -> Unit,
    private val log: ModuleLog,
    private val router: AlertRouter = AlertRouter(),
    private val vibrations: HapticVibrations<VibrationEffect> = HapticVibrations(HapticEffects::create),
) {
    private var reader: RecordReader? = null
    private val recent = RecentDecisions()

    val installed: MutableList<String> = ArrayList(2)
    val missing: MutableList<String> = ArrayList(2)
    val decisions = AtomicLong()
    val slowDecisions = AtomicLong()
    val maxMicros = AtomicLong()

    /** Resolves and hooks both methods. Never throws. */
    fun install(classLoader: ClassLoader) {
        try {
            val helper = classLoader.loadClass(ATTENTION_HELPER)
            val record = classLoader.loadClass(NOTIFICATION_RECORD)
            val recordReader = RecordReader(record)
            if (!recordReader.usable) {
                log.warn("NotificationRecord.getSbn() not found; hooks not installed, WhatsApp keeps its tones")
                missing += listOf(PLAY_SOUND, PLAY_VIBRATION)
                return
            }
            reader = recordReader
            hook(helper, PLAY_SOUND, 2) { chain -> playSound(chain) }
            hook(helper, PLAY_VIBRATION, 3) { chain -> playVibration(chain) }
        } catch (t: Throwable) {
            log.warn("Installing hooks failed; WhatsApp keeps its tones", t)
        }
    }

    /**
     * Loads and initialises everything the notification path touches (the module's own classes, the routing code,
     * `:core:haptics` and its `VibrationEffect.Builder` probe), so the first WhatsApp alert after boot does not pay for
     * class loading. A throwaway router decides a routed alert against [WARM_UP_TABLE] (shuffle bag, `forClip`, the
     * decision event), so the real router's bags stay untouched. Run on the background thread; caches no effect and
     * reports nothing. The vibrator's capabilities are read separately, once the service is up ([useCapabilities]).
     */
    fun warmUp() {
        try {
            val snapshot = NotificationSnapshot(Targets.MESSENGERS.first(), shortcutId = WARM_UP_KEY)
            router.decide(snapshot, table())
            AlertRouter().decide(snapshot, WARM_UP_TABLE)?.toEvent(micros = 0)
            val builder = HapticEffects.builderAvailable
            vibrations.warmUp(Targets.SELF_TEST_HAPTIC, VibratorReader.fallback())
            vibrations.swapFor(Haptic.Keep)
            log.info(
                "Haptics via ${if (builder) "VibrationEffect.Builder" else "fallbacks (no VibrationEffect.Builder)"}",
            )
            Uri.parse(Targets.SELF_TEST_CLIP).pathSegments
            recent.get(this)
        } catch (t: Throwable) {
            log.warn("Warm-up failed", t)
        }
    }

    private fun hook(
        owner: Class<*>,
        name: String,
        arity: Int,
        hooker: XposedInterface.Hooker,
    ) {
        try {
            val candidates = owner.declaredMethods.filter { it.name == name && it.parameterCount == arity }
            val method: Method? = candidates.singleOrNull()
            if (method == null) {
                missing += name
                log.warn("${owner.simpleName}.$name/$arity: ${candidates.size} candidates; not hooked")
                return
            }
            module
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("cucuto-$name")
                .intercept(hooker)
            installed += name
            log.info("Hooked ${owner.simpleName}.$name(${method.parameterTypes.joinToString { it.simpleName }})")
        } catch (t: Throwable) {
            missing += name
            log.warn("Hooking $name failed", t)
        }
    }

    // ---------------------------------------------------------------- notification path

    private fun playSound(chain: XposedInterface.Chain): Any? {
        val replacement: Uri? =
            guarded(PLAY_SOUND) {
                val start = SystemClock.elapsedRealtimeNanos()
                val decision = decisionFor(chain.getArg(0), start)
                val uri = decision?.clipUri?.let(Uri::parse)
                timed(PLAY_SOUND, start)
                uri
            }
        if (replacement == null) return chain.proceed()
        val args = chain.args.toTypedArray()
        args[1] = replacement
        return chain.proceed(args)
    }

    private fun playVibration(chain: XposedInterface.Chain): Any? {
        val swap: VibrationSwap<VibrationEffect>? =
            guarded(PLAY_VIBRATION) {
                val start = SystemClock.elapsedRealtimeNanos()
                val haptic = decisionFor(chain.getArg(0), start)?.haptic
                // A volatile read of the capabilities: a custom haptic keeps the original until they are known.
                val swap = vibrations.swapFor(haptic)
                timed(PLAY_VIBRATION, start)
                swap
            }
        return when (swap) {
            null, VibrationSwap.Keep -> {
                chain.proceed()
            }

            // Haptic.Off is an explicit per-contact choice: report "not vibrated" without calling the original.
            VibrationSwap.Suppress -> {
                false
            }

            is VibrationSwap.Replace -> {
                val args = chain.args.toTypedArray()
                args[1] = swap.effect
                chain.proceed(args)
            }
        }
    }

    /**
     * Sets the device's capabilities, read by [CapabilitiesLoader] on the background thread ([read] false when they
     * could not be read and [capabilities] are the generic fallback). Logs the one `Vibrator: …` line, then builds a
     * preset and an envelope for these capabilities, uncached, so the first custom haptic does not load the build
     * path this device takes. Only the first call counts. Background thread only; never throws.
     */
    fun useCapabilities(
        capabilities: HapticCapabilities,
        read: Boolean,
    ) {
        try {
            if (!vibrations.useCapabilities(capabilities)) return
            log.info(describe(capabilities, read))
            vibrations.warmUp(Targets.SELF_TEST_HAPTIC, capabilities)
            vibrations.warmUp(WARM_UP_ENVELOPE, capabilities)
        } catch (t: Throwable) {
            log.warn("Using the vibrator's capabilities failed", t)
        }
    }

    /**
     * The decision for [record], computed once and shared by both hooks (`playSound` runs first, `playVibration`
     * right after on the same record). Returns null for packages Cucuto ignores.
     */
    private fun decisionFor(
        record: Any?,
        start: Long,
    ): Decision? {
        if (record == null) return null
        val reader = reader ?: return null
        val pkg = reader.packageOf(record) ?: return null
        if (!Targets.isTarget(pkg)) return null
        recent.get(record)?.let { return it }

        val decision =
            try {
                val snapshot = reader.snapshot(record) ?: return null
                router.decide(snapshot, table()) ?: return null
            } catch (t: Throwable) {
                log.warnRateLimited("decide", "Routing failed; WhatsApp keeps its tone", t)
                Decision(Outcome.ERROR, pkg, EMPTY_IDENTITY, detail = t.javaClass.simpleName)
            }
        recent.put(record, decision)

        val micros = (SystemClock.elapsedRealtimeNanos() - start) / 1000
        decisions.incrementAndGet()
        // Queued for the background thread, which also writes the (redacted) log line: no IO here.
        report(decision.toEvent(micros))
        return decision
    }

    private fun timed(
        hook: String,
        start: Long,
    ) {
        val nanos = SystemClock.elapsedRealtimeNanos() - start
        val micros = nanos / 1000
        maxMicros.accumulateAndGet(micros, ::maxOf)
        if (nanos > SLOW_NANOS) {
            slowDecisions.incrementAndGet()
            log.warnRateLimited("slow-$hook", "Slow $hook: ${micros}us (budget ${SLOW_NANOS / 1000}us)")
        }
    }

    private inline fun <T> guarded(
        hook: String,
        block: () -> T?,
    ): T? =
        try {
            block()
        } catch (t: Throwable) {
            log.warnRateLimited("hook-$hook", "$hook hook failed; original arguments kept", t)
            null
        }

    private fun Decision.toEvent(micros: Long) =
        DecisionEvent(
            timeMillis = System.currentTimeMillis(),
            packageName = packageName,
            conversationKey = matchedKey ?: identity.primaryKey,
            displayName = identity.displayName,
            isGroup = identity.isGroup,
            senderKey = identity.senderKey,
            senderName = identity.senderName,
            contactId = contactId,
            outcome = outcome.name,
            clipUri = clipUri,
            detail = detail,
            hookMicros = micros,
        )

    /**
     * The last few decisions by record identity. `playVibration` follows `playSound` for the same record within the
     * same `buzzBeepBlinkLocked` call, so a handful of slots is plenty; records are held weakly.
     */
    private class RecentDecisions {
        private val records = arrayOfNulls<WeakReference<Any>>(SLOTS)
        private val decisions = arrayOfNulls<Decision>(SLOTS)
        private var next = 0

        @Synchronized
        fun get(record: Any): Decision? {
            for (i in 0 until SLOTS) if (records[i]?.get() === record) return decisions[i]
            return null
        }

        @Synchronized
        fun put(
            record: Any,
            decision: Decision,
        ) {
            records[next] = WeakReference(record)
            decisions[next] = decision
            next = (next + 1) % SLOTS
        }

        private companion object {
            const val SLOTS = 8
        }
    }

    companion object {
        const val ATTENTION_HELPER = "com.android.server.notification.NotificationAttentionHelper"
        const val NOTIFICATION_RECORD = "com.android.server.notification.NotificationRecord"
        const val PLAY_SOUND = "playSound"
        const val PLAY_VIBRATION = "playVibration"

        /** The brief's budget: under 5 ms of work on the notification path. */
        const val SLOW_NANOS: Long = 5_000_000

        private val EMPTY_IDENTITY = ConversationIdentity(emptyList(), false, null, null, null)

        private const val WARM_UP_KEY = "warm-up@lid"

        /** One routed chat with an auto haptic, for [warmUp]'s throwaway router. */
        private val WARM_UP_TABLE =
            RoutingTable(
                generation = 0,
                enabled = true,
                voices =
                    listOf(
                        Voice(
                            packId = "warm-up",
                            characterId = "warm-up",
                            clips = listOf(Clip(uri = Targets.SELF_TEST_CLIP, haptic = Targets.SELF_TEST_HAPTIC)),
                            haptic = Haptic.Auto,
                        ),
                    ),
                routes =
                    listOf(
                        Route(Targets.MESSENGERS.first(), WARM_UP_KEY, voice = 0, seed = 0, flags = 0, contactId = 0),
                    ),
            )

        /** A short swell, built once with the device's capabilities to load the envelope path off the hot path. */
        private val WARM_UP_ENVELOPE =
            Haptic.Custom(
                listOf(
                    HapticEvent.Envelope(
                        atMs = 0,
                        points =
                            listOf(
                                ControlPoint(milliIntensity = 600, milliSharpness = 500, durationMs = 40),
                                ControlPoint(milliIntensity = 0, milliSharpness = 500, durationMs = 60),
                            ),
                    ),
                ),
            )

        /** One line with what the vibrator can play, e.g. `Vibrator: builder, envelopes 16 points 20..1000ms, …`. */
        internal fun describe(
            capabilities: HapticCapabilities,
            read: Boolean,
        ): String =
            buildString {
                append(if (read) "Vibrator: " else "Vibrator unreadable, assuming: ")
                append(if (capabilities.builderSupported) "builder" else "no builder")
                if (capabilities.envelopesSupported) {
                    append(", envelopes ").append(capabilities.maxControlPoints).append(" points ")
                    append(capabilities.minControlPointMs).append("..").append(capabilities.maxControlPointMs)
                    append("ms, max ").append(capabilities.maxEnvelopeMs).append("ms")
                } else {
                    append(", no envelopes")
                }
                append(", amplitude control ").append(if (capabilities.amplitudeControl) "yes" else "no")
                append(", primitives ")
                val primitives = capabilities.primitiveDurationsMs
                if (primitives.isEmpty()) {
                    append("none")
                } else {
                    primitives.entries.joinTo(this, " ") { "${it.key.name.lowercase()}=${it.value}ms" }
                }
            }
    }
}
