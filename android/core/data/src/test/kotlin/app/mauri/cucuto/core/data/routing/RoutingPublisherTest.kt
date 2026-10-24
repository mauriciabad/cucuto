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
import app.mauri.cucuto.core.data.settings.DayNightSettings
import app.mauri.cucuto.core.data.settings.RoutingSettings
import app.mauri.cucuto.core.routing.ControlPoint
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.RoutingCodec
import app.mauri.cucuto.core.routing.RoutingContract
import app.mauri.cucuto.core.routing.RoutingTable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
import java.time.LocalDate
import java.time.ZoneId

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
    private val publishedTable = FakeStore()

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        contacts = ContactRepository(db, db.contactDao(), clock)
        assignments = AssignmentRepository(db, db.assignmentDao(), clock)
        packs = InstalledPackRepository(db.installedPackDao())
    }

    @After
    fun tearDown() = db.close()

    private val catalog = FakeCatalog()

    private fun TestScope.publisher(scope: CoroutineScope = backgroundScope): Pair<RoutingPublisher, RoutingSettings> {
        val store =
            PreferenceDataStoreFactory.create(
                scope = scope,
            ) { folder.newFile("settings.preferences_pb").also { it.delete() } }
        val settings = RoutingSettings(store)
        val source =
            RoutingTableSource(
                db.contactDao(),
                db.installedPackDao(),
                settings,
                RoutingTableBuilder(catalog, mirror),
                DayNightSettings(store),
                { flowOf(LocalDay(LocalDate.of(2026, 10, 24), ZoneId.of("Europe/Madrid"))) },
            )
        return RoutingPublisher(source, sink, publishedTable, mirror, clock, scope) to settings
    }

    /** An owl with [clips] clips, each with its own 16-point auto envelope (off the grid: the long syntax). */
    private fun owlWithEnvelopes(clips: Int) =
        mapOf(
            CRITTERS to
                mapOf(
                    "owl" to
                        CatalogCharacter(
                            clips =
                                (0 until clips).map { i ->
                                    val points =
                                        (1..16).map { p ->
                                            ControlPoint(
                                                if (p ==
                                                    16
                                                ) {
                                                    0
                                                } else {
                                                    501 + i % 400
                                                },
                                                333,
                                                21 + p,
                                            )
                                        }
                                    CatalogClip(
                                        "characters/owl/clips/hoot-$i.ogg",
                                        1000,
                                        Haptic.Custom(listOf(HapticEvent.Envelope(i, points))),
                                    )
                                },
                            haptic = Haptic.Auto,
                        ),
                ),
        )

    @Test
    fun republishesWhenTheCatalogRereadsAPack() =
        runTest {
            val (publisher, _) = publisher()
            packs.recordInstalled(installedPack())
            val ada = contacts.findOrCreate("com.whatsapp", "34600000001@s.whatsapp.net", "Ada", isGroup = false)
            assignments.assign(ada, CRITTERS, "owl")
            sink.connect()
            publisher.start()
            sink.tables().test {
                assertNull(
                    awaitItem()
                        .voices
                        .single()
                        .clips
                        .single()
                        .haptic,
                )
                // Auto haptics analysed in the background: same database rows, new catalog content.
                catalog.characters = owlWithEnvelopes(1)
                catalog.revisions.value++
                assertTrue(
                    awaitItem()
                        .voices
                        .single()
                        .clips
                        .single()
                        .haptic != null,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun putsATableTooLargeForRemotePreferencesInTheFileOnly() =
        runTest {
            // About 280 Ki characters: over the Binder limit of Remote Preferences, far under the remote file's.
            catalog.characters = owlWithEnvelopes(1500)
            val (publisher, _) = publisher()
            packs.recordInstalled(installedPack())
            val ada = contacts.findOrCreate("com.whatsapp", "34600000001@s.whatsapp.net", "Ada", isGroup = false)
            assignments.assign(ada, CRITTERS, "owl")
            sink.connect()
            publisher.start()

            val state = publisher.state.filterIsInstance<PublishState.Published>().first()
            assertFalse(state.clipHapticsLeftOut)
            assertTrue(state.tooLargeForPreferences)
            assertTrue(state.encodedChars > RoutingContract.MAX_PREFERENCES_CHARS)
            // The remote file, the app's copy and the nudge carry the full table...
            val full = RoutingCodec.decode(sink.writes.value.single())
            assertEquals(state.generation, full.generation)
            assertTrue(
                full.voices
                    .single()
                    .clips
                    .all { it.haptic != null },
            )
            assertEquals(sink.writes.value.single(), publishedTable.encoded)
            assertEquals(listOf(state.generation), sink.nudges)
            // ...and Remote Preferences the same table without clip envelopes, one generation older, so that every
            // module (which installs only newer generations) replaces it with the full one.
            val preferences = sink.preferencesTable!!
            assertTrue(preferences.length <= RoutingContract.MAX_PREFERENCES_CHARS)
            val reduced = RoutingCodec.decode(preferences)
            assertEquals(state.generation - 1, reduced.generation)
            assertEquals(
                full.voices
                    .single()
                    .clips
                    .map { it.uri },
                reduced.voices
                    .single()
                    .clips
                    .map { it.uri },
            )
            assertTrue(
                reduced.voices
                    .single()
                    .clips
                    .all { it.haptic == null },
            )
            assertEquals(state.generation, sink.preferencesGeneration)
            assertEquals(full, mirror.retained.last())

            // Nothing changed: the table is not written again, although Remote Preferences hold the reduced copy.
            sink.disconnect()
            clock.advance()
            sink.connect()
            testScheduler.advanceUntilIdle()
            assertEquals(1, sink.writes.value.size)
            assertEquals(1, sink.nudges.size)
        }

    @Test
    fun leavesClipHapticsOutRatherThanNotPublishing() =
        runTest {
            // Over the remote file's limit with the envelopes, about 150 Ki characters without them.
            catalog.characters = owlWithEnvelopes(6000)
            val (publisher, _) = publisher()
            packs.recordInstalled(installedPack())
            val ada = contacts.findOrCreate("com.whatsapp", "34600000001@s.whatsapp.net", "Ada", isGroup = false)
            assignments.assign(ada, CRITTERS, "owl")
            sink.connect()
            publisher.start()

            val state = publisher.state.filterIsInstance<PublishState.Published>().first()
            assertTrue(state.clipHapticsLeftOut)
            assertFalse(state.tooLargeForPreferences)
            assertTrue(state.encodedChars <= RoutingContract.MAX_PREFERENCES_CHARS)
            val table = RoutingCodec.decode(sink.writes.value.single())
            assertEquals(
                6000,
                table.voices
                    .single()
                    .clips.size,
            )
            assertTrue(
                table.voices
                    .single()
                    .clips
                    .all { it.haptic == null },
            )
            assertEquals(sink.writes.value.single(), sink.preferencesTable)

            // Nothing changed: the same reduced table is not written again.
            sink.disconnect()
            clock.advance()
            sink.connect()
            testScheduler.advanceUntilIdle()
            assertEquals(1, sink.writes.value.size)
        }

    @Test
    fun keepsRemotePreferencesWhenEvenTheReducedCopyIsTooLarge() =
        runTest {
            val (publisher, _) = publisher()
            packs.recordInstalled(installedPack())
            val ada = contacts.findOrCreate("com.whatsapp", "34600000001@s.whatsapp.net", "Ada", isGroup = false)
            assignments.assign(ada, CRITTERS, "frog")
            sink.connect()
            publisher.start()
            val small = published(publisher) { it.routes == 1 }
            val before = sink.preferencesTable

            // 15,000 clips without envelopes: over 256 Ki characters with nothing left to drop.
            catalog.characters =
                mapOf(
                    CRITTERS to
                        mapOf(
                            "frog" to
                                CatalogCharacter(
                                    clips =
                                        (0 until 15_000).map {
                                            CatalogClip(
                                                "characters/frog/clips/r-$it.ogg",
                                                1000,
                                            )
                                        },
                                    haptic = Haptic.Keep,
                                ),
                        ),
                )
            catalog.revisions.value++
            val large = published(publisher) { it.generation > small.generation }
            assertTrue(large.tooLargeForPreferences)
            assertFalse(large.clipHapticsLeftOut)
            assertEquals(
                15_000,
                RoutingCodec
                    .decode(sink.writes.value.last())
                    .voices
                    .single()
                    .clips.size,
            )
            // Remote Preferences keep the older table rather than none: the module still installs the file's.
            assertEquals(before, sink.preferencesTable)
            assertEquals(large.generation, sink.preferencesGeneration)
            assertEquals(large.generation, sink.nudges.last())
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
            // No service: only the app's copy, for the module's provider pull, and a nudge (ADR 0048).
            val local = published(publisher) { it.throughProvider }
            assertTrue(local.throughProvider)
            assertTrue(sink.writes.value.isEmpty())
            assertEquals(listOf(local.generation), sink.nudges)
            assertEquals(local.generation, RoutingCodec.decode(publishedTable.encoded!!).generation)
            assertTrue(mirror.retained.isEmpty()) // the module may still run an older table

            clock.advance()
            sink.connect()
            val first = published(publisher) { !it.throughProvider }
            assertFalse(first.throughProvider)
            assertEquals(1, first.routes)
            assertEquals(1, sink.writes.value.size)
            // Through the service with a newer generation, and the app's copy follows.
            assertTrue(first.generation > local.generation)
            assertEquals(listOf(local.generation, first.generation), sink.nudges)
            assertEquals(sink.writes.value.single(), publishedTable.encoded)

            // Reconnecting (for example after LSPosed restarts) with the same content does not rewrite it.
            sink.disconnect()
            clock.advance()
            sink.connect()
            testScheduler.advanceUntilIdle()
            assertEquals(1, sink.writes.value.size)
            assertEquals(2, sink.nudges.size)
        }

    @Test
    fun publishesThroughTheProviderWhenTheServiceIsLost() =
        runTest {
            val (publisher, settings) = publisher()
            sink.connect()
            publisher.start()
            val connected = published(publisher) { !it.throughProvider }

            // LSPosed dropped the service after an app update (ADR 0043): a change still reaches the app's copy.
            sink.disconnect()
            clock.advance()
            settings.setEnabled(false)
            val local = published(publisher) { it.throughProvider && it.generation > connected.generation }
            assertTrue(local.throughProvider)
            assertTrue(local.generation > connected.generation)
            assertEquals(1, sink.writes.value.size)
            assertEquals(local.generation, sink.nudges.last())
            assertFalse(RoutingCodec.decode(publishedTable.encoded!!).enabled)

            // The service comes back: the change goes to Remote Preferences with a newer generation still.
            clock.advance()
            sink.connect()
            published(publisher) { !it.throughProvider && it.generation > local.generation }
            val back = RoutingCodec.decode(sink.writes.value.last())
            assertFalse(back.enabled)
            assertTrue(back.generation > local.generation)
        }

    @Test
    fun nudgesEvenWhenTheServiceWriteFails() =
        runTest {
            val (publisher, _) = publisher()
            sink.failWrites = true
            sink.connect()
            publisher.start()
            publisher.state.first { it is PublishState.Failed }
            val generation = RoutingCodec.decode(publishedTable.encoded!!).generation
            assertEquals(listOf(generation), sink.nudges)
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

    /** Stand-in for Remote Preferences ([preferencesTable], [preferencesGeneration]) and the remote file ([writes]). */
    private class FakeSink : RoutingSink {
        // Every table written to the remote file, which always gets the full one.
        val writes = MutableStateFlow<List<String>>(emptyList())
        var preferencesTable: String? = null
        var preferencesGeneration: Long? = null
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

        override suspend fun read(): String? = preferencesTable

        override suspend fun publishedGeneration(): Long? = preferencesGeneration

        override suspend fun write(
            encoded: String,
            generation: Long,
            forPreferences: String?,
        ) {
            if (failWrites) error("boom")
            assertEquals(generation, RoutingCodec.decode(encoded).generation)
            if (forPreferences != null) {
                // Binder: Remote Preferences never get more than this.
                assertTrue(forPreferences.length <= RoutingContract.MAX_PREFERENCES_CHARS)
                assertTrue(RoutingCodec.decode(forPreferences).generation <= generation)
                preferencesTable = forPreferences
            }
            preferencesGeneration = generation
            writes.value += encoded
        }

        val nudges = mutableListOf<Long>()

        override fun nudge(generation: Long) {
            nudges += generation
        }
    }

    private suspend fun published(
        publisher: RoutingPublisher,
        predicate: (PublishState.Published) -> Boolean,
    ): PublishState.Published = publisher.state.filterIsInstance<PublishState.Published>().first(predicate)

    /** The app's copy of the table, in memory. */
    private class FakeStore : PublishedTableStore {
        var encoded: String? = null

        override suspend fun read(): String? = encoded

        override suspend fun write(
            encoded: String,
            generation: Long,
        ) {
            assertEquals(generation, RoutingCodec.decode(encoded).generation)
            this.encoded = encoded
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
