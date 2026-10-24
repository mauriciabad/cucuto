package app.mauri.cucuto.xposed

import android.content.SharedPreferences
import app.mauri.cucuto.core.routing.RoutingCodec
import app.mauri.cucuto.core.routing.RoutingContract
import app.mauri.cucuto.core.routing.RoutingFile
import app.mauri.cucuto.core.routing.RoutingTable
import app.mauri.cucuto.xposed.events.DeliveryRefused
import java.io.FileNotFoundException

/**
 * The module's in-memory copy of the routing table, kept in sync with the app. The notification hook calls [peek],
 * which is a volatile read; the table is decoded only when the app publishes a new one, off the notification path.
 *
 * Three sources carry the same table, in this order of preference:
 * - **Remote Preferences** (`XposedInterface.getRemotePreferences`): read when attaching, then followed through the
 *   change listener, on the framework's callback thread. LSPosed stops delivering those changes to this process
 *   once the app has been updated since boot, and the cached preferences object cannot be re-requested (ADR 0024).
 * - **The remote file** [RoutingContract.REMOTE_FILE], re-read by [refresh] when the app's nudge broadcast arrives.
 *   Remote files are addressed by package name, so they stay readable after an app update (ADR 0024).
 * - **The app's provider** (`content://app.mauri.cucuto.events/routing`), pulled by [refresh] only when the nudge
 *   announced a generation that neither of the above delivered. Both need the app to hold LSPosed's service, which
 *   LSPosed sometimes never hands to an updated app until a reboot (ADR 0043); the provider does not (ADR 0048).
 *
 * A table is installed only when its generation is newer than the one held, so whichever source delivers a
 * generation first installs it, the others then do nothing, and each publication logs one
 * `Routing table <gen> (via <source>)` line.
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
 * @param providerTable Reads the table the app's provider serves (a Binder call that may start the app's process,
 *   and file IO), framed by [RoutingFile]. Null when there is no provider. May throw: [DeliveryRefused] when the
 *   provider is not Cucuto's or cannot be reached, `FileNotFoundException` when the app has published nothing.
 * @param onReload Called after a publication replaced the table (not for the first read when attaching), on the
 *   thread that loaded it.
 */
