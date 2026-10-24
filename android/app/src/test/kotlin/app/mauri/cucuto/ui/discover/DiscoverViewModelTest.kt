package app.mauri.cucuto.ui.discover

import app.mauri.cucuto.R
import app.mauri.cucuto.core.data.db.RepositoryAuthType
import app.mauri.cucuto.core.repo.FileIndexCache
import app.mauri.cucuto.core.repo.RepoError
import app.mauri.cucuto.core.repo.RepositoryCredentials
import app.mauri.cucuto.core.repo.RepositoryLinks
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Duration.Companion.seconds

/** Discover against a fake pack repository over real TLS (MockWebServer), with the real client and caches. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val routes = Routes()
    private lateinit var store: FakeDiscoverStore
    private val catalog = FakeCatalog()
    private val links = IncomingRepoLinks()
    private val client = testClient()
    private lateinit var indexCache: FileIndexCache

    private val defaultPath = "/repo/index.json"

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

    private fun TestScope.viewModel(): DiscoverViewModel {
        val vm =
            DiscoverViewModel(
                store = store,
                client = client,
                indexCache = indexCache,
                previews = PreviewStore(client, tmp.newFolder()),
                downloader = PackDownloader(client, catalog, tmp.newFolder()),
                catalog = catalog,
                links = links,
            )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return vm
    }

    private suspend fun DiscoverViewModel.awaitState(predicate: (DiscoverUiState) -> Boolean): DiscoverUiState =
        state.first(predicate)

    private fun DiscoverUiState.section(index: Int = 0) = sections!![index]

    private fun serveDefault(vararg entries: String) {
        routes[defaultPath] = ok(indexJson("Cucuto demo packs", *entries))
    }

    @Test
    fun `opening Discover adds the default repository and shows its packs with badges`() =
        runTest(timeout = 30.seconds) {
            serveDefault(
                packEntry("app.mauri.demo.cats", "Cats", version = "1.2.0", tags = listOf("animals")),
                packEntry("app.mauri.demo.dogs", "Dogs", tags = listOf("animals", "pets")),
                packEntry("app.mauri.demo.bells", "Bells"),
            )
            store.installed.value =
                listOf(
                    InstalledRef("app.mauri.demo.cats", "1.0.0", null, null),
                    InstalledRef("app.mauri.demo.dogs", "1.0.0", null, null),
                )
            val vm = viewModel()

            val state = vm.awaitState { it.sections?.firstOrNull()?.loaded == true }
            val section = state.section()
            assertTrue(section.repository.isDefault)
            assertEquals("Cucuto demo packs", section.title)
            assertEquals("localhost", section.host)
            assertEquals(listOf("Cats", "Dogs", "Bells"), section.cards.map { it.name })
            assertEquals(
                listOf(InstallState.UPDATE, InstallState.INSTALLED, InstallState.NEW),
                section.cards.map { it.state },
            )
            assertEquals(listOf("animals", "pets"), state.tags)
            // The index name is remembered for the repositories sheet.
            assertEquals(
                "Cucuto demo packs",
                store.repositories.value
                    .single()
                    .name,
            )
            assertEquals(listOf(defaultPath), routes.paths())
        }

    @Test
    fun `search and tag filters narrow the cards`() =
        runTest(timeout = 30.seconds) {
            serveDefault(
                packEntry("app.mauri.demo.cats", "Cats", tags = listOf("animals"), character = "Tabby"),
                packEntry("app.mauri.demo.bells", "Bells", tags = listOf("objects")),
            )
            val vm = viewModel()
            vm.awaitState { it.sections?.firstOrNull()?.loaded == true }

            vm.setQuery("tab")
            assertEquals(
                listOf("Cats"),
                vm.state.value
                    .section()
                    .cards
                    .map { it.name },
            )
            vm.setQuery("")
            vm.toggleTag("objects")
            assertEquals(
                listOf("Bells"),
                vm.state.value
                    .section()
                    .cards
                    .map { it.name },
            )
            vm.toggleTag("objects")
            assertEquals(
                2,
                vm.state.value
                    .section()
                    .cards.size,
            )
            vm.setQuery("nothing like this")
            assertTrue(
                vm.state.value
                    .section()
                    .cards
                    .isEmpty(),
            )
            assertEquals(
                2,
                vm.state.value
                    .section()
                    .totalCards,
            )
            vm.clearFilters()
            assertFalse(vm.state.value.filtering)
        }

    @Test
    fun `offline, the last copy stays browsable and says so`() =
        runTest(timeout = 30.seconds) {
            serveDefault(packEntry("app.mauri.demo.cats", "Cats"))
            val first = viewModel()
            first.awaitState { it.sections?.firstOrNull()?.loaded == true }

            // The server goes away; a new visit to Discover still shows the cached index.
            server.close()
            val vm = viewModel()
            val state = vm.awaitState { it.sections?.firstOrNull()?.let { s -> !s.loading } == true }
            val section = state.section()
            assertTrue(section.stale)
            assertTrue(section.error is RepoError.Network)
            assertEquals(listOf("Cats"), section.cards.map { it.name })
        }

    @Test
    fun `a failing repository without a cached copy shows the error and can be retried`() =
        runTest(timeout = 30.seconds) {
            routes[defaultPath] = status(503)
            val vm = viewModel()
            val failed = vm.awaitState { it.sections?.firstOrNull()?.error != null }.section()
            assertFalse(failed.loaded)
            assertEquals(RepoError.HttpStatus(503), failed.error)
            assertEquals(
                R.string.repo_error_server,
                app.mauri.cucuto.packs
                    .plainWords(failed.error!!),
            )

            serveDefault(packEntry("app.mauri.demo.cats", "Cats"))
            vm.retry(failed.repository)
            val fixed = vm.awaitState { it.sections?.firstOrNull()?.loaded == true }.section()
            assertNull(fixed.error)
            assertEquals(1, fixed.cards.size)
        }

    @Test
    fun `an empty repository is loaded with no cards`() =
        runTest(timeout = 30.seconds) {
            serveDefault()
            val vm = viewModel()
            val section = vm.awaitState { it.sections?.firstOrNull()?.loaded == true }.section()
            assertEquals(0, section.totalCards)
        }

    @Test
    fun `adding a repository checks it first, then saves it with its name`() =
        runTest(timeout = 30.seconds) {
            serveDefault()
            routes["/friends/repository.json"] =
                ok(indexJson("Friends' packs", packEntry("app.example.friends", "Friends")))
            val vm = viewModel()
            vm.awaitState { it.sections?.firstOrNull()?.loaded == true }

            vm.openAdd()
            vm.updateForm { it.copy(input = server.at("/friends/").toString()) }
            val form = vm.state.value.form!!
            assertEquals("localhost", form.host)
            assertFalse(form.trusted)
            vm.submitForm()

            val state = vm.awaitState { it.form == null }
            assertEquals(DiscoverMessage.Added("Friends' packs"), state.message)
            val added = store.repositories.value.last()
            assertEquals(server.at("/friends/repository.json").toString(), added.url)
            assertEquals("Friends' packs", added.name)
            val section = vm.awaitState { it.sections?.size == 2 }.section(1)
            assertEquals(listOf("Friends"), section.cards.map { it.name })
            // Saved from the check: no second request for the same index.
            assertEquals(1, routes.paths().count { it == "/friends/repository.json" })
        }

    @Test
    fun `a private repository asks for a sign-in, and the credentials go only to it`() =
        runTest(timeout = 30.seconds) {
            serveDefault()
            val credentials = RepositoryCredentials.Basic("ana", "s3cret")
            val path = "/private/repository.json"
            routes[path] = status(401)
            val vm = viewModel()
            vm.awaitState { it.sections?.firstOrNull()?.loaded == true }

            vm.openAdd()
            vm.updateForm { it.copy(input = server.at(path).toString()) }
            vm.submitForm()
            val asked = vm.awaitState { it.form?.error != null }.form!!
            assertEquals(FormError.Fetch(RepoError.Unauthorized(401)), asked.error)
            assertTrue(store.repositories.value.none { it.url.endsWith(path) })

            routes[path] = ok(indexJson("Private packs"))
            vm.updateForm { it.copy(auth = AuthKind.BASIC, username = "ana", password = "s3cret") }
            vm.submitForm()
            vm.awaitState { it.form == null }

            val saved = store.repositories.value.single { it.url.endsWith(path) }
            assertEquals(RepositoryAuthType.BASIC, saved.authType)
            assertEquals(credentials, store.credentials[saved.url])
            assertEquals(credentials.authorizationHeader(), routes.request(path).headers["Authorization"])
            assertNull(routes.request(defaultPath).headers["Authorization"])
        }

    @Test
    fun `invalid credentials and bad links never reach the network`() =
        runTest(timeout = 30.seconds) {
            serveDefault()
            val vm = viewModel()
            vm.awaitState { it.sections?.firstOrNull()?.loaded == true }
            val requests = routes.requests.size

            vm.openAdd()
            vm.updateForm { it.copy(input = "http://example.com/packs/") }
            vm.submitForm()
            assertEquals(
                FormError.Link(app.mauri.cucuto.core.repo.LinkError.NOT_HTTPS),
                vm.state.value.form
                    ?.error,
            )

            vm.updateForm {
                it.copy(input = server.at("/x/").toString(), auth = AuthKind.BASIC, username = "a:b", password = "p")
            }
            vm.submitForm()
            assertEquals(
                FormError.InvalidCredentials,
                vm.state.value.form
                    ?.error,
            )
            assertEquals(requests, routes.requests.size)
        }

    @Test
    fun `adding a repository that is already there turns it back on`() =
        runTest(timeout = 30.seconds) {
            serveDefault()
            val vm = viewModel()
            val repo = vm.awaitState { it.sections?.firstOrNull()?.loaded == true }.section().repository
            vm.setEnabled(repo, false)
            assertEquals(emptyList<RepositorySection>(), vm.state.value.sections)

            vm.openAdd()
            vm.updateForm { it.copy(input = repo.url) }
            vm.submitForm()
            val state = vm.awaitState { it.message != null }
            assertEquals(DiscoverMessage.AlreadyAdded("Cucuto demo packs"), state.message)
            assertTrue(
                store.repositories.value
                    .single()
                    .enabled,
            )
        }

    @Test
    fun `an Add to app link fills in the sheet and fetches nothing until confirmed`() =
        runTest(timeout = 30.seconds) {
            serveDefault()
            val vm = viewModel()
            vm.awaitState { it.sections?.firstOrNull()?.loaded == true }
            val requests = routes.requests.size

            links.offer(RepositoryLinks.deepLinkFor(server.at("/shared/repository.json")))
            val form = vm.state.value.form!!
            assertTrue(form.fromLink)
            assertEquals(server.at("/shared/repository.json").toString(), form.input)
            assertEquals(requests, routes.requests.size)
            assertNull(links.pending.value)

            links.offer("cucuto://repo/add?url=http://insecure.example/")
            assertEquals(DiscoverMessage.BadLink, vm.state.value.message)
        }

    @Test
    fun `installing downloads the verified file and opens the import with the repository as origin`() =
        runTest(timeout = 30.seconds) {
            serveDefault(packEntry("app.mauri.demo.cats", "Cats"))
            routes["/repo/packs/app.mauri.demo.cats-1.0.0.soundpack.zip"] = ok(packBytes)
            val vm = viewModel()
            val card =
                vm
                    .awaitState { it.sections?.firstOrNull()?.loaded == true }
                    .section()
                    .cards
                    .single()

            vm.install(card)
            // The same review as a local file, remembering where the file came from.
            val review = vm.awaitState { it.import is ImportUi.Review }.import as ImportUi.Review
            val origin =
                PackOrigin.Repository(
                    store.repositories.value
                        .single()
                        .url,
                )
            assertEquals(origin, review.review.origin)
            val (bytes, prepareOrigin) = catalog.prepared.single()
            assertArrayEquals(packBytes, bytes)
            assertEquals(origin, prepareOrigin)
            assertEquals(1, routes.paths().count { it.endsWith(".soundpack.zip") })

            vm.confirmInstall()
            val message = vm.awaitState { it.message != null }.message
            assertEquals(listOf("Pack app.mauri.demo.cats"), (message as DiscoverMessage.Installed).installed.names)
            assertEquals(ImportUi.Idle, vm.state.value.import)
        }

    @Test
    fun `a download that does not match the index ends in a problem, not in the importer`() =
        runTest(timeout = 30.seconds) {
            serveDefault(packEntry("app.mauri.demo.cats", "Cats", sha = "0".repeat(64)))
            routes["/repo/packs/app.mauri.demo.cats-1.0.0.soundpack.zip"] = ok(packBytes)
            val vm = viewModel()
            val card =
                vm
                    .awaitState { it.sections?.firstOrNull()?.loaded == true }
                    .section()
                    .cards
                    .single()

            vm.install(card)
            val problem = (vm.awaitState { it.import is ImportUi.Problem }.import as ImportUi.Problem).problem
            assertEquals(R.string.download_problem_title, problem.title)
            assertEquals(R.string.error_download_hash_mismatch, problem.summary)
            assertTrue(catalog.prepared.isEmpty())
            vm.closeImport()
            assertEquals(ImportUi.Idle, vm.state.value.import)
        }

    @Test
    fun `a direct pack link downloads once through the importer without saving a repository`() =
        runTest(timeout = 30.seconds) {
            serveDefault()
            routes["/files/friends.soundpack.zip"] = ok(packBytes)
            val vm = viewModel()
            vm.awaitState { it.sections?.firstOrNull()?.loaded == true }

            vm.openAdd()
            vm.updateForm { it.copy(input = server.at("/files/friends.soundpack.zip").toString()) }
            vm.submitForm()
            vm.awaitState { it.import is ImportUi.Review }
            val (bytes, origin) = catalog.prepared.single()
            assertArrayEquals(packBytes, bytes)
            assertEquals(PackOrigin.Url(server.at("/files/friends.soundpack.zip").toString()), origin)
            assertEquals(1, store.repositories.value.size)
        }

    @Test
    fun `previews are fetched once, cached, and checked against the media rules`() =
        runTest(timeout = 30.seconds) {
            serveDefault(packEntry("app.mauri.demo.cats", "Cats"))
            routes["/repo/previews/app.mauri.demo.cats/cover.png"] = ok(pngBytes)
            routes["/repo/previews/app.mauri.demo.cats/c1.png"] = ok("<html>not a picture</html>")
            val vm = viewModel()
            val card =
                vm
                    .awaitState { it.sections?.firstOrNull()?.loaded == true }
                    .section()
                    .cards
                    .single()

            val cover = vm.preview(card.cover!!, card.repositoryId)
            assertNotNull(cover)
            assertArrayEquals(pngBytes, cover!!.readBytes())
            assertEquals(cover, vm.preview(card.cover!!, card.repositoryId))
            assertEquals(1, routes.paths().count { it.endsWith("/cover.png") })

            assertNull(vm.preview(card.characters.single().sprite!!, card.repositoryId))
        }

    @Test
    fun `removing a repository forgets it, its credentials and its cached index`() =
        runTest(timeout = 30.seconds) {
            serveDefault()
            val url = server.at("/friends/repository.json").toString()
            routes["/friends/repository.json"] = ok(indexJson("Friends' packs"))
            store.add(SavedRepository(10, url, "Friends' packs", false, true, RepositoryAuthType.BEARER))
            store.credentials[url] = RepositoryCredentials.Bearer("t0ken")
            val vm = viewModel()
            val friends =
                vm
                    .awaitState { s -> s.sections?.size == 2 && s.sections.all { it.loaded } }
                    .sections!!
                    .single { !it.repository.isDefault }
            assertEquals("Bearer t0ken", routes.request("/friends/repository.json").headers["Authorization"])
            assertNotNull(indexCache.read(url))

            vm.remove(friends.repository)
            // The message comes last, once the cached index is gone too.
            assertEquals(DiscoverMessage.Removed("Friends' packs"), vm.awaitState { it.message != null }.message)
            assertEquals(
                1,
                vm.state.value.sections
                    ?.size,
            )
            assertTrue(store.credentials.isEmpty())
            assertNull(indexCache.read(url))
            // The default repository cannot be removed.
            vm.remove(
                vm.state.value
                    .section()
                    .repository,
            )
            assertEquals(1, store.repositories.value.size)
        }

    @Test
    fun `nothing is fetched for repositories that are turned off`() =
        runTest(timeout = 30.seconds) {
            serveDefault()
            store.add(
                SavedRepository(
                    10,
                    server.at("/off/repository.json").toString(),
                    "Off",
                    false,
                    false,
                    RepositoryAuthType.NONE,
                ),
            )
            val vm = viewModel()
            vm.awaitState { it.sections?.firstOrNull()?.loaded == true }
            assertEquals(
                1,
                vm.state.value.sections!!
                    .size,
            )
            assertTrue(routes.paths().none { it.startsWith("/off/") })
        }
}
