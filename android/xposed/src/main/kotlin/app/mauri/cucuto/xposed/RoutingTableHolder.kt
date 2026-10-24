package app.mauri.cucuto.xposed

import android.content.SharedPreferences
import app.mauri.cucuto.core.routing.RoutingCodec
import app.mauri.cucuto.core.routing.RoutingContract
import app.mauri.cucuto.core.routing.RoutingFile
import app.mauri.cucuto.core.routing.RoutingTable

/**
 * The module's in-memory copy of the routing table, kept in sync with the app through libxposed. The notification
 * hook calls [peek], which is a volatile read; the table is decoded only when the app publishes a new one, off the
 * notification path.
 *
 * Two sources carry the same table (ADR 0024):
 * - **Remote Preferences** (`XposedInterface.getRemotePreferences`): read when attaching, then followed through the
 *   change listener, on the framework's callback thread. LSPosed stops delivering those changes to this process
 *   once the app has been updated since boot, and the cached preferences object cannot be re-requested.
 * - **The remote file** [RoutingContract.REMOTE_FILE], re-read by [refresh] when the app's nudge broadcast arrives.
 *   Remote files are addressed by package name, so they stay readable after an app update.
 *
 * Whichever source delivers a generation first installs it; the other one then sees the same generation and does
 * nothing, so each publication logs one `Routing table <gen>` line.
 *
 * Failure handling, because this runs inside `system_server`:
 * - Nothing here throws. Every failure is reported to [log] and leaves the previous table in place.
 * - A failed attach is retried at most every [retryIntervalNanos].
 * - A table the module cannot read (malformed, a torn file read, or a newer format written by an updated app before
 *   a reboot) is ignored: the module keeps the last table it understood.
 * - Before the app has published anything the table is [RoutingTable.EMPTY]: every contact keeps WhatsApp's tone.
 *
 * @param remoteFile Reads [RoutingContract.REMOTE_FILE] (a Binder call and file IO), or returns null when it does not
 *   exist. May throw; [refresh] catches.
 * @param onReload Called after a publication replaced the table (not for the first read when attaching), on the
 *   thread that loaded it.
 */
class RoutingTableHolder(
    private val preferences: () -> SharedPreferences,
    private val log: (String, Throwable?) -> Unit,
    private val remoteFile: () -> ByteArray? = { null },
    private val onReload: (RoutingTable) -> Unit = {},
    private val nanoTime: () -> Long = System::nanoTime,
    private val retryIntervalNanos: Long = 10_000_000_000,
) : SharedPreferences.OnSharedPreferenceChangeListener {
    @Volatile private var table: RoutingTable = RoutingTable.EMPTY

    // The framework keeps the listener registered for as long as the preferences object lives; this field keeps
    // both reachable (some libxposed implementations hold listeners weakly).
    @Volatile private var attached: SharedPreferences? = null

    @Volatile private var lastAttempt: Long = 0
    private val lock = Any()

    // Serialises installs from the framework's callback thread and the nudge's background thread.
    private val installLock = Any()

    /** The latest table. Attaches on first use, which is a Binder call: prefer [peek] on the notification path. */
    fun current(): RoutingTable {
        if (attached == null) attach()
        return table
    }

    /** The table held right now, without attaching: a volatile read, for the notification path. */
    fun peek(): RoutingTable = table

    /** Whether the change listener is registered (the table follows the app's publications). */
    val isAttached: Boolean get() = attached != null

    /** Attaches if needed (at most once per retry interval). Call from a background thread. */
    fun ensureAttached(): Boolean {
        if (attached == null) attach()
        return attached != null
    }

    override fun onSharedPreferenceChanged(
        preferences: SharedPreferences?,
        key: String?,
    ) {
        if (preferences != null && (key == null || key == RoutingContract.KEY_TABLE)) {
            load(read = { preferences.getString(RoutingContract.KEY_TABLE, null) }, notify = true)
        }
    }

    /**
     * Re-reads the table from the remote file, after the app's nudge. Background thread only (Binder call and file
     * IO). Returns true when it installed a new table. Never throws.
     */
    fun refresh(): Boolean {
        val bytes =
            try {
                remoteFile() ?: return false
            } catch (t: Throwable) {
                log("Reading the routing file failed; keeping the previous table", t)
                return false
            }
        val encoded = RoutingFile.unframe(bytes)
        if (encoded == null) {
            log("Ignoring an incomplete routing file (${bytes.size} bytes); keeping the previous table", null)
            return false
        }
        return load(read = { encoded }, notify = true)
    }

    private fun attach() {
        synchronized(lock) {
            if (attached != null) return
            val now = nanoTime()
            if (lastAttempt != 0L && now - lastAttempt < retryIntervalNanos) return
            lastAttempt = now
            try {
                val prefs = preferences()
                prefs.registerOnSharedPreferenceChangeListener(this)
                attached = prefs
                load(read = { prefs.getString(RoutingContract.KEY_TABLE, null) }, notify = false)
            } catch (t: Throwable) {
                log("Remote Preferences unavailable; keeping WhatsApp's tones", t)
            }
        }
    }

    /** Decodes what [read] returns and installs it when its generation differs. Returns true when installed. */
    private fun load(
        read: () -> String?,
        notify: Boolean,
    ): Boolean {
        val installed =
            try {
                synchronized(installLock) { install(read()) }
            } catch (t: Throwable) {
                log("Reading the routing table failed; keeping the previous one", t)
                null
            }
        if (installed != null && notify) {
            try {
                onReload(installed)
            } catch (t: Throwable) {
                log("Routing table listener failed", t)
            }
        }
        return installed != null
    }

    /** Installs the table in [encoded] (null means "nothing published"). Returns it, or null when unchanged. */
    private fun install(encoded: String?): RoutingTable? {
        if (encoded == null) {
            if (table === RoutingTable.EMPTY) return null
            table = RoutingTable.EMPTY
            return RoutingTable.EMPTY
        }
        val decoded = RoutingCodec.decodeOrNull(encoded)
        if (decoded == null) {
            log("Ignoring an unreadable routing table (${encoded.length} chars); keeping the previous one", null)
            return null
        }
        if (decoded.generation == table.generation && table !== RoutingTable.EMPTY) return null
        table = decoded
        log("Routing table ${decoded.generation}: ${decoded.routes.size} keys, ${decoded.voices.size} voices", null)
        return decoded
    }
}
