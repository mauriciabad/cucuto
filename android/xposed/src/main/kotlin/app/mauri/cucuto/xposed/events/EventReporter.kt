package app.mauri.cucuto.xposed.events

import android.content.Context
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
    private val queue: EventQueue = EventQueue(),
) {
    private val thread = HandlerThread("cucuto-events", Process.THREAD_PRIORITY_BACKGROUND)
    private val handler: Handler by lazy { Handler(thread.looper) }
    private val deliver = Runnable { deliver() }

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
            val batch = queue.take(SystemClock.uptimeMillis())
            if (batch.isEmpty()) return
            val ok =
                try {
                    sender.send(batch, status())
                } catch (t: Throwable) {
                    onError("Delivering ${batch.size} decision events failed", t)
                    false
                }
            queue.completed(batch, ok)
            if (!queue.isEmpty) schedule()
        } catch (t: Throwable) {
            onError("Decision event delivery crashed", t)
        }
    }
}

/**
 * [EventSender] that calls the app's event provider through the system context's `ContentResolver`.
 *
 * The provider is verified before every call: it must belong to the module's own APK ([moduleUid]), so that if
 * Cucuto were uninstalled while the module is still loaded, another app claiming the authority would receive
 * nothing. Starting the app's process for a call is the system's normal provider behaviour; [EventQueue] keeps that
 * to at most one wake-up per interval.
 */
internal class ProviderEventSender(
    private val moduleUid: () -> Int?,
) : EventSender {
    private val uri: Uri = Uri.parse("content://${EventContract.AUTHORITY}")

    @Volatile private var context: Context? = null

    override fun send(
        events: List<DecisionEvent>,
        status: ModuleStatus,
    ): Boolean {
        val ctx = context ?: systemContext()?.also { context = it } ?: return false
        val provider = ctx.packageManager.resolveContentProvider(EventContract.AUTHORITY, 0) ?: return false
        val expectedUid = moduleUid()
        if (expectedUid == null || provider.applicationInfo?.uid != expectedUid) return false

        val extras =
            Bundle().apply {
                putInt(EventContract.EXTRA_VERSION, EventContract.VERSION)
                putParcelableArrayList(EventContract.EXTRA_EVENTS, ArrayList(events.map { it.toMap().toBundle() }))
                putBundle(EventContract.EXTRA_STATUS, status.toMap().toBundle())
            }
        val result = ctx.contentResolver.call(uri, EventContract.METHOD_REPORT, null, extras) ?: return false
        return result.getInt(EventContract.RESULT_ACCEPTED, -1) >= 0
    }

    private companion object {
        /** `ActivityThread.currentActivityThread().getSystemContext()`: the system context inside system_server. */
        fun systemContext(): Context? =
            try {
                val activityThread = Class.forName("android.app.ActivityThread")
                val current = activityThread.getMethod("currentActivityThread").invoke(null)
                activityThread.getMethod("getSystemContext").invoke(current) as? Context
            } catch (_: Throwable) {
                null
            }
    }
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
