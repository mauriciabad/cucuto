package app.mauri.cucuto.ui.discover

import app.mauri.cucuto.core.pack.ImportSource
import app.mauri.cucuto.core.repo.FileIndexCache
import app.mauri.cucuto.core.repo.RepoError
import app.mauri.cucuto.packs.ImportUi
import app.mauri.cucuto.packs.PackOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Duration.Companion.seconds

/**
 * Home's "Get the demo packs" (#179): Discover shows only the demo bundle and starts its install through the usual
 * review, using the network only after the tap, with the file picker as the fallback. Same fake repository over real
 * TLS as [DiscoverViewModelTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DemoPacksTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val routes = Routes()
    private lateinit var store: FakeDiscoverStore
    private val catalog = FakeCatalog()
    private val requests = DemoPacksRequests()
    private val client = testClient()
    private lateinit var indexCache: FileIndexCache

    private val defaultPath = "/repo/index.json"
    private val bundlePath = "/repo/packs/$DEMO_BUNDLE_ID-1.0.0.soundpack.zip"

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = repositoryServer()
        server.dispatcher = routes
        store = FakeDiscoverStore(server.at(defaultPath).toString())
        indexCache = FileIndexCache(tmp.newFolder("repo-index"))
    }

    @After
    fun tearDown() {
        server.close()
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(requests: DemoPacksRequests = this@DemoPacksTest.requests): DiscoverViewModel {
        val vm =
            DiscoverViewModel(
                store = store,
                client = client,
                indexCache = indexCache,
                previews = PreviewStore(client, tmp.newFolder()),
                downloader = PackDownloader(client, catalog, tmp.newFolder()),
                catalog = catalog,
                links = IncomingRepoLinks(),
                demoRequests = requests,
            )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return vm
    }

    private suspend fun DiscoverViewModel.awaitState(predicate: (DiscoverUiState) -> Boolean): DiscoverUiState =
        state.first(predicate)

    private fun serveDemo() {
        routes[defaultPath] =
            ok(indexJson("Cucuto demo packs", packEntry("app.mauri.demo.cats", "Cats"), demoBundleEntry()))
        routes[bundlePath] = ok(packBytes)
    }

    private fun defaultOrigin() =
        PackOrigin.Repository(
            store.repositories.value
                .single { it.isDefault }
                .url,
        )

    @Test
    fun `the tap opens Discover on the demo bundle alone and starts its review`() =
        runTest(timeout = 30.seconds) {
            serveDemo()
            requests.offer()
            val vm = viewModel()

            val state = vm.awaitState { it.import is ImportUi.Review }
            assertEquals(DEMO_BUNDLE_ID, state.focus)
            assertTrue(state.filtering)
            assertEquals(DemoPacksUi.Importing, state.demo)
            assertEquals(
                listOf(DEMO_BUNDLE_ID),
                state.sections!!
                    .single()
                    .cards
                    .map { it.id },
            )
            // Verified against the index's size and SHA-256, then handed to the importer with the repository as origin.
            val (bytes, origin) = catalog.prepared.single()
            assertArrayEquals(packBytes, bytes)
            assertEquals(defaultOrigin(), origin)
            // One index request (the opening fetch, not a second one for the demo) and one download.
            assertEquals(listOf(defaultPath, bundlePath), routes.paths())
            assertFalse(requests.pending.value)

            // The user still confirms in the review.
            vm.confirmInstall()
            val done = vm.awaitState { it.message != null }
            assertTrue(done.message is DiscoverMessage.Installed)
            assertNull(done.demo)
            assertEquals(ImportUi.Idle, done.import)
        }

    @Test
    fun `nothing is downloaded until the tap, which also works while Discover is open`() =
        runTest(timeout = 30.seconds) {
            serveDemo()
            val vm = viewModel()
            val opened = vm.awaitState { it.sections?.firstOrNull()?.loaded == true }
            assertNull(opened.demo)
            assertNull(opened.focus)
            assertEquals(
                2,
                opened.sections!!
                    .single()
                    .cards.size,
            )
            assertEquals(listOf(defaultPath), routes.paths())

            requests.offer()
            vm.awaitState { it.import is ImportUi.Review }
            // The index fetched on opening is reused; only the bundle is downloaded.
            assertEquals(listOf(defaultPath, bundlePath), routes.paths())
        }

    @Test
    fun `offline, it says why and the picked file goes through the same review`() =
        runTest(timeout = 30.seconds) {
            server.close()
            requests.offer()
            val vm = viewModel()

            val failed = vm.awaitState { it.demo is DemoPacksUi.Failed }
            val problem = (failed.demo as DemoPacksUi.Failed).problem
            assertTrue((problem as DemoProblem.Unreachable).error is RepoError.Network)
            assertEquals(ImportUi.Idle, failed.import)

            // The fallback: the demo bundle downloaded from the website and picked from Downloads.
            val file = tmp.newFile("cucuto-demo.soundpack.zip").apply { writeBytes(packBytes) }
            vm.importFile(ImportSource.of(file))
            val review = vm.awaitState { it.import is ImportUi.Review }
            assertEquals(DemoPacksUi.Importing, review.demo)
            val (bytes, origin) = catalog.prepared.single()
            assertArrayEquals(packBytes, bytes)
            assertNull(origin)
        }

    @Test
    fun `a stale copy of the index is not enough, and Try again works once the server is back`() =
        runTest(timeout = 30.seconds) {
            serveDemo()
            // An earlier visit cached the index (its own requests, so it does not pick up the tap below).
            viewModel(DemoPacksRequests()).awaitState { it.sections?.firstOrNull()?.loaded == true }

            // The index is cached, but the server is down: the download would fail, so say so instead.
            routes[defaultPath] = status(503)
            requests.offer()
            val vm = viewModel()
            val failed = vm.awaitState { it.demo is DemoPacksUi.Failed }
            assertEquals(
                DemoProblem.Unreachable(RepoError.HttpStatus(503)),
                (failed.demo as DemoPacksUi.Failed).problem,
            )
            assertTrue(routes.paths().none { it == bundlePath })

            serveDemo()
            vm.getDemoPacks()
            vm.awaitState { it.import is ImportUi.Review }
            assertEquals(1, routes.paths().count { it == bundlePath })
        }

    @Test
    fun `an index without the demo bundle says so, and clearing the filter shows every pack`() =
        runTest(timeout = 30.seconds) {
            routes[defaultPath] = ok(indexJson("Cucuto demo packs", packEntry("app.mauri.demo.cats", "Cats")))
            requests.offer()
            val vm = viewModel()

            val failed = vm.awaitState { it.demo is DemoPacksUi.Failed }
            assertEquals(DemoProblem.NotListed, (failed.demo as DemoPacksUi.Failed).problem)
            assertTrue(
                failed.sections!!
                    .single()
                    .cards
                    .isEmpty(),
            )

            vm.clearFilters()
            val cleared = vm.state.value
            assertNull(cleared.demo)
            assertNull(cleared.focus)
            assertEquals(
                listOf("Cats"),
                cleared.sections!!
                    .single()
                    .cards
                    .map { it.name },
            )
        }

    @Test
    fun `a default repository the user turned off comes back on for the demo packs`() =
        runTest(timeout = 30.seconds) {
            serveDemo()
            store.ensureDefault()
            val default = store.repositories.value.single()
            store.setEnabled(default.id, false)
            requests.offer()
            val vm = viewModel()

            vm.awaitState { it.import is ImportUi.Review }
            assertTrue(
                store.repositories.value
                    .single()
                    .enabled,
            )
        }

    @Test
    fun `closing the review ends the demo flow, but the bundle stays on screen`() =
        runTest(timeout = 30.seconds) {
            serveDemo()
            requests.offer()
            val vm = viewModel()
            vm.awaitState { it.import is ImportUi.Review }

            vm.closeImport()
            val closed = vm.state.value
            assertNull(closed.demo)
            assertEquals(DEMO_BUNDLE_ID, closed.focus)
            assertEquals(ImportUi.Idle, closed.import)
        }
}
