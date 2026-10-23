package app.mauri.cucuto.packs

import app.mauri.cucuto.R
import app.mauri.cucuto.core.data.db.PackSource
import app.mauri.cucuto.core.pack.FailureReason
import app.mauri.cucuto.core.pack.ImportProgress
import app.mauri.cucuto.core.pack.ImportSource
import app.mauri.cucuto.core.pack.InstallAction
import app.mauri.cucuto.core.pack.InstalledPack
import app.mauri.cucuto.core.pack.PackCandidate
import app.mauri.cucuto.core.pack.PackSelection
import app.mauri.cucuto.packspec.BundleManifest
import app.mauri.cucuto.packspec.Character
import app.mauri.cucuto.packspec.Clip
import app.mauri.cucuto.packspec.ErrorCode
import app.mauri.cucuto.packspec.Issue
import app.mauri.cucuto.packspec.PackManifest
import app.mauri.cucuto.packspec.ValidationReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PacksViewModelTest {
    private val catalog = FakeCatalog()
    private val incoming = IncomingImports()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(): PacksViewModel =
        PacksViewModel(catalog, incoming).also { vm ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        }

    @Test
    fun `installed packs load after a loading state`() =
        runTest {
            val vm = PacksViewModel(catalog, incoming)
            assertNull(vm.state.value.packs)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            assertEquals(emptyList<LibraryPack>(), vm.state.value.packs)
            catalog.installed.value = listOf(libraryPack("app.mauri.demo.cats"))
            assertEquals(
                listOf("app.mauri.demo.cats"),
                vm.state.value.packs
                    ?.map { it.id },
            )
        }

    @Test
    fun `a valid pack is reviewed, installed and announced`() =
        runTest {
            val review = review(pack("app.mauri.demo.cats", characterCount = 4))
            catalog.prepareSteps = listOf(ImportStep.Working(ImportProgress.Validating), ImportStep.Ready(review))
            catalog.installSteps = { _, _ ->
                listOf(
                    ImportStep.Working(ImportProgress.Installing(0, 1)),
                    ImportStep.Installed(listOf(installed("app.mauri.demo.cats")), emptyList()),
                )
            }
            val vm = viewModel()

            vm.importFrom(source())
            val reviewing = vm.state.value.import as ImportUi.Review
            assertSame(review, reviewing.review)
            assertEquals(setOf("app.mauri.demo.cats"), reviewing.selection.selected)
            assertTrue(reviewing.canInstall)

            vm.install()
            assertEquals(ImportUi.Idle, vm.state.value.import)
            assertEquals(PacksMessage.Installed(listOf("Pack app.mauri.demo.cats"), 4, 0), vm.state.value.message)
            assertEquals(
                PackSelection(setOf("app.mauri.demo.cats"), setOf("app.mauri.demo.cats")),
                catalog.lastSelection(),
            )

            vm.messageShown()
            assertNull(vm.state.value.message)
        }

    @Test
    fun `progress is shown while working`() =
        runTest {
            catalog.prepareFlow = {
                flow {
                    emit(ImportStep.Working(ImportProgress.Decoding(2, 5)))
                    awaitCancellation()
                }
            }
            val vm = viewModel()
            vm.importFrom(source())
            assertEquals(ImportUi.Working(ImportProgress.Decoding(2, 5)), vm.state.value.import)

            vm.closeImport()
            assertEquals(ImportUi.Idle, vm.state.value.import)
            assertTrue(catalog.prepareCancelled)
        }

    @Test
    fun `an invalid pack shows its problems in plain words`() =
        runTest {
            val report =
                ValidationReport(listOf(Issue(ErrorCode.CLIP_DURATION_OUT_OF_RANGE, "6 s", "cat/clips/meow.ogg")))
            catalog.prepareSteps = listOf(ImportStep.Invalid(report))
            val vm = viewModel()
            vm.importFrom(source())

            val problem = (vm.state.value.import as ImportUi.Problem).problem
            assertEquals(R.string.import_problem_invalid_title, problem.title)
            assertEquals(R.string.error_clip_duration_out_of_range, problem.items.single().message)
            assertEquals("cat/clips/meow.ogg", problem.items.single().file)

            vm.closeImport()
            assertEquals(ImportUi.Idle, vm.state.value.import)
            assertTrue(catalog.discarded.isEmpty())
        }

    @Test
    fun `a backup and an unreadable file get their own explanations`() =
        runTest {
            val notAPack = ValidationReport(listOf(Issue(ErrorCode.MANIFEST_MISSING, "none")))
            catalog.prepareSteps = listOf(ImportStep.Invalid(notAPack, DetectedFile.BACKUP))
            val vm = viewModel()
            vm.importFrom(source())
            assertEquals(
                R.string.import_problem_backup_title,
                (vm.state.value.import as ImportUi.Problem).problem.title,
            )

            catalog.prepareSteps = listOf(ImportStep.Failed(FailureReason.SOURCE_UNREADABLE, "reset"))
            vm.importFrom(source())
            val problem = (vm.state.value.import as ImportUi.Problem).problem
            assertEquals(R.string.import_problem_failed_title, problem.title)
            assertEquals(R.string.import_failure_source_unreadable, problem.summary)
        }

    @Test
    fun `a crash in the pipeline becomes a problem, not a crash`() =
        runTest {
            catalog.prepareFlow = { flow { throw IllegalStateException("boom") } }
            val vm = viewModel()
            vm.importFrom(source())
            val problem = (vm.state.value.import as ImportUi.Problem).problem
            assertEquals(R.string.import_failure_unexpected, problem.summary)
        }

    @Test
    fun `bundle packs that replace an installed copy start unselected and selecting confirms them`() =
        runTest {
            val fresh = pack("app.mauri.demo.cats")
            val same = pack("app.mauri.demo.dogs", action = InstallAction.REINSTALL, installedVersion = "1.0.0")
            val older = pack("app.mauri.demo.frogs", action = InstallAction.DOWNGRADE, installedVersion = "2.0.0")
            catalog.prepareSteps = listOf(ImportStep.Ready(review(fresh, same, older, bundle = true)))
            catalog.installSteps = { _, selection ->
                listOf(ImportStep.Installed(selection.selected.map(::installed), emptyList()))
            }
            val vm = viewModel()
            vm.importFrom(source())
            assertEquals(setOf("app.mauri.demo.cats"), (vm.state.value.import as ImportUi.Review).selection.selected)

            vm.setSelected("app.mauri.demo.dogs", true)
            vm.setSelected("app.mauri.demo.cats", false)
            val selection = (vm.state.value.import as ImportUi.Review).selection
            assertEquals(PackSelection(setOf("app.mauri.demo.dogs"), setOf("app.mauri.demo.dogs")), selection)

            vm.install()
            assertEquals(selection, catalog.lastSelection())
            assertEquals(listOf("Pack app.mauri.demo.dogs"), (vm.state.value.message as PacksMessage.Installed).names)
        }

    @Test
    fun `nothing selected cannot be installed`() =
        runTest {
            catalog.prepareSteps = listOf(ImportStep.Ready(review(pack("app.mauri.demo.cats"), bundle = true)))
            val vm = viewModel()
            vm.importFrom(source())
            vm.setSelected("app.mauri.demo.cats", false)
            assertFalse((vm.state.value.import as ImportUi.Review).canInstall)
            vm.install()
            assertTrue(catalog.installCalls.isEmpty())
        }

    @Test
    fun `a single pack that is already installed is replaced with the install button`() =
        runTest {
            val same = pack("app.mauri.demo.cats", action = InstallAction.REINSTALL, installedVersion = "1.0.0")
            catalog.prepareSteps = listOf(ImportStep.Ready(review(same)))
            catalog.installSteps = { _, _ -> listOf(ImportStep.Installed(listOf(installed(same.id)), emptyList())) }
            val vm = viewModel()
            vm.importFrom(source())
            assertTrue((vm.state.value.import as ImportUi.Review).canInstall)
            vm.install()
            assertEquals(PackSelection(setOf(same.id), setOf(same.id)), catalog.lastSelection())
        }

    @Test
    fun `skipped packs are counted`() =
        runTest {
            val cats = pack("app.mauri.demo.cats")
            catalog.prepareSteps = listOf(ImportStep.Ready(review(cats)))
            catalog.installSteps = { _, _ -> listOf(ImportStep.Installed(emptyList(), listOf(cats.candidate))) }
            val vm = viewModel()
            vm.importFrom(source())
            vm.install()
            assertEquals(PacksMessage.Installed(emptyList(), 0, 1), vm.state.value.message)
        }

    @Test
    fun `an install failure is shown as a problem`() =
        runTest {
            catalog.prepareSteps = listOf(ImportStep.Ready(review(pack("app.mauri.demo.cats"))))
            catalog.installSteps = { _, _ -> listOf(ImportStep.Failed(FailureReason.STORAGE_ERROR, "disk full")) }
            val vm = viewModel()
            vm.importFrom(source())
            vm.install()
            val problem = (vm.state.value.import as ImportUi.Problem).problem
            assertEquals(R.string.import_failure_storage_error, problem.summary)
        }

    @Test
    fun `closing or replacing a review discards it`() =
        runTest {
            val first = review(pack("app.mauri.demo.cats"))
            val second = review(pack("app.mauri.demo.dogs"))
            catalog.prepareSteps = listOf(ImportStep.Ready(first))
            val vm = viewModel()
            vm.importFrom(source())

            catalog.prepareSteps = listOf(ImportStep.Ready(second))
            vm.importFrom(source())
            assertEquals(listOf(first), catalog.discarded)
            assertSame(second, (vm.state.value.import as ImportUi.Review).review)

            vm.closeImport()
            assertEquals(listOf(first, second), catalog.discarded)
            assertEquals(ImportUi.Idle, vm.state.value.import)
        }

    @Test
    fun `an install in progress is not interrupted`() =
        runTest {
            catalog.prepareSteps = listOf(ImportStep.Ready(review(pack("app.mauri.demo.cats"))))
            catalog.installFlow = { flow { awaitCancellation() } }
            val vm = viewModel()
            vm.importFrom(source())
            vm.install()

            vm.closeImport()
            vm.importFrom(source())
            vm.setSelected("app.mauri.demo.cats", false)
            val state = vm.state.value.import as ImportUi.Review
            assertTrue(state.installing)
            assertEquals(setOf("app.mauri.demo.cats"), state.selection.selected)
            assertTrue(catalog.discarded.isEmpty())
            assertEquals(1, catalog.prepareCalls)
        }

    @Test
    fun `files opened with Cucuto are imported`() =
        runTest {
            catalog.prepareSteps = listOf(ImportStep.Ready(review(pack("app.mauri.demo.cats"))))
            val vm = viewModel()
            incoming.offer(source())
            assertTrue(vm.state.value.import is ImportUi.Review)
            assertNull(incoming.pending.value)
            assertEquals(1, catalog.prepareCalls)
        }

    @Test
    fun `removing asks first and can clear the assignments`() =
        runTest {
            val pack = libraryPack("app.mauri.demo.cats", contacts = 2)
            catalog.installed.value = listOf(pack)
            val vm = viewModel()

            vm.requestRemove(pack)
            assertEquals(RemovalUi(pack), vm.state.value.removal)
            assertTrue(catalog.removed.isEmpty())

            vm.setClearAssignments(true)
            vm.confirmRemove()
            assertEquals(listOf(pack.id to true), catalog.removed)
            assertNull(vm.state.value.removal)
            assertEquals(PacksMessage.Removed(pack.name), vm.state.value.message)
        }

    @Test
    fun `removing keeps the assignments by default and can be cancelled`() =
        runTest {
            val pack = libraryPack("app.mauri.demo.cats", contacts = 1)
            val vm = viewModel()
            vm.requestRemove(pack)
            vm.cancelRemove()
            assertNull(vm.state.value.removal)
            assertTrue(catalog.removed.isEmpty())

            vm.requestRemove(pack)
            vm.confirmRemove()
            assertEquals(listOf(pack.id to false), catalog.removed)
        }

    @Test
    fun `a failed removal is reported`() =
        runTest {
            catalog.removeError = IllegalStateException("busy")
            val pack = libraryPack("app.mauri.demo.cats")
            val vm = viewModel()
            vm.requestRemove(pack)
            vm.confirmRemove()
            assertEquals(PacksMessage.RemoveFailed(pack.name), vm.state.value.message)
            assertNull(vm.state.value.removal)
        }

    private fun source(): ImportSource =
        object : ImportSource {
            override fun describe() = ImportSource.Description("made-up.soundpack.zip", 3)

            override fun open() = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        }

    private fun manifest(
        id: String,
        characters: Int,
    ) = PackManifest(
        formatVersion = 1,
        id = id,
        name = "Pack $id",
        version = "1.0.0",
        author = "Test author",
        license = "CC0-1.0",
        characters =
            (1..characters).map { n ->
                Character(
                    id = "c$n",
                    name = "Character $n",
                    sprite = "c$n/sprite.png",
                    clips = listOf(Clip("c$n/a.ogg")),
                )
            },
    )

    private fun pack(
        id: String,
        characterCount: Int = 2,
        action: InstallAction = InstallAction.INSTALL,
        installedVersion: String? = null,
    ): ReviewPack {
        val manifest = manifest(id, characterCount)
        val candidate = PackCandidate(id, manifest, action, installedVersion, sizeBytes = 1_000, fileCount = 3)
        return ReviewPack(candidate, characters(manifest, File("/staging/$id")))
    }

    private fun review(
        vararg packs: ReviewPack,
        bundle: Boolean = false,
    ) = ImportReview(
        fileName = "made-up.soundpack.zip",
        bundle =
            if (bundle) {
                BundleManifest(
                    1,
                    "bundle",
                    "app.mauri.demo.all",
                    "All",
                    "1.0.0",
                    packs = packs.map { "packs/${it.id}" },
                )
            } else {
                null
            },
        packs = packs.toList(),
        warnings = emptyList(),
        handle = null,
    )

    private fun installed(id: String) = InstalledPack(id, "1.0.0", File("/packs/$id/1.0.0"), null, active = true)

    private fun libraryPack(
        id: String,
        contacts: Int = 0,
    ) = LibraryPack(
        id = id,
        name = "Pack $id",
        version = "1.0.0",
        author = "Test author",
        license = "CC0-1.0",
        description = null,
        cover = null,
        characters = characters(manifest(id, 2), File("/packs/$id/1.0.0")),
        sizeBytes = 1_000,
        source = PackSource.LOCAL,
        bundleId = null,
        contacts = (1..contacts).map { AssignedContact(it.toLong(), "Contact $it", "c1") },
    )

    private class FakeCatalog : PackCatalog {
        override val installed = MutableStateFlow<List<LibraryPack>>(emptyList())

        var prepareSteps: List<ImportStep> = emptyList()
        var prepareFlow: (() -> Flow<ImportStep>)? = null
        var prepareCalls = 0
        var prepareCancelled = false
        var installSteps: (ImportReview, PackSelection) -> List<ImportStep> = { _, _ -> emptyList() }
        var installFlow: (() -> Flow<ImportStep>)? = null
        val installCalls = mutableListOf<PackSelection>()
        val discarded = mutableListOf<ImportReview>()
        val removed = mutableListOf<Pair<String, Boolean>>()
        var removeError: Exception? = null

        fun lastSelection(): PackSelection = installCalls.single()

        override fun prepare(source: ImportSource): Flow<ImportStep> {
            prepareCalls++
            val custom = prepareFlow ?: return flowOf(*prepareSteps.toTypedArray())
            return flow {
                try {
                    custom().collect { emit(it) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    prepareCancelled = true
                    throw e
                }
            }
        }

        override fun install(
            review: ImportReview,
            selection: PackSelection,
        ): Flow<ImportStep> {
            installCalls += selection
            return installFlow?.invoke() ?: flowOf(*installSteps(review, selection).toTypedArray())
        }

        override fun discard(review: ImportReview) {
            discarded += review
        }

        override suspend fun remove(
            packId: String,
            clearAssignments: Boolean,
        ) {
            removeError?.let { throw it }
            removed += packId to clearAssignments
        }
    }
}
