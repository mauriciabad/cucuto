package app.mauri.cucuto.xposed

import android.util.Log
import app.mauri.cucuto.core.routing.ContactKeys

/**
 * The module's log. Lines go to libxposed's `log()`, which LSPosed shows in its modules log (not logcat: M0 found
 * hook output never reaches logcat). Nothing here ever throws.
 *
 * Privacy: callers pass identifiers through [keyShape], never raw. A WhatsApp key is reduced to its JID domain
 * (`@lid`, `@g.us`, `@s.whatsapp.net`) or `<title>`, which is all a device test needs.
 */
internal class ModuleLog(
    private val sink: (priority: Int, message: String, error: Throwable?) -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val lastWarned = HashMap<String, Long>()

    fun info(message: String) = write(Log.INFO, message, null)

    fun warn(
        message: String,
        error: Throwable? = null,
    ) = write(Log.WARN, message, error)

    /** Like [warn], but at most once per [intervalMillis] for the same [topic], so a hot path cannot flood the log. */
    fun warnRateLimited(
        topic: String,
        message: String,
        error: Throwable? = null,
        intervalMillis: Long = 60_000,
    ) {
        val now = nowMillis()
        val allowed =
            synchronized(lastWarned) {
                val last = lastWarned[topic]
                if (last != null && now - last < intervalMillis) {
                    false
                } else {
                    lastWarned[topic] = now
                    true
                }
            }
        if (allowed) warn(message, error)
    }

    private fun write(
        priority: Int,
        message: String,
        error: Throwable?,
    ) {
        try {
            sink(priority, message, error)
        } catch (_: Throwable) {
            // Logging must never take system_server down with it.
        }
    }

    companion object {
        const val TAG: String = "cucuto"

        /** A loggable shape of a conversation key: its JID domain, never the identifier itself. */
        fun keyShape(key: String?): String {
            if (key == null) return "<none>"
            if (key.startsWith(ContactKeys.PREFIX)) return "<contact>"
            val at = key.lastIndexOf('@')
            return if (at in 1 until key.length - 1 && key.indexOf(' ') < 0) key.substring(at) else "<title>"
        }

        /**
         * A loggable shape of a group message's sender, for the device check of the per-sender policy (#20): the
         * JID domain of its `Person` key, `<other>` for a key that is not a JID, `<name only>` when the message
         * named the sender without a key, `<none>` when the record carried no sender. Never the key or the name.
         */
        fun senderShape(
            key: String?,
            hasName: Boolean,
        ): String {
            if (key == null) return if (hasName) "<name only>" else "<none>"
            val shape = keyShape(key)
            return if (shape == "<title>") "<other>" else shape
        }
    }
}
