package app.mauri.cucuto.spike.xposed

import android.net.Uri
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.lang.reflect.Method

/**
 * cucuto M0 spike — throwaway module. Scope: System Framework (`android`) only.
 *
 * Safety contract (see docs/m0-report.md):
 *  - Every hook body is wrapped in [safely]; nothing we write can throw into `system_server`.
 *  - Every hook is additionally registered with [XposedInterface.ExceptionMode.PROTECTIVE], so the
 *    framework itself swallows anything that escapes and proceeds as if the hook did not exist.
 *  - Hooks are resolved by name with a tolerated-miss: a missing method logs and no-ops.
 *  - We only ever *replace an argument* of a playback call that the framework had already decided
 *    to make. We never decide whether to alert. Muting, DND, ringer mode, cooldown and
 *    alert-once therefore keep working exactly as AOSP implements them.
 *  - Sound/vibration substitution is gated to [DEBUG_PACKAGES]. WhatsApp is observed, never altered.
 */
class SpikeModule : XposedModule() {

    private companion object {
        const val TAG = "cucuto-m0"

        /** Packages we observe and log. */
        val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

        /**
         * Debug-only allowlist (issue #11): lets the spike's own debug app exercise the swap path
         * without needing a real WhatsApp message. The production module must never carry this.
         */
        val DEBUG_PACKAGES = setOf("app.mauri.cucuto.spike")

        val OBSERVED_PACKAGES = WHATSAPP_PACKAGES + DEBUG_PACKAGES

        const val ATTENTION_HELPER = "com.android.server.notification.NotificationAttentionHelper"
        const val NOTIFICATION_RECORD = "com.android.server.notification.NotificationRecord"

        /** Clip served by the spike app's read-only provider. */
        val SPIKE_CLIP: Uri = Uri.parse("content://app.mauri.cucuto.spike.clips/clip/spike_chirp.wav")
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        safely("onModuleLoaded") {
            info("module loaded in process=${param.processName} isSystemServer=${param.isSystemServer}")
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        safely("onSystemServerStarting") {
            info("system_server starting; installing hooks")
            installHooks(param.classLoader)
        }
    }

    // ---------------------------------------------------------------- hooks

    private fun installHooks(cl: ClassLoader) {
        val helper = loadClassOrNull(cl, ATTENTION_HELPER)
        if (helper == null) {
            warn("$ATTENTION_HELPER not found on this build — nothing hooked")
            return
        }
        val record = loadClassOrNull(cl, NOTIFICATION_RECORD)
        if (record == null) {
            warn("$NOTIFICATION_RECORD not found on this build — nothing hooked")
            return
        }
        val reader = RecordReader(record)

        // 1. Observation point: every notification that reaches the alerting stage (issue #9).
        //    Read-only: we always proceed with the original arguments.
        hookByName(helper, "buzzBeepBlinkLocked", argCount = 2) { chain ->
            safely("buzzBeepBlinkLocked") {
                val rec = chain.getArg(0)
                if (rec != null && reader.packageOf(rec) in OBSERVED_PACKAGES) {
                    info("ALERTING ${reader.describe(rec)}")
                }
            }
            chain.proceed()
        }

        // 2. Sound substitution (issue #11). playSound(NotificationRecord, Uri) is called only
        //    after the framework has decided this record should make a sound.
        hookByName(helper, "playSound", argCount = 2) { chain ->
            var args = chain.args.toTypedArray()
            safely("playSound") {
                val rec = args.getOrNull(0)
                val pkg = rec?.let { reader.packageOf(it) }
                if (pkg != null && pkg in OBSERVED_PACKAGES) {
                    info("PLAY_SOUND pkg=$pkg original=${Redact.uri(args.getOrNull(1) as? Uri)} ${reader.describe(rec)}")
                    if (pkg in DEBUG_PACKAGES) {
                        args[1] = SPIKE_CLIP
                        info("PLAY_SOUND swapped -> $SPIKE_CLIP")
                    }
                }
            }
            chain.proceed(args)
        }

        // 3. Vibration substitution (issue #11).
        //    playVibration(NotificationRecord, VibrationEffect, boolean)
        hookByName(helper, "playVibration", argCount = 3) { chain ->
            var args = chain.args.toTypedArray()
            safely("playVibration") {
                val rec = args.getOrNull(0)
                val pkg = rec?.let { reader.packageOf(it) }
                if (pkg != null && pkg in OBSERVED_PACKAGES) {
                    info("PLAY_VIBRATION pkg=$pkg original=${args.getOrNull(1)} ${reader.describe(rec)}")
                    if (pkg in DEBUG_PACKAGES) {
                        val effect = SpikeHaptics.build()
                        if (effect != null) {
                            args[1] = effect
                            info("PLAY_VIBRATION swapped -> ${SpikeHaptics.lastStrategy}")
                        } else {
                            warn("PLAY_VIBRATION no effect built (${SpikeHaptics.lastStrategy}); keeping original")
                        }
                    }
                }
            }
            chain.proceed(args)
        }
    }

    /**
     * Resolves a declared method by name and argument count, then installs [hooker].
     *
     * Resolving by name + arity rather than exact signature survives the inner-class and
     * parameter-type churn the brief flags as the top risk: `Signals` is a package-private inner
     * class, and its shape changes between QPRs. A miss is logged and ignored, never fatal.
     */
    private fun hookByName(
        owner: Class<*>,
        name: String,
        argCount: Int,
        hooker: XposedInterface.Hooker,
    ) {
        safely("hook:$name") {
            val candidates = owner.declaredMethods.filter { it.name == name && it.parameterCount == argCount }
            when {
                candidates.isEmpty() -> warn("no method $name/$argCount on ${owner.name} — skipped")
                candidates.size > 1 -> warn("ambiguous $name/$argCount on ${owner.name} (${candidates.size}) — skipped")
                else -> {
                    val m: Method = candidates.single()
                    hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("cucuto-m0-$name")
                        .intercept(hooker)
                    info("hooked ${owner.simpleName}.$name(${m.parameterTypes.joinToString { it.simpleName }})")
                }
            }
        }
    }

    // ---------------------------------------------------------------- plumbing

    private fun loadClassOrNull(cl: ClassLoader, name: String): Class<*>? = try {
        cl.loadClass(name)
    } catch (t: Throwable) {
        warn("loadClass($name) failed: ${t.javaClass.simpleName}")
        null
    }

    /**
     * Runs [block], swallowing everything. This is the hard rule for `system_server`: a throw here
     * can boot-loop the phone, and there is no outcome of this spike worth that.
     */
    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            try {
                Log.w(TAG, "swallowed in $what", t)
                log(Log.WARN, TAG, "swallowed in $what", t)
            } catch (_: Throwable) {
                // Even logging must not throw.
            }
        }
    }

    private fun info(msg: String) {
        Log.i(TAG, msg)
        try {
            log(Log.INFO, TAG, msg)
        } catch (_: Throwable) {
        }
    }

    private fun warn(msg: String) {
        Log.w(TAG, msg)
        try {
            log(Log.WARN, TAG, msg)
        } catch (_: Throwable) {
        }
    }
}
