package app.mauri.cucuto.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.mauri.cucuto.core.routing.RoutingContract
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Whether a nudge sent by [sender] may make the module re-read its table: only the module's own app, which shares
 * its identity with the broadcast (`BroadcastOptions.setShareIdentityEnabled`). Pure, unit-tested.
 */
internal fun acceptsNudge(
    sender: String?,
    modulePackage: String?,
): Boolean = !modulePackage.isNullOrEmpty() && sender == modulePackage

/**
 * Receives the app's [RoutingContract.ACTION_TABLE_PUBLISHED] broadcast inside `system_server` (ADR 0024). It only
 * checks the sender and hands the announced generation ([RoutingContract.EXTRA_GENERATION], 0 when absent) over to
 * [onNudge]; the table itself is read from the framework or pulled from the app's verified provider (ADR 0048), never
 * from the broadcast. Never throws.
 */
internal class RoutingNudgeReceiver(
    private val modulePackage: () -> String?,
    private val onNudge: (generation: Long) -> Unit,
    private val onRefused: (sender: String?) -> Unit,
) : BroadcastReceiver() {
    override fun onReceive(
        context: Context?,
        intent: Intent?,
    ) {
        try {
            if (intent?.action != RoutingContract.ACTION_TABLE_PUBLISHED) return
            val sender = sentFromPackage
            if (acceptsNudge(sender, modulePackage())) {
                onNudge(intent.getLongExtra(RoutingContract.EXTRA_GENERATION, 0L).coerceAtLeast(0L))
            } else {
                onRefused(sender)
            }
        } catch (_: Throwable) {
            // A broken nudge only delays the table until the next one; never fail inside system_server.
        }
    }
}

/**
 * Runs [task] through [post] once for any number of [request]s made before it starts. A request made while the task
 * runs schedules one more run, so the last publication is always read after it was written. Never throws.
 */
internal class Coalescer(
    private val post: (() -> Unit) -> Unit,
    private val task: () -> Unit,
) {
    private val pending = AtomicBoolean(false)

    fun request() {
        if (!pending.compareAndSet(false, true)) return
        try {
            post {
                pending.set(false)
                task()
            }
        } catch (_: Throwable) {
            pending.set(false)
        }
    }
}

/**
 * The newest generation the app's nudges announced. A burst of nudges coalesces into one refresh, which catches up
 * with this generation once. Thread-safe, never throws.
 */
internal class NudgeTarget {
    private val newest = AtomicLong(0)

    fun offer(generation: Long) {
        newest.accumulateAndGet(generation) { a, b -> maxOf(a, b) }
    }

    fun get(): Long = newest.get()
}
