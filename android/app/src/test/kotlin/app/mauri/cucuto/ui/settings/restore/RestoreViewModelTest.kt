package app.mauri.cucuto.ui.settings.restore

import app.mauri.cucuto.backup.BackupTestData
import app.mauri.cucuto.backup.BackupTestData.CRITTERS
import app.mauri.cucuto.backup.BackupTestData.KITCHEN
import app.mauri.cucuto.backup.BackupTestData.PRIVATE
import app.mauri.cucuto.backup.FakeBackupGateway
import app.mauri.cucuto.backup.FakeSession
import app.mauri.cucuto.backup.OpenOutcome
import app.mauri.cucuto.backup.PackFailure
import app.mauri.cucuto.backup.PackProgress
import app.mauri.cucuto.core.backup.BackupPackSourceType
import app.mauri.cucuto.core.backup.PackRestoreAction
import app.mauri.cucuto.core.backup.ZipContentType
import app.mauri.cucuto.core.data.db.GroupPolicy
import app.mauri.cucuto.testing.FakeCharacterDirectory
import app.mauri.cucuto.ui.settings.FakeAutoBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RestoreViewModelTest {
    private val gateway = FakeBackupGateway()
    private val autoBackup = FakeAutoBackup()
    private val viewModel by lazy { RestoreViewModel(gateway, FakeCharacterDirectory(), autoBackup) }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private inline fun <reified T : RestoreStep> step(): T {
        val step = viewModel.step.value
        assertTrue("Expected ${T::class.simpleName}, got $step", step is T)
        return step as T
    }

    @Test
    fun `an encrypted backup asks for the passphrase, says when it is wrong and wipes what was typed`() =
        runTest {
            val session = FakeSession(BackupTestData.document(), encrypted = true)
            gateway.openOutcomes[null] = OpenOutcome.PassphraseRequired
            gateway.openOutcomes["correct horse"] = OpenOutcome.Opened(session)
            gateway.reports += BackupTestData.report(applied = listOf("1", "2"))

            viewModel.open("content://backup")
            assertFalse(step<RestoreStep.Passphrase>().wrong)

            val wrong = "battery staple".toCharArray()
            viewModel.submitPassphrase(wrong)
            assertTrue(step<RestoreStep.Passphrase>().wrong)
            assertTrue("the passphrase is wiped after use", wrong.all { it == '\u0000' })

            viewModel.submitPassphrase("correct horse".toCharArray())
            // No packs in this backup: straight to the summary.
            val done = step<RestoreStep.Done>()
            assertEquals(2, done.summary.voicesApplied)
            assertEquals(listOf(null, "battery staple", "correct horse"), gateway.openedWith)
            // A plain automatic backup must never replace an encrypted file.
            assertEquals(null, done.reusableSource)
            viewModel.keepForBackups()
            assertFalse(step<RestoreStep.Done>().keptForBackups)
            assertEquals(emptyList<String>(), autoBackup.chosenFiles)
        }

    @Test
    fun `a plain backup file is offered for the next automatic backups`() =
        runTest {
            gateway.openOutcomes[null] = OpenOutcome.Opened(FakeSession(BackupTestData.document()))
            gateway.reports += BackupTestData.report(applied = listOf("1"))

            viewModel.open("content://drive/backup")
            val done = step<RestoreStep.Done>()
            assertEquals("content://drive/backup", done.reusableSource)
            assertFalse(done.keptForBackups)

            viewModel.keepForBackups()
            assertTrue(step<RestoreStep.Done>().keptForBackups)
            assertEquals(listOf("content://drive/backup"), autoBackup.chosenFiles)
        }

    @Test
    fun `keeping the file stays off when the permission is refused`() =
        runTest {
            gateway.openOutcomes[null] = OpenOutcome.Opened(FakeSession(BackupTestData.document()))
            gateway.reports += BackupTestData.report(applied = listOf("1"))
            autoBackup.failChoose = true

            viewModel.open("content://drive/backup")
            viewModel.keepForBackups()
            assertFalse(step<RestoreStep.Done>().keptForBackups)
        }

    @Test
    fun `the screen opens its file once, and again in a new view model after process death`() =
        runTest {
            gateway.openOutcomes[null] = OpenOutcome.PassphraseRequired
            viewModel.start("content://backup")
            viewModel.submitPassphrase("wrong".toCharArray())
            // Shown again (rotation, back from another tab): the restore carries on where it was.
            viewModel.start("content://backup")
            assertEquals(listOf(null, "wrong"), gateway.openedWith)

            val restored = RestoreViewModel(gateway, FakeCharacterDirectory(), autoBackup)
            restored.start("content://backup")
            assertEquals(listOf(null, "wrong", null), gateway.openedWith)
            assertFalse((restored.step.value as RestoreStep.Passphrase).wrong)
        }

    @Test
    fun `a pack or a broken file is a problem, not a restore`() =
        runTest {
            gateway.openOutcomes[null] = OpenOutcome.NotABackup(ZipContentType.PACK)
            viewModel.open("content://pack")
            assertEquals(ProblemKind.PACK_NOT_BACKUP, step<RestoreStep.Problem>().kind)

            gateway.openOutcomes[null] = OpenOutcome.Invalid(listOf("SCHEMA_VIOLATION: /contacts/0"))
            viewModel.open("content://broken")
            val problem = step<RestoreStep.Problem>()
            assertEquals(ProblemKind.INVALID, problem.kind)
            assertEquals(listOf("SCHEMA_VIOLATION: /contacts/0"), problem.details)
        }

    @Test
    fun `packs that need no file start on their own and local-only packs wait for a file`() =
        runTest {
            gateway.instantPacks = null
            gateway.installed[KITCHEN] = null
            val document =
                BackupTestData.document(
                    packs =
                        listOf(
                            BackupTestData.pack(CRITTERS, BackupPackSourceType.REPOSITORY),
                            BackupTestData.pack(KITCHEN, BackupPackSourceType.URL),
                            BackupTestData.pack(
                                PRIVATE,
                                BackupPackSourceType.LOCAL,
                                fileName = "private.soundpack.zip",
                            ),
                        ),
                )
            gateway.openOutcomes[null] = OpenOutcome.Opened(FakeSession(document))
            viewModel.open("content://backup")

            val packs = step<RestoreStep.Packs>()
            assertEquals(listOf(CRITTERS), gateway.restoredPacks)
            assertEquals(PackStatus.Working(), packs.rows.single { it.id == CRITTERS }.status)
            assertEquals(PackStatus.Done, packs.rows.single { it.id == KITCHEN }.status)
            val private = packs.rows.single { it.id == PRIVATE }
            assertEquals(PackRestoreAction.PICK_FILE, private.item.action)
            assertEquals(PackStatus.Pending, private.status)
            assertTrue(packs.busy)

            // Busy: continuing is not possible yet.
            viewModel.continueToContacts()
            step<RestoreStep.Packs>()

            gateway.packProgress.emit(CRITTERS to PackProgress.Working(0.5f))
            assertEquals(PackStatus.Working(0.5f), step<RestoreStep.Packs>().rows.single { it.id == CRITTERS }.status)
            gateway.packProgress.emit(CRITTERS to PackProgress.Done)
            assertEquals(PackStatus.Done, step<RestoreStep.Packs>().rows.single { it.id == CRITTERS }.status)

            viewModel.importPackFile(PRIVATE, "content://private")
            assertEquals(listOf(PRIVATE to "content://private"), gateway.importedFiles)
            gateway.packProgress.emit(PRIVATE to PackProgress.Failed(PackFailure.WRONG_FILE))
            val failed = step<RestoreStep.Packs>()
            assertEquals(PackStatus.Failed(PackFailure.WRONG_FILE), failed.rows.single { it.id == PRIVATE }.status)
            assertEquals(1, failed.missing)
            assertFalse(failed.busy)

            // Going on without it: the summary says one pack is missing.
            gateway.reports += BackupTestData.report(applied = listOf("1"))
            viewModel.continueToContacts()
            assertEquals(1, step<RestoreStep.Done>().summary.packsMissing)
        }

    @Test
    fun `a failed download can be retried`() =
        runTest {
            gateway.instantPacks = PackProgress.Failed(PackFailure.DOWNLOAD)
            val document =
                BackupTestData.document(
                    packs = listOf(BackupTestData.pack(CRITTERS, BackupPackSourceType.REPOSITORY)),
                )
            gateway.openOutcomes[null] = OpenOutcome.Opened(FakeSession(document))
            viewModel.open("content://backup")
            assertEquals(PackStatus.Failed(PackFailure.DOWNLOAD), step<RestoreStep.Packs>().rows.single().status)

            gateway.instantPacks = PackProgress.Done
            viewModel.restorePack(CRITTERS)
            assertEquals(PackStatus.Done, step<RestoreStep.Packs>().rows.single().status)
            assertEquals(listOf(CRITTERS, CRITTERS), gateway.restoredPacks)
            assertEquals(0, step<RestoreStep.Packs>().missing)
        }

    @Test
    fun `conflicts keep this phone's voice by default and show names from the installed packs`() =
        runTest {
            val session = FakeSession(BackupTestData.document(contacts = 3))
            gateway.openOutcomes[null] = OpenOutcome.Opened(session)
            gateway.reports +=
                BackupTestData.report(
                    applied = listOf("1"),
                    conflicts =
                        listOf(
                            BackupTestData.conflict("2", "Contact 2"),
                            BackupTestData.conflict(
                                "3",
                                null,
                                current = BackupTestData.voice(null, null, GroupPolicy.GROUP),
                                backup = BackupTestData.voice("app.mauri.gone", "ghost", GroupPolicy.PER_SENDER),
                            ),
                        ),
                )
            viewModel.open("content://backup")

            val conflicts = step<RestoreStep.Conflicts>()
            assertEquals(emptySet<String>(), conflicts.selected)
            assertFalse(conflicts.allSelected)
            val first = conflicts.rows[0]
            assertEquals(VoiceLabel("Bleep the frog", "Chiptune critters", null), first.current)
            assertEquals(VoiceLabel("Hoot the owl", "Chiptune critters", null), first.backup)
            // Not installed: IDs instead of names; the group policy differs, so it is shown.
            val second = conflicts.rows[1]
            assertEquals(VoiceLabel(null, null, GroupPolicy.GROUP), second.current)
            assertEquals(VoiceLabel("ghost", "app.mauri.gone", GroupPolicy.PER_SENDER), second.backup)

            // Keeping everything: no second restore.
            viewModel.applyConflicts()
            val done = step<RestoreStep.Done>()
            assertEquals(listOf(emptySet<String>()), gateway.restoreCalls)
            assertEquals(2, done.summary.conflictsKept)
            assertEquals(1, done.summary.voicesApplied)
        }

    @Test
    fun `ticked conflicts take the backup's voice in a second restore`() =
        runTest {
            gateway.openOutcomes[null] = OpenOutcome.Opened(FakeSession(BackupTestData.document(contacts = 3)))
            val conflicts = listOf(BackupTestData.conflict("2", "Contact 2"), BackupTestData.conflict("3", "Contact 3"))
            gateway.reports +=
                BackupTestData.report(applied = listOf("1"), conflicts = conflicts, created = 1, keysElsewhere = 1)
            gateway.reports += BackupTestData.report(applied = listOf("3"), conflicts = conflicts.take(1))
            viewModel.open("content://backup")

            viewModel.toggleConflict("3")
            assertEquals(setOf("3"), step<RestoreStep.Conflicts>().selected)
            viewModel.selectAllConflicts(true)
            assertTrue(step<RestoreStep.Conflicts>().allSelected)
            viewModel.toggleConflict("2")
            viewModel.toggleConflict("unknown")
            assertEquals(setOf("3"), step<RestoreStep.Conflicts>().selected)

            viewModel.applyConflicts()
            val summary = step<RestoreStep.Done>().summary
            assertEquals(listOf(emptySet(), setOf("3")), gateway.restoreCalls)
            assertEquals(2, summary.voicesApplied)
            assertEquals(1, summary.conflictsKept)
            assertEquals(1, summary.created)
            assertEquals(1, summary.keysLeftAlone)
            assertEquals(1, summary.repositoriesAdded)
        }

    @Test
    fun `selecting none after all brings back the default`() =
        runTest {
            gateway.openOutcomes[null] = OpenOutcome.Opened(FakeSession(BackupTestData.document()))
            gateway.reports += BackupTestData.report(conflicts = listOf(BackupTestData.conflict("1", "Contact 1")))
            viewModel.open("content://backup")
            viewModel.selectAllConflicts(true)
            viewModel.selectAllConflicts(false)
            assertEquals(emptySet<String>(), step<RestoreStep.Conflicts>().selected)
        }

    @Test
    fun `closing deletes the scratch copies and a new file starts over`() =
        runTest {
            val session = FakeSession(BackupTestData.document())
            gateway.openOutcomes[null] = OpenOutcome.Opened(session)
            gateway.reports += BackupTestData.report(conflicts = listOf(BackupTestData.conflict("1", "Contact 1")))
            viewModel.open("content://backup")
            step<RestoreStep.Conflicts>()

            viewModel.close()
            assertEquals(RestoreStep.Closed, viewModel.step.value)
            assertTrue(session.closed)
        }

    @Test
    fun `a failing restore is a problem and nothing else runs`() =
        runTest {
            gateway.openOutcomes[null] = OpenOutcome.Opened(FakeSession(BackupTestData.document()))
            // No report queued: the fake throws.
            viewModel.open("content://backup")
            assertEquals(ProblemKind.FAILED, step<RestoreStep.Problem>().kind)
        }
}
