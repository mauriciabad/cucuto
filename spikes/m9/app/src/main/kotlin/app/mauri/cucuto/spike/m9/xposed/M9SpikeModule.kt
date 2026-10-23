package app.mauri.cucuto.spike.m9.xposed

import android.app.Notification
import android.media.AudioAttributes
import android.net.Uri
import android.os.VibrationEffect
import android.service.notification.StatusBarNotification
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.util.Collections

/**
 * Cucuto M9 spike: a throwaway module for issue #50. Scope: System Framework (`system`) only.
 *
 * What it answers on the device (docs/m9-report.md §7):
 *  1. Telecom's classes resolve from the system_server class loader on Android 17, so `system`
 *     stays the only scope (static analysis says yes: service-telecom.jar is on SYSTEMSERVERCLASSPATH).
 *  2. For a SIM call: which `Ringer` path runs, what `RingtoneFactory` returns, and whether the
 *     ringtone plays through a local MediaPlayer in system_server (uid 1000).
 *  3. With `debug.cucuto.m9.swap=1`: swapping `Call.getRingtone()` changes the ringtone with every
 *     Telecom decision (DND, volume, vibrate mode, ramping, fallback) left intact, and swapping the
 *     `VibrationEffect` argument of `Ringer.vibrateIfNeeded` changes the ring vibration.
 *  4. For a WhatsApp call: that Telecom sees it as self-managed (so `Ringer` ends early), and what
 *     WhatsApp's incoming-call notification looks like (channel sound, usage, insistent flag,
 *     CallStyle, shortcut ID domain). Observation only: WhatsApp's alerting is never changed.
 *
 * Safety contract, same as M0:
 *  - Every hook body runs in [safely]; nothing can throw into system_server.
 *  - Every hook is registered with [XposedInterface.ExceptionMode.PROTECTIVE].
 *  - Methods are resolved by name + arity; a miss or an ambiguity logs and skips.
 *  - Hooks only ever replace a return value or an argument of a call Telecom already decided to
 *    make. With the debug property unset (the default) the module only observes.
 *  - Nothing personal is logged: see [Redact].
 */
class M9SpikeModule : XposedModule() {

    private companion object {
        const val TAG = "cucuto-m9"

        const val RINGER = "com.android.server.telecom.Ringer"
        const val CALL = "com.android.server.telecom.Call"
        const val RINGTONE_FACTORY = "com.android.server.telecom.RingtoneFactory"
        const val ATTENTION_HELPER = "com.android.server.notification.NotificationAttentionHelper"

        val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

        /** `CallState.RINGING` and `CallState.SIMULATED_RINGING` in service-telecom.jar. */
        val RINGING_STATES = setOf(4, 13)

        /** Swap switch, settable by `adb shell setprop` (debug.* is writable by the shell user). */
        const val PROP_SWAP = "debug.cucuto.m9.swap"

        /** Optional replacement URI (for example a MediaStore ringtone); defaults to the spike's provider. */
        const val PROP_URI = "debug.cucuto.m9.uri"

        val SPIKE_RINGTONE: Uri = Uri.parse("content://app.mauri.cucuto.spike.m9.ring/ring/spike_ring.wav")

        /** Three short pulses and a pause, repeating from index 0: unmistakably not a stock ring vibration. */
        val SPIKE_VIBRATION: VibrationEffect = VibrationEffect.createWaveform(
            longArrayOf(0, 90, 110, 90, 110, 90, 900),
            intArrayOf(0, 255, 0, 255, 0, 255, 0),
            0,
        )
    }

    private val calls = CallReader()

    /** Call tokens already logged by the getRingtone hook, so one ring logs once, not three times. */
    private val loggedSwaps: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        safely("onModuleLoaded") {
            info("loaded in process=${param.processName} isSystemServer=${param.isSystemServer}")
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        safely("onSystemServerStarting") {
            installTelecomHooks(param.classLoader)
            installNotificationObserver(param.classLoader)
        }
    }

    // ------------------------------------------------------------------ Telecom (phone calls)

