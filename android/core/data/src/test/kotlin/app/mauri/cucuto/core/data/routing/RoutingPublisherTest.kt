package app.mauri.cucuto.core.data.routing

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import app.mauri.cucuto.core.data.FakeClock
import app.mauri.cucuto.core.data.db.CucutoDatabase
import app.mauri.cucuto.core.data.db.GroupPolicy
import app.mauri.cucuto.core.data.inMemoryDatabase
import app.mauri.cucuto.core.data.repository.AssignmentRepository
import app.mauri.cucuto.core.data.repository.ContactRepository
import app.mauri.cucuto.core.data.repository.InstalledPackRepository
import app.mauri.cucuto.core.data.settings.RoutingSettings
import app.mauri.cucuto.core.routing.RoutingCodec
import app.mauri.cucuto.core.routing.RoutingTable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** End to end inside the app: database changes → routing table → encoded write to (fake) Remote Preferences. */
@RunWith(AndroidJUnit4::class)
class RoutingPublisherTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var db: CucutoDatabase
    private lateinit var contacts: ContactRepository
    private lateinit var assignments: AssignmentRepository
    private lateinit var packs: InstalledPackRepository
    private val clock = FakeClock()
    private val sink = FakeSink()
    private val mirror = FakeMirror()

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        contacts = ContactRepository(db, db.contactDao(), clock)
        assignments = AssignmentRepository(db, db.assignmentDao(), clock)
        packs = InstalledPackRepository(db.installedPackDao())
    }

    @After
    fun tearDown() = db.close()

    private fun TestScope.publisher(scope: CoroutineScope = backgroundScope): Pair<RoutingPublisher, RoutingSettings> {
        val settings =
            RoutingSettings(
                PreferenceDataStoreFactory.create(
                    scope = scope,
                ) { folder.newFile("settings.preferences_pb").also { it.delete() } },
            )
        val source =
            RoutingTableSource(
                db.contactDao(),
                db.installedPackDao(),
                settings,
                RoutingTableBuilder(FakeCatalog(), mirror),
            )
        return RoutingPublisher(source, sink, mirror, clock, scope) to settings
    }

    @Test
    fun republishesOnEveryRelevantChange() =
        runTest {
            val (publisher, settings) = publisher()
            sink.connect()
            publisher.start()
            assertTrue(sink.started)

            sink.tables().test {
                // Empty database: an empty, enabled table.
                assertEquals(0, awaitItem().routes.size)

                packs.recordInstalled(installedPack())
                val ada = contacts.findOrCreate("com.whatsapp", "34600000001@s.whatsapp.net", "Ada", isGroup = false)
                assignments.assign(ada, CRITTERS, "frog")
                val assigned = awaitItem()
                assertEquals(listOf("34600000001@s.whatsapp.net"), assigned.routes.map { it.key })
                assertEquals("frog", assigned.resolve("com.whatsapp", "34600000001@s.whatsapp.net")?.voice?.characterId)

                // A new key for the same contact routes too.
                contacts.addKey(ada, "com.whatsapp", "123456789012345@lid")
                assertEquals(2, awaitItem().routes.size)

                // Toggling a clip changes the voice.
                assignments.setClipEnabled(ada, "characters/frog/clips/croak.ogg", enabled = false)
                assertEquals(
                    1,
                    awaitItem()
                        .voices
                        .single()
                        .clips.size,
                )

                // A per-sender group gets a route without a voice.
                val group = contacts.findOrCreate("com.whatsapp", "120363000000000001@g.us", "Family", isGroup = true)
                contacts.setGroupPolicy(group, GroupPolicy.PER_SENDER)
                val withGroup = awaitItem()
                assertTrue(withGroup.route("com.whatsapp", "120363000000000001@g.us")!!.isPerSender)

                // The global switch.
                settings.setEnabled(false)
                assertFalse(awaitItem().enabled)

                // Uninstalling the pack drops the voice but keeps the per-sender group.
                packs.remove(CRITTERS)
                val uninstalled = awaitItem()
                assertEquals(listOf("120363000000000001@g.us"), uninstalled.routes.map { it.key })
                cancelAndIgnoreRemainingEvents()
            }

            // Generations only grow.
            val generations = sink.writes.value.map { RoutingCodec.decode(it).generation }
            assertEquals(generations.sorted(), generations)
            assertEquals(generations.distinct(), generations)
            val state = publisher.state.value as PublishState.Published
            assertEquals(generations.last(), state.generation)
            // The mirror only keeps what the module can still play.
            assertEquals(RoutingCodec.decode(sink.writes.value.last()), mirror.retained.last())
        }

    @Test
    fun waitsForTheFrameworkAndSkipsIdenticalTables() =
        runTest {
            val (publisher, _) = publisher()
            packs.recordInstalled(installedPack())
            val ada = contacts.findOrCreate("com.whatsapp", "34600000001@s.whatsapp.net", "Ada", isGroup = false)
            assignments.assign(ada, CRITTERS, "frog")

            publisher.start()
            publisher.start() // idempotent
            testScheduler.advanceUntilIdle()
            assertEquals(PublishState.Idle, publisher.state.value)
            assertTrue(sink.writes.value.isEmpty())

            sink.connect()
            val first = publisher.state.filterIsInstance<PublishState.Published>().first()
            assertEquals(1, first.routes)
            assertEquals(1, sink.writes.value.size)

            // Reconnecting (for example after LSPosed restarts) with the same content does not rewrite it.
            sink.disconnect()
            clock.advance()
            sink.connect()
            testScheduler.advanceUntilIdle()
            assertEquals(1, sink.writes.value.size)
        }

    @Test
    fun reportsUnsupportedFrameworksAndWriteFailures() =
        runTest {
            val (publisher, _) = publisher()
            publisher.start()
            sink.connection.value = SinkConnection.Unsupported("Embedded 1.0")
            assertEquals(
                PublishState.Unsupported("Embedded 1.0"),
                publisher.state.first { it is PublishState.Unsupported },
            )

            sink.failWrites = true
            sink.connect()
            val failed = publisher.state.filterIsInstance<PublishState.Failed>().first()
            assertEquals("boom", failed.message)
            assertNull(sink.read())
        }

    /** Remote Preferences stand-in. */
    private class FakeSink : RoutingSink {
        val writes = MutableStateFlow<List<String>>(emptyList())
        var started = false
        var failWrites = false
        override val connection = MutableStateFlow<SinkConnection>(SinkConnection.Disconnected)

        fun connect() {
            connection.value = SinkConnection.Connected("LSPosed", 102)
        }

        fun disconnect() {
            connection.value = SinkConnection.Disconnected
        }

        fun tables(): StateFlowTables = StateFlowTables(writes)

        override fun start() {
            started = true
        }

        override suspend fun read(): String? = writes.value.lastOrNull()

        override suspend fun write(
            encoded: String,
            generation: Long,
        ) {
            if (failWrites) error("boom")
            assertEquals(generation, RoutingCodec.decode(encoded).generation)
            writes.value += encoded
        }
    }

    /** The decoded table of every write, as a flow that starts at the first write. */
    private class StateFlowTables(
        private val writes: StateFlow<List<String>>,
    ) : kotlinx.coroutines.flow.Flow<RoutingTable> {
        override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<RoutingTable>) {
            var seen = 0
            writes.collect { all ->
                while (seen < all.size) collector.emit(RoutingCodec.decode(all[seen++]))
            }
        }
    }
}
