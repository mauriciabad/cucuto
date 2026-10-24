package app.mauri.cucuto.xposed

import android.content.SharedPreferences
import app.mauri.cucuto.core.routing.Clip
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.Route
import app.mauri.cucuto.core.routing.RoutingCodec
import app.mauri.cucuto.core.routing.RoutingContract
import app.mauri.cucuto.core.routing.RoutingFile
import app.mauri.cucuto.core.routing.RoutingTable
import app.mauri.cucuto.core.routing.Voice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingTableHolderTest {
    private val logs = mutableListOf<String>()
    private val prefs = FakePreferences()
    private var now = 1L
    private var failAttach = false
    private var file: ByteArray? = null
    private var failFile = false
    private val reloads = mutableListOf<Long>()
    private val holder =
        RoutingTableHolder(
            preferences = {
                if (failAttach) error("no service")
                prefs
            },
            log = { message, _ -> logs += message },
            remoteFile = {
                if (failFile) error("binder died")
                file
            },
            onReload = { reloads += it.generation },
            nanoTime = { now },
            retryIntervalNanos = 100,
        )

    private fun table(generation: Long) =
        RoutingTable(
            generation = generation,
            enabled = true,
            voices = listOf(Voice("app.mauri.demo.critters", "frog", listOf(Clip("content://x/a.ogg")), Haptic.Auto)),
            routes = listOf(Route("com.whatsapp", "a@s.whatsapp.net", 0, 1, 0, 1)),
        )

    @Test
    fun startsEmptyAndFollowsPublications() {
        assertSame(RoutingTable.EMPTY, holder.current())
        assertTrue(prefs.listeners.isNotEmpty())

        prefs.put(RoutingCodec.encode(table(5)))
        assertEquals(table(5), holder.current())
        assertEquals(
            "frog",
            holder
                .current()
                .resolve("com.whatsapp", "a@s.whatsapp.net")
                ?.voice
                ?.characterId,
        )

        prefs.put(RoutingCodec.encode(table(6)))
        assertEquals(6L, holder.current().generation)
    }

    @Test
    fun keepsTheLastReadableTable() {
        prefs.put(RoutingCodec.encode(table(5)))
        assertEquals(5L, holder.current().generation)

        // An updated app wrote a newer format before system_server restarted.
        prefs.put(RoutingCodec.encode(table(6)).replaceFirst("\t1\t", "\t2\t"))
        assertEquals(5L, holder.current().generation)
        prefs.put("garbage")
        assertEquals(5L, holder.current().generation)
        assertTrue(logs.any { it.startsWith("Ignoring an unreadable routing table") })

        // Cleared preferences mean "nothing assigned".
        prefs.put(null)
        assertSame(RoutingTable.EMPTY, holder.current())
    }

    @Test
    fun retriesAFailedAttachWithoutHammering() {
        failAttach = true
        assertSame(RoutingTable.EMPTY, holder.current())
        assertSame(RoutingTable.EMPTY, holder.current())
        assertEquals(1, logs.count { it.startsWith("Remote Preferences unavailable") })

        failAttach = false
        prefs.put(RoutingCodec.encode(table(9)))
        assertSame(RoutingTable.EMPTY, holder.current()) // still inside the retry interval
        now += 200
        assertEquals(9L, holder.current().generation)
    }

    @Test
    fun theFirstReadWhenAttachingIsNotAReload() {
        prefs.put(RoutingCodec.encode(table(5)))
        assertTrue(holder.ensureAttached())
        assertEquals(5L, holder.peek().generation)
        assertTrue(reloads.isEmpty())

        prefs.put(RoutingCodec.encode(table(6)))
        assertEquals(listOf(6L), reloads)
    }

    @Test
    fun theRemoteFileTakesOverWhenPreferenceChangesStop() {
        prefs.put(RoutingCodec.encode(table(5)))
        holder.ensureAttached()

        // After an app update LSPosed stores new preferences but no longer notifies this process, and its cached
        // preferences object keeps the old values. Only the remote file has the new table.
        file = RoutingFile.frame(RoutingCodec.encode(table(6)))
        assertEquals(5L, holder.peek().generation)

        assertTrue(holder.refresh())
        assertEquals(6L, holder.peek().generation)
        assertEquals(listOf(6L), reloads)
        assertTrue(logs.contains("Routing table 6: 1 keys, 1 voices"))
    }

    @Test
    fun aGenerationDeliveredByBothSourcesLoadsOnce() {
        holder.ensureAttached()
        prefs.put(RoutingCodec.encode(table(7)))
        file = RoutingFile.frame(RoutingCodec.encode(table(7)))

        assertFalse(holder.refresh())
        assertEquals(listOf(7L), reloads)
        assertEquals(1, logs.count { it.startsWith("Routing table 7") })
    }

    @Test
    fun aBadOrMissingFileKeepsTheTable() {
        prefs.put(RoutingCodec.encode(table(5)))
        holder.ensureAttached()

        assertFalse(holder.refresh()) // never written
        val framed = RoutingFile.frame(RoutingCodec.encode(table(6)))
        file = framed.copyOf(framed.size - 4) // read while the app was writing it
        assertFalse(holder.refresh())
        assertTrue(logs.any { it.startsWith("Ignoring an incomplete routing file") })
        failFile = true
        assertFalse(holder.refresh())
        assertTrue(logs.any { it.startsWith("Reading the routing file failed") })

        assertEquals(5L, holder.peek().generation)
        assertTrue(reloads.isEmpty())
    }

    @Test
    fun aThrowingReloadListenerIsContained() {
        val failing =
            RoutingTableHolder(
                preferences = { prefs },
                log = { message, _ -> logs += message },
                remoteFile = { RoutingFile.frame(RoutingCodec.encode(table(8))) },
                onReload = { error("listener bug") },
            )
        assertTrue(failing.refresh())
        assertEquals(8L, failing.peek().generation)
        assertTrue(logs.contains("Routing table listener failed"))
    }

    /** Just enough of a read-only SharedPreferences, notifying like Remote Preferences do. */
    private class FakePreferences : SharedPreferences {
        val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()
        private var value: String? = null

        fun put(encoded: String?) {
            value = encoded
            listeners.forEach { it.onSharedPreferenceChanged(this, RoutingContract.KEY_TABLE) }
        }

        override fun getString(
            key: String?,
            defValue: String?,
        ): String? = if (key == RoutingContract.KEY_TABLE) value ?: defValue else defValue

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) {
            listeners += listener
        }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) {
            listeners -= listener
        }

        override fun getAll(): Map<String, *> = mapOf(RoutingContract.KEY_TABLE to value)

        override fun getStringSet(
            key: String?,
            defValues: Set<String>?,
        ): Set<String>? = defValues

        override fun getInt(
            key: String?,
            defValue: Int,
        ): Int = defValue

        override fun getLong(
            key: String?,
            defValue: Long,
        ): Long = defValue

        override fun getFloat(
            key: String?,
            defValue: Float,
        ): Float = defValue

        override fun getBoolean(
            key: String?,
            defValue: Boolean,
        ): Boolean = defValue

        override fun contains(key: String?): Boolean = key == RoutingContract.KEY_TABLE && value != null

        override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException("read-only")
    }
}