    private fun installTelecomHooks(cl: ClassLoader) {
        val ringer = loadClassOrNull(cl, RINGER)
        val call = loadClassOrNull(cl, CALL)
        val factory = loadClassOrNull(cl, RINGTONE_FACTORY)
        info("telecom classes: Ringer=${ringer != null} Call=${call != null} RingtoneFactory=${factory != null} (loader=${cl.javaClass.simpleName})")

        // 1. Observation: every call Telecom asks to ring, SIM or self-managed.
        //    Ringer.startRinging(Call foregroundCall, boolean isHfpDeviceAttached)
        if (ringer != null) {
            hookByName(ringer, "startRinging", argCount = 2) { chain ->
                safely("startRinging") {
                    chain.getArg(0)?.let { info("START_RINGING ${calls.describe(it)} hfp=${chain.getArg(1)}") }
                }
                val result = chain.proceed()
                safely("startRinging.after") { info("START_RINGING returned acquireFocus=$result") }
                result
            }
        }

        // 2. Observation: what RingtoneFactory resolved, and whether it is a local player in system_server.
        //    RingtoneFactory.getRingtone(Call, VolumeShaper.Configuration, boolean hapticChannelsMuted)
        if (factory != null) {
            hookByName(factory, "getRingtone", argCount = 3) { chain ->
                val result = chain.proceed()
                safely("RingtoneFactory.getRingtone") {
                    val pair = result as? android.util.Pair<*, *>
                    val ringtone = pair?.second
                    val local = ringtone?.let { r ->
                        runCatching { r.javaClass.getMethod("hasLocalPlayer").invoke(r) }.getOrNull()
                    }
                    val vib = ringtone?.let { r ->
                        runCatching { r.javaClass.getMethod("getVibrationEffect").invoke(r) }.getOrNull()
                    }
                    info(
                        "RINGTONE uri=${Redact.uri(pair?.first as? Uri)} loaded=${ringtone != null} " +
                            "localPlayer=$local ringtoneVibration=${vib != null} shaper=${chain.getArg(1) != null} " +
                            "hapticsMuted=${chain.getArg(2)}",
                    )
                }
                result
            }
        }

        // 3. Swap (debug property only): Call.getRingtone() has exactly two callers on this build,
        //    RingtoneFactory.getRingtone and Ringer.hasExplicitVibration. Replacing its return value
        //    keeps the ownership check, audio attributes, ramping, haptic-channel muting and the
        //    fallback to the default ringtone (when our URI cannot be opened) all Telecom's.
        if (call != null) {
            hookByName(call, "getRingtone", argCount = 0) { chain ->
                val original = chain.proceed()
                var result: Any? = original
                safely("Call.getRingtone") {
                    if (!swapEnabled()) return@safely
                    val c = chain.thisObject ?: return@safely
                    if (calls.isSelfManaged(c) == true || calls.isIncoming(c) != true) return@safely
                    if (calls.state(c) !in RINGING_STATES) return@safely
                    val replacement = swapUri()
                    result = replacement
                    val token = Redact.token(calls.id(c))
                    if (loggedSwaps.add(token)) {
                        if (loggedSwaps.size > 64) loggedSwaps.remove(loggedSwaps.first())
                        info("SWAP_RINGTONE call=$token original=${Redact.uri(original as? Uri)} -> ${Redact.uri(replacement)}")
                    }
                }
                result
            }
        }

        // 4. Swap (debug property only): the ring vibration, downstream of every decision.
        //    Ringer.vibrateIfNeeded(boolean isUsingAudioCoupledHaptics, Call, VibrationEffect, Uri)
        //    It returns early for audio-coupled haptics and for ringtones with a vibration_uri.
        if (ringer != null) {
            hookByName(ringer, "vibrateIfNeeded", argCount = 4) { chain ->
                val args = chain.args.toTypedArray()
                safely("vibrateIfNeeded") {
                    val c = args.getOrNull(1)
                    info(
                        "VIBRATE_IF_NEEDED audioCoupled=${args.getOrNull(0)} ringtone=${Redact.uri(args.getOrNull(3) as? Uri)} " +
                            (c?.let { "call=${Redact.token(calls.id(it))}" } ?: ""),
                    )
                    if (swapEnabled() && c != null && calls.isSelfManaged(c) != true) {
                        args[2] = SPIKE_VIBRATION
                        info("SWAP_VIBRATION -> spike waveform (repeating)")
                    }
                }
                chain.proceed(args)
            }
        }
    }

    // ------------------------------------------------------------------ WhatsApp calls (observe only)

