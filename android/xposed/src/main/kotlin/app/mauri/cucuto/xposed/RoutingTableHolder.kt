package app.mauri.cucuto.xposed

import android.content.SharedPreferences
import app.mauri.cucuto.core.routing.RoutingCodec
import app.mauri.cucuto.core.routing.RoutingContract
import app.mauri.cucuto.core.routing.RoutingTable

/**
 * The module's in-memory copy of the routing table, kept in sync with the app through libxposed Remote Preferences
 * (`XposedInterface.getRemotePreferences`). The notification hook calls [current], which is a volatile read; the
 * table is decoded only when the app publishes a new one, on the framework's callback thread.
 *
 * Failure handling, because this runs inside `system_server`:
 * - Nothing here throws. Every failure is reported to [log] and leaves the previous table in place.
 * - Preferences are attached lazily on the first [current] call, not during boot, and a failed attach is retried
 *   at most every [retryIntervalNanos].
 * - A table the module cannot read (malformed, or a newer format written by an updated app before a reboot)
 *   is ignored: the module keeps the last table it understood.
 * - Before the app has published anything the table is [RoutingTable.EMPTY]: every contact keeps WhatsApp's tone.
 */
class RoutingTableHolder(
    private val preferences: () -> SharedPreferences,
    private val log: (String, Throwable?) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
    private val retryIntervalNanos: Long = 10_000_000_000,
) : SharedPreferences.OnSharedPreferenceChangeListener {
    @Volatile private var table: RoutingTable = RoutingTable.EMPTY

    // Remote Preferences keep listeners in a weak map: this field is the strong reference that keeps us registered.
    @Volatile private var attached: SharedPreferences? = null

    @Volatile private var lastAttempt: Long = 0
    private val lock = Any()

    /** The latest table. Cheap enough for the notification path. */
    fun current(): RoutingTable {
        if (attached == null) attach()
        return table
    }

    override fun onSharedPreferenceChanged(
        preferences: SharedPreferences?,
        key: String?,
    ) {
        if (preferences != null && (key == null || key == RoutingContract.KEY_TABLE)) reload(preferences)
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
                reload(prefs)
            } catch (t: Throwable) {
                log("Remote Preferences unavailable; keeping WhatsApp's tones", t)
            }
        }
    }

    private fun reload(preferences: SharedPreferences) {
        try {
            val encoded = preferences.getString(RoutingContract.KEY_TABLE, null)
            if (encoded == null) {
                table = RoutingTable.EMPTY
                return
            }
            val decoded = RoutingCodec.decodeOrNull(encoded)
            if (decoded == null) {
                log("Ignoring an unreadable routing table (${encoded.length} chars); keeping the previous one", null)
            } else {
                table = decoded
                log(
                    "Routing table ${decoded.generation}: ${decoded.routes.size} keys, ${decoded.voices.size} voices",
                    null,
                )
            }
        } catch (t: Throwable) {
            log("Reading the routing table failed; keeping the previous one", t)
        }
    }
}