class RoutingTableHolder(
    private val preferences: () -> SharedPreferences,
    private val log: (String, Throwable?) -> Unit,
    private val remoteFile: () -> ByteArray? = { null },
    private val providerTable: () -> ByteArray? = { null },
    private val onReload: (RoutingTable) -> Unit = {},
    private val nanoTime: () -> Long = System::nanoTime,
    private val retryIntervalNanos: Long = 10_000_000_000,
) : SharedPreferences.OnSharedPreferenceChangeListener {
    /** Where a table came from, for the `Routing table <gen> (via <label>)` line. */
    enum class Source(
        val label: String,
    ) {
        PREFERENCES("preferences"),
        FILE("file"),
        PROVIDER("provider"),
    }

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
            load(read = { preferences.getString(RoutingContract.KEY_TABLE, null) }, Source.PREFERENCES, notify = true)
        }
    }

    /**
     * Catches up with the app's nudge for [target] (0 when the nudge did not say: apps before ADR 0048): nothing when
     * the table held is already that new, else the remote file, else a pull from the app's provider ([shouldPull]).
     * Background thread only (Binder calls and file IO). Returns true when it installed a new table. Never throws.
     */
    fun refresh(target: Long = 0): Boolean {
        if (target > 0 && table.generation >= target) return false
        val (file, installed) = readFile()
        if (!shouldPull(target, table.generation, file)) return installed
        return pull(quietIfUnavailable = false) == Pull.INSTALLED || installed
    }

    /**
     * The boot check, after attaching: reads the remote file and pulls from the app's provider only when the file
     * does not exist. Returns false when the pull should be retried later (the provider cannot be reached yet, for
     * example before the user unlocks); true when done. Background thread only. Never throws.
     */
    fun checkAtBoot(): Boolean {
        val (file, _) = readFile()
        if (file != FileRead.MISSING) return true
        return pull(quietIfUnavailable = true) != Pull.UNAVAILABLE
    }

    private fun readFile(): Pair<FileRead, Boolean> {
        val bytes =
            try {
                remoteFile() ?: return FileRead.MISSING to false
            } catch (t: Throwable) {
                log("Reading the routing file failed; keeping the previous table", t)
                return FileRead.UNREADABLE to false
            }
        val encoded = RoutingFile.unframe(bytes)
        if (encoded == null) {
            log("Ignoring an incomplete routing file (${bytes.size} bytes); keeping the previous table", null)
            return FileRead.UNREADABLE to false
        }
        return FileRead.FOUND to load(read = { encoded }, Source.FILE, notify = true)
    }

    private fun pull(quietIfUnavailable: Boolean): Pull {
        val bytes =
            try {
                providerTable() ?: return Pull.FAILED
            } catch (e: DeliveryRefused) {
                if (!(e.unavailable && quietIfUnavailable)) log("Pulling the routing table refused: ${e.message}", null)
                return if (e.unavailable) Pull.UNAVAILABLE else Pull.FAILED
            } catch (_: FileNotFoundException) {
                log("The app has no routing table to pull yet", null)
                return Pull.FAILED
            } catch (t: Throwable) {
                log("Pulling the routing table from the app failed; keeping the previous table", t)
                return Pull.FAILED
            }
        val encoded = RoutingFile.unframe(bytes)
        if (encoded == null) {
            log("Ignoring an incomplete routing table from the app (${bytes.size} bytes)", null)
            return Pull.FAILED
        }
        return if (load(read = { encoded }, Source.PROVIDER, notify = true)) Pull.INSTALLED else Pull.NOTHING_NEW
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
                load(read = { prefs.getString(RoutingContract.KEY_TABLE, null) }, Source.PREFERENCES, notify = false)
            } catch (t: Throwable) {
                log("Remote Preferences unavailable; keeping WhatsApp's tones", t)
            }
        }
    }

    /** Decodes what [read] returns and installs it when its generation is newer. Returns true when installed. */
    private fun load(
        read: () -> String?,
        source: Source,
        notify: Boolean,
    ): Boolean {
        val installed =
            try {
                synchronized(installLock) { install(read(), source) }
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

    /** Installs the table in [encoded] (null means "nothing published"). Returns it, or null when not newer. */
    private fun install(
        encoded: String?,
        source: Source,
    ): RoutingTable? {
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
        if (!isNewer(decoded.generation, table)) return null
        table = decoded
        log(
            "Routing table ${decoded.generation} (via ${source.label}): " +
                "${decoded.routes.size} keys, ${decoded.voices.size} voices",
            null,
        )
        return decoded
    }

    private enum class Pull { INSTALLED, NOTHING_NEW, FAILED, UNAVAILABLE }
}

/** What the remote file gave the module when it looked. */
internal enum class FileRead {
    /** A complete table (installed or not, depending on its generation). */
    FOUND,

    /** The app never wrote it (it never held LSPosed's service with this feature). */
    MISSING,

    /** Torn, corrupt, too large or a failed read. */
    UNREADABLE,
}

/**
 * Whether the module pulls the table from the app's provider after a nudge, once it read the remote file (pure,
 * unit-tested). With a [target] generation, only while the table [held] is older than it, whatever the file said: a
 * file that is present but stale means the app could not write it. Without one (an app from before ADR 0048, which
 * nudges only after writing the file), only when the file gave nothing.
 */
internal fun shouldPull(
    target: Long,
    held: Long,
    file: FileRead,
): Boolean = if (target > 0) held < target else file != FileRead.FOUND

/**
 * Whether a table of [generation] replaces [held] (pure). Generations only grow (the publication time, and always
 * above the previous one), so an older or equal one is a copy that arrived late through another source.
 */
internal fun isNewer(
    generation: Long,
    held: RoutingTable,
): Boolean = held === RoutingTable.EMPTY || generation > held.generation