    /**
     * Read-only: logs the shape of every WhatsApp record that reaches the alerting stage on a call
     * channel or with `CATEGORY_CALL`. Always proceeds with the original arguments.
     */
    private fun installNotificationObserver(cl: ClassLoader) {
        val helper = loadClassOrNull(cl, ATTENTION_HELPER) ?: return
        hookByName(helper, "buzzBeepBlinkLocked", argCount = 2) { chain ->
            safely("buzzBeepBlinkLocked") {
                val record = chain.getArg(0) ?: return@safely
                val sbn = record.call("getSbn") as? StatusBarNotification ?: return@safely
                if (sbn.packageName !in WHATSAPP_PACKAGES) return@safely
                val n = sbn.notification
                val isCallish = n.category == Notification.CATEGORY_CALL ||
                    n.channelId?.startsWith("voip_notification") == true
                if (!isCallish) return@safely
                val attrs = record.call("getAudioAttributes") as? AudioAttributes
                info(
                    "WA_CALL_NOTIFICATION channel=${Redact.channel(n.channelId)} category=${n.category} " +
                        "template=${n.extras?.getString(Notification.EXTRA_TEMPLATE)?.substringAfterLast('$')} " +
                        "sound=${Redact.uri(record.call("getSound") as? Uri)} usage=${attrs?.usage} " +
                        "vibration=${record.call("getVibration") != null} insistent=${n.flags and Notification.FLAG_INSISTENT != 0} " +
                        "ongoing=${n.flags and Notification.FLAG_ONGOING_EVENT != 0} fsi=${n.fullScreenIntent != null} " +
                        "update=${record.readField("isUpdate")} shortcut=${Redact.jid(n.shortcutId)} " +
                        "people=${n.extras?.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, android.app.Person::class.java)?.size}",
                )
            }
            chain.proceed()
        }
    }

    // ------------------------------------------------------------------ plumbing

    private fun swapEnabled(): Boolean = systemProperty(PROP_SWAP) == "1"

    private fun swapUri(): Uri = systemProperty(PROP_URI)?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: SPIKE_RINGTONE

    /** `android.os.SystemProperties` is hidden API; system_server may call it, reflectively. */
    private fun systemProperty(key: String): String? = try {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java, String::class.java)
            .invoke(null, key, "") as? String
    } catch (_: Throwable) {
        null
    }

    private fun Any.call(name: String): Any? = try {
        var c: Class<*>? = javaClass
        var m: java.lang.reflect.Method? = null
        while (c != null && m == null) {
            m = c.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 }
            c = c.superclass
        }
        m?.apply { isAccessible = true }?.invoke(this)
    } catch (_: Throwable) {
        null
    }

    private fun Any.readField(name: String): Any? = try {
        var c: Class<*>? = javaClass
        var f: java.lang.reflect.Field? = null
        while (c != null && f == null) {
            f = c.declaredFields.firstOrNull { it.name == name }
            c = c.superclass
        }
        f?.apply { isAccessible = true }?.get(this)
    } catch (_: Throwable) {
        null
    }

    private fun hookByName(owner: Class<*>, name: String, argCount: Int, hooker: XposedInterface.Hooker) {
        safely("hook:$name") {
            val candidates = owner.declaredMethods.filter { it.name == name && it.parameterCount == argCount }
            when {
                candidates.isEmpty() -> warn("no method $name/$argCount on ${owner.name}; skipped")
                candidates.size > 1 -> warn("ambiguous $name/$argCount on ${owner.name} (${candidates.size}); skipped")
                else -> {
                    val m = candidates.single()
                    hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("cucuto-m9-${owner.simpleName}-$name")
                        .intercept(hooker)
                    info("hooked ${owner.simpleName}.$name(${m.parameterTypes.joinToString { it.simpleName }})")
                }
            }
        }
    }

    private fun loadClassOrNull(cl: ClassLoader, name: String): Class<*>? = try {
        cl.loadClass(name)
    } catch (t: Throwable) {
        warn("loadClass($name) failed: ${t.javaClass.simpleName}")
        null
    }

    /** The hard rule for system_server: nothing escapes. */
    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            try {
                log(Log.WARN, TAG, "swallowed in $what", t)
            } catch (_: Throwable) {
            }
        }
    }

    private fun info(msg: String) {
        try {
            log(Log.INFO, TAG, msg)
        } catch (_: Throwable) {
        }
    }

    private fun warn(msg: String) {
        try {
            log(Log.WARN, TAG, msg)
        } catch (_: Throwable) {
        }
    }
}
