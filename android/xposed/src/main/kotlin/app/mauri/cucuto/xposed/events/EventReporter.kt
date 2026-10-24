package app.mauri.cucuto.xposed.events

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock

/** Delivers one batch to the app. Returns true when the app accepted it. Runs on the reporter's thread only. */
internal fun interface EventSender {
    fun send(
        events: List<DecisionEvent>,
        status: ModuleStatus,
    ): Boolean
}

/**
 * Sends decision events from `system_server` to the app (issue #17) without ever blocking the notification path:
 * [report] is a queue insert plus at most one `Handler` post; the Binder call happens later on a dedicated
 * background thread, batched and rate-limited by [EventQueue].
 */
internal class EventReporter(
    private val sender: EventSender,
    private val status: () -> ModuleStatus,
    private val onError: (String, Throwable?) -> Unit,
    private val onQueuedListener: (DecisionEvent) -> Unit = {},
    private val onDelivered: (count: Int) -> Unit = {},
    private val queue: EventQueue = EventQueue(),
) {
    private val thread = HandlerThread("cucuto-events", Process.THREAD_PRIORITY_BACKGROUND)

    /** The background thread's handler, also used to receive the app's nudge broadcast. */
    val handler: Handler by lazy { Handler(thread.looper) }
    private val deliver = Runnable { deliver() }

    /** Whether the last attempt failed (or none succeeded yet), so the next success is worth one log line. */
    private var lastFailed = true

    /** Starts the background thread. Call once, outside the notification path. */
    fun start() {
        thread.start()
        handler // Creates the handler now, not on the first notification.
    }

    val dropped: Long get() = queue.dropped

    /** Runs [block] on the background thread after [delayMillis]. Never throws. */
    fun post(
        delayMillis: Long = 0,
        block: () -> Unit,
    ) {
        try {
            handler.postDelayed(
                {
                    try {
                        block()
                    } catch (t: Throwable) {
                        onError("Background task failed", t)
                    }
                },
                delayMillis,
            )
        } catch (t: Throwable) {
            onError("Scheduling a background task failed", t)
        }
    }

    /**
     * Queues [event] and hands it to [onQueued] on the background thread (the module writes its log line there).
     * Cheap and never throws.
     */
    fun report(event: DecisionEvent) {
        try {
            if (queue.offer(event)) schedule()
            handler.post { onQueued(event) }
        } catch (t: Throwable) {
            onError("Queueing a decision event failed", t)
        }
    }

    /**
     * Sends the module's status to the app even with no event queued (same schedule and rate limit as events), so
     * the Diagnostics screen learns about a new routing table. Cheap and never throws.
     */
    fun reportStatus() {
        try {
            if (queue.requestStatus()) schedule()
        } catch (t: Throwable) {
            onError("Scheduling a status report failed", t)
        }
    }

    private fun onQueued(event: DecisionEvent) {
        try {
            onQueuedListener(event)
        } catch (t: Throwable) {
            onError("Decision event listener failed", t)
        }
    }

    private fun schedule() {
        val at = queue.nextDeliveryAt(SystemClock.uptimeMillis())
        handler.removeCallbacks(deliver)
        handler.postAtTime(deliver, at)
    }

    private fun deliver() {
        try {
            if (!queue.hasWork) return
            // Empty when only a status report was requested.
            val batch = queue.take(SystemClock.uptimeMillis())
            val ok =
                try {
                    sender.send(batch, status())
                } catch (t: Throwable) {
                    onError("Delivering ${batch.size} decision events failed", t)
                    false
                }
            queue.completed(batch, ok)
            if (ok && lastFailed) onDelivered(batch.size)
            lastFailed = !ok
            if (queue.hasWork) schedule()
        } catch (t: Throwable) {
            onError("Decision event delivery crashed", t)
        }
    }
}

/**
 * Why a batch was not delivered (or a table not pulled). Carries no stack trace: the reason is the whole story,
 * logged on one line. [unavailable] means the provider could not be reached at all (not installed for user 0, or the
 * user is still locked), so the app's process was not started.
 */
internal class DeliveryRefused(
    reason: String,
    val unavailable: Boolean = false,
) : Exception(reason) {
    override fun fillInStackTrace(): Throwable = this
}

/**
 * Decides whether the app that owns the event provider is the module's own app (pure, unit-tested): same package
 * name, and the installed package holds every certificate that signed the module's APK. Returns null when it may
 * receive events, otherwise the reason it may not.
 *
 * This replaced a uid comparison: LSPosed builds the module's `ApplicationInfo` for `system_server` by parsing the
 * APK file, so its `uid` is never the installed app's uid (device finding, issue #17).
 */
internal fun providerRefusal(
    providerPackage: String?,
    modulePackage: String?,
    moduleSigners: List<ByteArray>?,
    holdsCertificate: (ByteArray) -> Boolean,
): String? =
    when {
        modulePackage.isNullOrEmpty() -> "the module's package name is unknown"
        providerPackage != modulePackage -> "the event provider belongs to another package"
        moduleSigners.isNullOrEmpty() -> "the module APK's signing certificate could not be read"
        !moduleSigners.all(holdsCertificate) -> "the event provider's app is signed by another key"
        else -> null
    }

