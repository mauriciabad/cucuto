package app.mauri.cucuto.ui.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.mauri.cucuto.backup.AutoBackupControl
import app.mauri.cucuto.backup.AutoBackupResult
import app.mauri.cucuto.backup.ExportOutcome
import app.mauri.cucuto.backup.FakeBackupGateway
import app.mauri.cucuto.core.data.settings.AutoBackupState
import app.mauri.cucuto.core.data.settings.RoutingSettings
import app.mauri.cucuto.core.data.settings.UiFeedbackSettings
import app.mauri.cucuto.core.data.settings.UiSoundLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val gateway = FakeBackupGateway()
    private val autoBackup = FakeAutoBackup()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class Fixture(
        val viewModel: SettingsViewModel,
        val routing: RoutingSettings,
        val feedback: UiFeedbackSettings,
    ) {
        /** DataStore reads and writes run on a real IO thread: wait for them in real time. */
        suspend fun await(predicate: (SettingsState) -> Boolean): SettingsState =
            withContext(Dispatchers.Default) { withTimeout(10_000) { viewModel.state.first(predicate) } }
    }

    private fun TestScope.fixture(): Fixture {
        val folder = tmp.newFolder()
        val store =
            PreferenceDataStoreFactory.create(
                scope = backgroundScope,
            ) { File(folder, "settings.preferences_pb") }
        val routing = RoutingSettings(store)
        val feedback = UiFeedbackSettings(store)
        val viewModel = SettingsViewModel(routing, feedback, autoBackup, gateway)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
        return Fixture(viewModel, routing, feedback)
    }

    @Test
    fun `the switches save to the same settings the rest of the app reads`() =
        runTest {
            val f = fixture()
            val defaults = f.viewModel.state.value
            assertTrue(defaults.enabled)
            assertEquals(UiSoundLevel.Quiet, defaults.soundLevel)
            assertTrue(defaults.haptics)
            assertFalse(defaults.removeAnimations)

            f.viewModel.setEnabled(false)
            f.viewModel.setSoundLevel(UiSoundLevel.Off)
            f.viewModel.setHaptics(false)
            f.viewModel.setRemoveAnimations(true)
            val state =
                f.await {
                    it.removeAnimations && !it.haptics && !it.enabled && it.soundLevel == UiSoundLevel.Off
                }

            assertFalse(f.routing.enabled.first())
            assertEquals(UiSoundLevel.Off, f.feedback.soundLevel.first())
            assertFalse(f.feedback.hapticsEnabled.first())
            assertTrue(f.feedback.removeAnimations.first())
            assertEquals(
                SettingsState(enabled = false, soundLevel = UiSoundLevel.Off, haptics = false, removeAnimations = true),
                state,
            )
        }

    @Test
    fun `an export waits for the picker, then runs once and wipes the passphrase`() =
        runTest {
            val f = fixture()
            gateway.exportOutcome =
                ExportOutcome.Written(encrypted = true, contacts = 3, includedPacks = 1, skippedPacks = emptyList())
            val form =
                ExportForm(
                    includePacks = true,
                    encrypt = true,
                    passphrase = "long enough",
                    confirmation = "long enough",
                    includeCredentials = true,
                )
            assertTrue(f.viewModel.prepareExport(form))
            assertTrue(gateway.exports.isEmpty())

            f.viewModel.exportTo("content://doc")
            advanceUntilIdle()
            val (destination, request) = gateway.exports.single()
            assertEquals("content://doc", destination)
            assertTrue(request.includePacks)
            assertTrue(request.includeCredentials)
            assertEquals(listOf<String?>("long enough"), gateway.exportedPassphrases)
            assertTrue("wiped after the export", request.passphrase!!.all { it == '\u0000' })
            assertEquals(
                BackupMessage.Exported(gateway.exportOutcome as ExportOutcome.Written),
                f.viewModel.message.value,
            )

            f.viewModel.messageShown()
            assertNull(f.viewModel.message.value)
            // The request was used up: a second picker result does nothing.
            f.viewModel.exportTo("content://other")
            advanceUntilIdle()
            assertEquals(1, gateway.exports.size)
        }

    @Test
    fun `a cancelled picker exports nothing and an invalid form never opens it`() =
        runTest {
            val f = fixture()
            assertFalse(
                f.viewModel.prepareExport(ExportForm(encrypt = true, passphrase = "short", confirmation = "short")),
            )
            assertTrue(f.viewModel.prepareExport(ExportForm()))
            f.viewModel.exportTo(null)
            advanceUntilIdle()
            assertTrue(gateway.exports.isEmpty())
            assertNull(f.viewModel.message.value)
        }

    @Test
    fun `a failed export is reported`() =
        runTest {
            val f = fixture()
            gateway.exportOutcome = ExportOutcome.Failed("disk full")
            f.viewModel.prepareExport(ExportForm())
            f.viewModel.exportTo("content://doc")
            advanceUntilIdle()
            assertEquals(BackupMessage.ExportFailed("disk full"), f.viewModel.message.value)
            assertFalse(f.viewModel.state.value.backupBusy)
        }

    @Test
    fun `automatic backups follow the folder choice`() =
        runTest {
            val f = fixture()
            f.viewModel.chooseFolder(null)
            assertTrue(autoBackup.chosen.isEmpty())

            f.viewModel.chooseFolder("content://tree/backups")
            f.await { it.autoBackup.enabled }
            assertEquals(listOf("content://tree/backups"), autoBackup.chosen)

            f.viewModel.setAutoIncludePacks(false)
            f.await { !it.autoBackup.includePacks }

            f.viewModel.backUpNow()
            advanceUntilIdle()
            assertEquals(
                BackupMessage.AutoResult(AutoBackupResult.Written("cucuto-backup-2026-10-24.zip")),
                f.viewModel.message.value,
            )

            f.viewModel.turnOffAutoBackup()
            f.await { !it.autoBackup.enabled }
        }

    @Test
    fun `a folder Cucuto cannot keep is reported`() =
        runTest {
            val f = fixture()
            autoBackup.failChoose = true
            f.viewModel.chooseFolder("content://tree/readonly")
            advanceUntilIdle()
            assertEquals(BackupMessage.FolderFailed, f.viewModel.message.value)
            assertFalse(autoBackup.state.value.enabled)
        }
}

