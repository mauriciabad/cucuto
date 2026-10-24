package app.mauri.cucuto.spike.waring.xposed

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Method

/**
 * Cucuto WhatsApp ringtone spike (#215): a throwaway module scoped to `com.whatsapp` only. Findings, design and the
 * device test plan: docs/whatsapp-ringtone-spike.md.
 *
 * What it answers on the device:
 *  1. WhatsApp 2.26.42.12 appears to get its call ringtone from `RingtoneManager.getRingtone(Context, Uri)` and ring
 *     with `Ringtone.play()`. Does that hold at runtime, on which thread, how many times per call?
 *  2. Is WhatsApp's incoming-call notification already posted when `getRingtone` runs (the identification race), or
 *     is the ringtone sometimes loaded earlier?
 *  3. In swap mode: does substituting the URI argument make a call from the test contact ring with the chosen file,
 *     with WhatsApp's looping, vibration, stop, silent and DND behaviour unchanged? Can WhatsApp's process open a
 *     file in `Ringtones/cucuto/` through MediaStore?
 *
 * Hooks, all on framework methods (never on WhatsApp's own classes):
 *  - `RingtoneManager.getRingtone(Context, Uri)`: the production candidate. Observes, and in swap mode replaces
 *    argument 1. If the replacement cannot be opened (null), it calls the original with WhatsApp's URI.
 *  - `Ringtone.play()` and `Ringtone.stop()`: spike-only observers for timing, for ringtone-usage instances only.
 *
 * Safety contract, the same as the system_server modules:
 *  - [Mode][SpikeConfig.Mode] `off` (the default) installs nothing.
 *  - Every hook body runs in [safely]; nothing can throw into WhatsApp. Every hook uses `ExceptionMode.PROTECTIVE`.
 *  - Methods are resolved by exact signature; a miss logs and skips.
 *  - The hook only replaces the URI WhatsApp already decided to play (after its silent, DND and per-chat checks).
 *  - Nothing personal is logged ([Redact]); no message notification is read ([CallNotificationReader]).
 */
class WaRingtoneSpikeModule : XposedModule() {
    private val calls = CallNotificationReader()

    /** WhatsApp's application context, kept from the first `getRingtone` call so `play` can look up the call too. */
    @Volatile private var appContext: Context? = null

    @Volatile private var lastGetRingtoneAt: Long = 0

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        safely("onModuleLoaded") {
            info("loaded in process=${param.processName} by $frameworkName $frameworkVersion (API $apiVersion)")
        }
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        safely("onPackageLoaded") {
            if (param.packageName != WHATSAPP || !param.isFirstPackage) return@safely
            val config = config()
            if (config.mode == SpikeConfig.Mode.OFF) {
                info("mode off: no hooks installed in WhatsApp")
                return@safely
            }
            hook(
                RingtoneManager::class.java.getDeclaredMethod("getRingtone", Context::class.java, Uri::class.java),
                "getRingtone",
            ) { chain -> getRingtone(chain) }
            hook(Ringtone::class.java.getDeclaredMethod("play"), "play") { chain -> observe(chain, "PLAY") }
            hook(Ringtone::class.java.getDeclaredMethod("stop"), "stop") { chain -> observe(chain, "STOP") }
            info("hooks installed (mode ${config.mode.name.lowercase()})")
        }
    }

    // ------------------------------------------------------------------ hooks

    private fun getRingtone(chain: XposedInterface.Chain): Any? {
        val replacement: Uri? =
            guarded("getRingtone") {
                val started = SystemClock.elapsedRealtimeNanos()
                val context = chain.getArg(0) as? Context
                val original = chain.getArg(1) as? Uri
                if (context != null && appContext == null) appContext = context.applicationContext ?: context
                lastGetRingtoneAt = SystemClock.elapsedRealtime()
                val call = context?.let(calls::read)
                val decision = SwapDecision.decide(config(), original?.toString(), call)
                info(
                    "GET_RINGTONE uri=${Redact.uri(original?.toString())} thread=${threadShape()} " +
                        "callNotification=${describe(call)} decision=${decision.reason} " +
                        "${(SystemClock.elapsedRealtimeNanos() - started) / 1_000}us",
                )
                (decision as? Decision.Swap)?.let { Uri.parse(it.uri) }
            }
        if (replacement == null) return chain.proceed()
        val swapped =
            try {
                chain.proceed(arrayOf(chain.getArg(0), replacement))
            } catch (t: Throwable) {
                warn("SWAP threw; WhatsApp's own ringtone", t)
                null
            }
        if (swapped != null) {
            info("SWAP ok ${Redact.uri(replacement.toString())}")
            return swapped
        }
        // RingtoneManager returns null when the file cannot be opened: fall back to WhatsApp's own choice.
        info("SWAP failed to open; WhatsApp's own ringtone")
        return chain.proceed()
    }

    /** Spike-only: logs ringtone-usage `play`/`stop` calls with timing; changes nothing. */
    private fun observe(
        chain: XposedInterface.Chain,
        event: String,
    ): Any? {
        guarded(event) {
            val ringtone = chain.thisObject as? Ringtone ?: return@guarded
            val usage = ringtone.audioAttributes?.usage ?: return@guarded
            if (usage != AudioAttributes.USAGE_NOTIFICATION_RINGTONE) return@guarded
            val sinceGet = if (lastGetRingtoneAt == 0L) -1 else SystemClock.elapsedRealtime() - lastGetRingtoneAt
            val call = appContext?.let(calls::read)
            info(
                "$event usage=$usage playing=${ringtone.isPlaying} thread=${threadShape()} " +
                    "sinceGetRingtoneMs=$sinceGet callNotification=${describe(call)}",
            )
        }
        return chain.proceed()
    }

    // ------------------------------------------------------------------ plumbing

    private fun config(): SpikeConfig =
        SpikeConfig.read(
            try {
                getRemotePreferences(SpikeConfig.GROUP)
            } catch (_: Throwable) {
                null
            },
        )

    private fun hook(
        method: Method,
        name: String,
        hooker: XposedInterface.Hooker,
    ) {
        try {
            hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("cucuto-waring-$name")
                .intercept(hooker)
            info("hooked ${method.declaringClass.simpleName}.$name")
        } catch (t: Throwable) {
            warn("hooking $name failed", t)
        }
    }

    private fun describe(call: IncomingCall?): String =
        if (call == null) {
            "absent"
        } else {
            val testName = config().testName
            "present(ageMs=${call.ageMillis} group=${call.isGroupCall} testContact=${testName != null && call.name == testName})"
        }

    private fun threadShape(): String =
        if (Looper.myLooper() == Looper.getMainLooper()) "main" else "bg:${Thread.currentThread().name.take(MAX_THREAD_NAME)}"

    private inline fun <T> guarded(
        stage: String,
        block: () -> T?,
    ): T? =
        try {
            block()
        } catch (t: Throwable) {
            warn("$stage failed; WhatsApp keeps its own ringtone", t)
            null
        }

    private inline fun safely(
        stage: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (t: Throwable) {
            warn("$stage failed", t)
        }
    }

    private fun info(message: String) {
        try {
            Log.i(TAG, message)
        } catch (_: Throwable) {
            // Nothing safe left to do.
        }
    }

    private fun warn(
        message: String,
        error: Throwable?,
    ) {
        try {
            Log.w(TAG, message, error)
        } catch (_: Throwable) {
            // Nothing safe left to do.
        }
    }

    private companion object {
        const val TAG = "cucuto-waring"
        const val WHATSAPP = "com.whatsapp"
        const val MAX_THREAD_NAME = 32
    }
}
