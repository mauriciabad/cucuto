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
import app.mauri.cucuto.xposed.events.DeliveryRefused
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException

class RoutingTableHolderTest {
    private val logs = mutableListOf<String>()
    private val prefs = FakePreferences()
    private var now = 1L
    private var failAttach = false
    private var file: ByteArray? = null
    private var failFile = false
    private val reloads = mutableListOf<Long>()

    /** What the app's provider answers to a pull; counted in [pulls]. */
    private var provider: () -> ByteArray? = { null }
    private var pulls = 0
    private var fileReads = 0
    private val holder =
        RoutingTableHolder(
            preferences = {
                if (failAttach) error("no service")
                prefs
            },
            log = { message, _ -> logs += message },
            remoteFile = {
                fileReads++
                if (failFile) error("binder died")
                file
            },
            providerTable = {
                pulls++
                provider()
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
        assertTrue(logs.contains("Routing table 6 (via file): 1 keys, 1 voices"))
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

    private fun framed(generation: Long) = RoutingFile.frame(RoutingCodec.encode(table(generation)))

    @Test
    fun pullsFromTheProviderWhenTheServiceCarriedNothing() {
        // An updated app without LSPosed's service: preferences and file stay at 5, the app announces 6.
        prefs.put(RoutingCodec.encode(table(5)))
        holder.ensureAttached()
        file = framed(5)
        provider = { framed(6) }

        assertTrue(holder.refresh(target = 6))
        assertEquals(6L, holder.peek().generation)
        assertEquals(1, pulls)
        assertEquals(listOf(6L), reloads)
        assertTrue(logs.contains("Routing table 6 (via provider): 1 keys, 1 voices"))
    }

    @Test
    fun theServiceIsPreferredAndNothingIsPulledTwice() {
        holder.ensureAttached()
        provider = { framed(9) }

        // Remote Preferences delivered it before the nudge arrived: no file read, no pull.
        prefs.put(RoutingCodec.encode(table(6)))
        assertFalse(holder.refresh(target = 6))
        assertEquals(0, fileReads)
        assertEquals(0, pulls)

        // The file has the announced generation: no pull either.
        file = framed(7)
        assertTrue(holder.refresh(target = 7))
        assertEquals(0, pulls)
        assertEquals(1, logs.count { it.startsWith("Routing table 7 (via file)") })
    }

    @Test
    fun olderGenerationsNeverReplaceANewerTable() {
        holder.ensureAttached()
        provider = { framed(8) }
        assertTrue(holder.refresh(target = 8))

        // Late copies through the other sources are older: ignored, whatever the order.
        prefs.put(RoutingCodec.encode(table(7)))
        file = framed(6)
        assertFalse(holder.refresh())
        provider = { framed(7) }
        assertFalse(holder.refresh(target = 9))
        assertEquals(8L, holder.peek().generation)
        assertEquals(listOf(8L), reloads)
    }

    @Test
    fun refusedOrFailedPullsKeepTheTable() {
        prefs.put(RoutingCodec.encode(table(5)))
        holder.ensureAttached()

        provider = { throw DeliveryRefused("the event provider's app is signed by another key") }
        assertFalse(holder.refresh(target = 6))
        assertTrue(
            logs.contains("Pulling the routing table refused: the event provider's app is signed by another key"),
        )

        provider = { throw FileNotFoundException("nothing published") }
        assertFalse(holder.refresh(target = 6))
        assertTrue(logs.contains("The app has no routing table to pull yet"))

        provider = { framed(6).let { it.copyOf(it.size - 3) } }
        assertFalse(holder.refresh(target = 6))
        assertTrue(logs.any { it.startsWith("Ignoring an incomplete routing table from the app") })

        provider = { error("binder died") }
        assertFalse(holder.refresh(target = 6))
        assertTrue(logs.any { it.startsWith("Pulling the routing table from the app failed") })

        assertEquals(5L, holder.peek().generation)
        assertTrue(reloads.isEmpty())
    }

    @Test
    fun atBootItPullsOnlyWhenTheFileIsMissing() {
        prefs.put(RoutingCodec.encode(table(5)))
        holder.ensureAttached()
        provider = { framed(6) }

        file = framed(5)
        assertTrue(holder.checkAtBoot())
        assertEquals(0, pulls)

        file = null
        // Before the user unlocks, the provider does not resolve: retry later, quietly, without waking the app.
        provider = { throw DeliveryRefused("the event provider is not installed for user 0", unavailable = true) }
        val before = logs.size
        assertFalse(holder.checkAtBoot())
        assertEquals(before, logs.size)

        provider = { framed(6) }
        assertTrue(holder.checkAtBoot())
        assertEquals(6L, holder.peek().generation)
    }

    @Test
    fun aBurstOfNudgesPullsOnce() {
        prefs.put(RoutingCodec.encode(table(5)))
        holder.ensureAttached()
        file = framed(5)
        provider = { framed(10) }
        val posted = ArrayDeque<() -> Unit>()
        val target = NudgeTarget()
        val coalescer = Coalescer(post = { posted.addLast(it) }, task = { holder.refresh(target.get()) })

        for (generation in 6L..10L) {
            target.offer(generation)
            coalescer.request()
        }
        assertEquals(1, posted.size)
        posted.removeFirst()()
        assertEquals(1, pulls)
        assertEquals(10L, holder.peek().generation)

        // A late nudge for a generation already held does nothing.
        target.offer(9)
        coalescer.request()
        posted.removeFirst()()
        assertEquals(1, pulls)
    }

    @Test
    fun thePullDecision() {
        // With an announced generation: only while the table held is older, whatever the file said.
        assertTrue(shouldPull(target = 6, held = 5, file = FileRead.FOUND))
        assertTrue(shouldPull(target = 6, held = 5, file = FileRead.MISSING))
        assertFalse(shouldPull(target = 6, held = 6, file = FileRead.UNREADABLE))
        assertFalse(shouldPull(target = 6, held = 7, file = FileRead.MISSING))
        // An app from before ADR 0048 announces nothing: only when the file gave nothing.
        assertFalse(shouldPull(target = 0, held = 5, file = FileRead.FOUND))
        assertTrue(shouldPull(target = 0, held = 5, file = FileRead.MISSING))
        assertTrue(shouldPull(target = 0, held = 5, file = FileRead.UNREADABLE))
    }

    @Test
    fun generationOrdering() {
        assertTrue(isNewer(1, RoutingTable.EMPTY))
        assertTrue(isNewer(6, table(5)))
        assertFalse(isNewer(5, table(5)))
        assertFalse(isNewer(4, table(5)))
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