class ExportFormTest {
    @Test
    fun `a plain backup needs nothing`() {
        val request = ExportForm(includePacks = true, includeCredentials = true).toRequest()!!
        assertTrue(request.includePacks)
        assertNull(request.passphrase)
        assertFalse("credentials only travel encrypted", request.includeCredentials)
    }

    @Test
    fun `an encrypted backup needs a long enough passphrase typed twice`() {
        val short = ExportForm(encrypt = true, passphrase = "1234567", confirmation = "1234567")
        assertTrue(short.passphraseTooShort)
        assertFalse(short.canExport)
        assertNull(short.toRequest())

        val mismatch = ExportForm(encrypt = true, passphrase = "12345678", confirmation = "12345679")
        assertTrue(mismatch.mismatch)
        assertFalse(mismatch.canExport)

        val pending = ExportForm(encrypt = true, passphrase = "12345678")
        assertFalse("no error before the confirmation is typed", pending.mismatch)
        assertFalse(pending.canExport)

        val ok =
            ExportForm(encrypt = true, passphrase = "12345678", confirmation = "12345678", includeCredentials = true)
        val request = ok.toRequest()!!
        assertEquals("12345678", request.passphrase!!.concatToString())
        assertTrue(request.includeCredentials)
    }

    @Test
    fun `the passphrase never shows up in logs`() {
        val form = ExportForm(encrypt = true, passphrase = "secret phrase", confirmation = "secret phrase")
        assertFalse(form.toString().contains("secret"))
        assertFalse(form.toRequest().toString().contains("secret"))
    }
}

class FakeAutoBackup : AutoBackupControl {
    override val state = MutableStateFlow(AutoBackupState())
    val chosen = mutableListOf<String>()
    var failChoose = false

    override suspend fun choose(treeUri: String) {
        if (failChoose) throw SecurityException("No persistable permission")
        chosen += treeUri
        state.value = state.value.copy(folderUri = treeUri, folderName = "backups")
    }

    override suspend fun turnOff() {
        state.value = state.value.copy(folderUri = null, folderName = null)
    }

    override suspend fun setIncludePacks(include: Boolean) {
        state.value = state.value.copy(includePacks = include)
    }

    override suspend fun backUpNow(): AutoBackupResult = AutoBackupResult.Written("cucuto-backup-2026-10-24.zip")
}