/**
 * The app's event provider, seen from `system_server` (user 0, the system context's user), verified before every
 * use ([providerRefusal]): it must belong to the module's package and be signed by the certificate that signed the
 * module's APK, so that if Cucuto were uninstalled while the module is still loaded, another app claiming the
 * authority would receive nothing. The certificate is read from the module's APK once ([prepare], right after boot,
 * while that file still exists; an app update replaces it but the module keeps running until the next reboot).
 * Shared by [ProviderEventSender] and the routing table pull (ADR 0048).
 */
internal class AppProvider(
    private val moduleInfo: () -> ApplicationInfo?,
) {
    @Volatile private var context: Context? = null

    @Volatile private var moduleSigners: List<ByteArray>? = null

    /** Reads the module APK's signing certificates now (background thread, after boot). Never throws. */
    fun prepare() {
        try {
            val ctx = context() ?: return
            signers(ctx)
        } catch (_: Throwable) {
            // Retried on the first use.
        }
    }

    /**
     * The system context, once the provider is verified as the module's own app. Throws [DeliveryRefused] with the
     * reason otherwise. Background thread only (package manager calls).
     */
    fun verified(): Context {
        val ctx = context() ?: throw DeliveryRefused("no system context", unavailable = true)
        val pm = ctx.packageManager
        // Without match flags, a provider that is not direct-boot aware does not resolve while the user is locked.
        val provider =
            pm.resolveContentProvider(EventContract.AUTHORITY, PackageManager.ComponentInfoFlags.of(0))
                ?: throw DeliveryRefused("the event provider is not installed for user 0", unavailable = true)
        val refusal =
            providerRefusal(
                providerPackage = provider.packageName,
                modulePackage = moduleInfo()?.packageName,
                moduleSigners = signers(ctx),
            ) { cert -> pm.hasSigningCertificate(provider.packageName, cert, PackageManager.CERT_INPUT_RAW_X509) }
        if (refusal != null) throw DeliveryRefused(refusal)
        return ctx
    }

    private fun context(): Context? = context ?: systemContext()?.also { context = it }

    /** The certificates that signed the module's APK, read once from the file LSPosed loaded the module from. */
    private fun signers(ctx: Context): List<ByteArray>? {
        moduleSigners?.let { return it }
        val module = moduleInfo() ?: return null
        val apk = module.sourceDir ?: return null
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val pm = ctx.packageManager
        // The installed package, if it is still the very APK the module was loaded from (no update since boot)...
        val installed =
            try {
                pm.getPackageInfo(module.packageName, flags).takeIf { it.applicationInfo?.sourceDir == apk }
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        // ...otherwise the APK file itself, while it still exists.
        val signing = (installed ?: pm.getPackageArchiveInfo(apk, flags))?.signingInfo ?: return null
        val certs = signing.apkContentsSigners.orEmpty().map { it.toByteArray() }
        return certs.takeIf { it.isNotEmpty() }?.also { moduleSigners = it }
    }
}

/**
 * [EventSender] that calls the app's event provider through the system context's `ContentResolver`, once
 * [AppProvider] verified it. Every refusal throws [DeliveryRefused] so the reporter logs why. Starting the app's
 * process for a call is the system's normal provider behaviour; [EventQueue] keeps that to at most one wake-up per
 * interval.
 */
internal class ProviderEventSender(
    private val provider: AppProvider,
) : EventSender {
    private val uri: Uri = Uri.parse("content://${EventContract.AUTHORITY}")

    override fun send(
        events: List<DecisionEvent>,
        status: ModuleStatus,
    ): Boolean {
        val ctx = provider.verified()
        val extras =
            Bundle().apply {
                putInt(EventContract.EXTRA_VERSION, EventContract.VERSION)
                putParcelableArrayList(EventContract.EXTRA_EVENTS, ArrayList(events.map { it.toMap().toBundle() }))
                putBundle(EventContract.EXTRA_STATUS, status.toMap().toBundle())
            }
        val result =
            ctx.contentResolver.call(uri, EventContract.METHOD_REPORT, null, extras)
                ?: throw DeliveryRefused("the event provider returned no result")
        val accepted = result.getInt(EventContract.RESULT_ACCEPTED, -1)
        if (accepted < 0) throw DeliveryRefused("the event provider did not accept the batch")
        return true
    }
}

/** `ActivityThread.currentActivityThread().getSystemContext()`: the system context inside system_server. */
internal fun systemContext(): Context? =
    try {
        val activityThread = Class.forName("android.app.ActivityThread")
        val current = activityThread.getMethod("currentActivityThread").invoke(null)
        activityThread.getMethod("getSystemContext").invoke(current) as? Context
    } catch (_: Throwable) {
        null
    }

/** Writes a flat map of primitives and strings into a Bundle. Unknown value types are skipped. */
internal fun Map<String, Any?>.toBundle(): Bundle =
    Bundle(size).also { b ->
        for ((key, value) in this) {
            when (value) {
                null -> Unit
                is String -> b.putString(key, value)
                is Long -> b.putLong(key, value)
                is Int -> b.putInt(key, value)
                is Boolean -> b.putBoolean(key, value)
            }
        }
    }
