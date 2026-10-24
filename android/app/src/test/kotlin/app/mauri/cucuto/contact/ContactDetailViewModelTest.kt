package app.mauri.cucuto.contact

import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.chats.ChatVoice
import app.mauri.cucuto.core.data.db.DecisionLogEntity
import app.mauri.cucuto.core.data.db.DecisionOutcome
import app.mauri.cucuto.core.data.db.GroupPolicy
import app.mauri.cucuto.core.data.voices.VoicePack
import app.mauri.cucuto.testing.FakeCharacterDirectory
import app.mauri.cucuto.testing.FakeChatDirectory
import app.mauri.cucuto.testing.FakeVoicePreview
import app.mauri.cucuto.testing.TestData
import app.mauri.cucuto.testing.TestData.ADA
import app.mauri.cucuto.testing.TestData.CLUB
import app.mauri.cucuto.testing.TestData.CRITTERS
import app.mauri.cucuto.testing.TestData.chat
import app.mauri.cucuto.xposed.events.Outcome
import app.mauri.cucuto.xposed.route.Targets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContactDetailViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val frog =
        TestData.frog.copy(
            clipPaths = listOf("characters/frog/clips/0.ogg", "characters/frog/clips/1.ogg"),
        )
    private val characters =
        FakeCharacterDirectory(listOf(VoicePack(CRITTERS, "Chiptune critters", listOf(frog, TestData.owl))))
    private val directory =
        FakeChatDirectory(
            listOf(
                chat(ADA, "Ada", ChatVoice(CRITTERS, "frog"), contactId = 7),
                chat(CLUB, "Book club", ChatVoice(CRITTERS, "owl"), contactId = 9),
            ),
        )
    private val source = FakeContactDetailSource()
    private val simulator = FakeMessageSimulator()
    private val preview = FakeVoicePreview()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(chat: ChatKey = ADA) =
        ContactDetailViewModel(chat, directory, characters, source, simulator, preview)

    private fun TestScope.collect(vm: ContactDetailViewModel) = backgroundScope.launch { vm.state.collect {} }

    @Test
    fun loadsTheCharacterClipsSettingsAndPath() =
        runTest(dispatcher) {
            source.settings.value = mapOf(7L to ChatSettings(GroupPolicy.GROUP, listOf(frog.clipPaths[1]), null))
            val vm = viewModel()
            collect(vm)

            val state = vm.state.value
            assertFalse(state.loading)
            assertEquals("Bleep the frog", state.character?.name)
            assertEquals("Chiptune critters", state.packName)
            assertEquals(listOf(true, false), state.clips.map { it.enabled })
            assertEquals(HapticSource.Character, state.hapticSource)
            assertNull(state.groupPolicy)
            assertEquals(SimulatePath.InApp(InAppReason.RebootNeeded), state.path)
            assertTrue(state.canSimulate)
        }

    @Test
    fun groupsShowTheirPolicy() =
        runTest(dispatcher) {
            source.settings.value = mapOf(9L to ChatSettings(GroupPolicy.PER_SENDER, emptyList(), null))
            val vm = viewModel(CLUB)
            collect(vm)
            assertEquals(GroupPolicy.PER_SENDER, vm.state.value.groupPolicy)
        }

    @Test
    fun simulatingThroughTheModulePostsAndPicksUpItsReport() =
        runTest(dispatcher) {
            simulator.path = SimulatePath.Module
            val vm = viewModel()
            collect(vm)

            vm.simulate("Simulated message from Ada")

            assertEquals(listOf(ADA to "Simulated message from Ada"), simulator.posted)
            assertEquals(SimulationStatus.Sent(SENT_AT), vm.state.value.simulation)
            assertNull(vm.state.value.report)

            // The module's decision arrives through the event provider into the decision log.
            source.log.value =
                listOf(
                    DecisionLogEntity(
                        id = 1,
                        timestamp = SENT_AT + 80,
                        packageName = Targets.SELF_PACKAGE,
                        conversationKey = ADA.conversationKey,
                        contactId = 7,
                        outcome = DecisionOutcome.SWAPPED,
                        clipUri = "content://app.mauri.cucuto.clips/$CRITTERS/frog/characters/frog/clips/1.ogg",
                        detail = "simulated",
                    ),
                )
            val report = vm.state.value.report!!
            assertEquals("1", report.clipName)
            assertTrue(report.simulated)
            assertEquals(1, vm.state.value.decisions.size)
        }

    @Test
    fun simulatingInTheAppPlaysWhatTheRouterChose() =
        runTest(dispatcher) {
            val vm = viewModel()
            collect(vm)

            vm.simulate("title")

            assertEquals(listOf(ADA), simulator.inApp)
            assertEquals(SimulationStatus.PlayedInApp("0", HapticPlayback.WhatsApp), vm.state.value.simulation)
            assertTrue(simulator.posted.isEmpty())
        }

    @Test
    fun simulationResultsAreReported() =
        runTest(dispatcher) {
            val vm = viewModel()
            collect(vm)

            simulator.inAppResult = InAppSimulation.WhatsAppTone(Outcome.DISABLED)
            vm.simulate("title")
            assertEquals(SimulationStatus.WhatsAppTone(Outcome.DISABLED), vm.state.value.simulation)

            simulator.path = SimulatePath.Module
            simulator.moduleResult = ModuleSimulation.TooSoon(7)
            vm.simulate("title")
            assertEquals(SimulationStatus.TooSoon(7), vm.state.value.simulation)

            simulator.moduleResult = ModuleSimulation.Blocked
            vm.simulate("title")
            assertEquals(SimulationStatus.Blocked, vm.state.value.simulation)
        }

    @Test
    fun theScreenRechecksThePathOnResume() =
        runTest(dispatcher) {
            val vm = viewModel()
            collect(vm)
            simulator.path = SimulatePath.Module
            vm.refreshPath()
            assertEquals(SimulatePath.Module, vm.state.value.path)
        }

    @Test
    fun clipsPlayAloneAndToggleButTheLastOneStaysOn() =
        runTest(dispatcher) {
            source.settings.value = mapOf(7L to ChatSettings(GroupPolicy.GROUP, emptyList(), null))
            val vm = viewModel()
            collect(vm)
            val (first, second) = vm.state.value.clips

            vm.playClip(first)
            assertEquals(listOf(first.file to null), preview.clipPlays)

            vm.setClipEnabled(first, false)
            assertEquals(listOf(Triple(7L, first.path, false)), source.toggles)

            val last = vm.state.value.clips[1]
            assertEquals(second.path, last.path)
            assertFalse(last.canToggle)
            vm.setClipEnabled(last, false)
            assertEquals(1, source.toggles.size)
        }

    @Test
    fun removingTheVoiceUnassignsTheChat() =
        runTest(dispatcher) {
            val vm = viewModel()
            collect(vm)
            vm.unassign()
            assertEquals(listOf(ADA), directory.unassigned)
            assertNull(vm.state.value.character)
            assertFalse(vm.state.value.canSimulate)
        }

    @Test
    fun aFailedWriteIsShownOnce() =
        runTest(dispatcher) {
            source.settings.value = mapOf(7L to ChatSettings(GroupPolicy.GROUP, emptyList(), null))
            source.fail = true
            val vm = viewModel()
            collect(vm)
            vm.setClipEnabled(
                vm.state.value.clips
                    .first(),
                false,
            )
            assertTrue(vm.state.value.failed)
            vm.clearFailure()
            assertFalse(vm.state.value.failed)
        }

    private companion object {
        const val SENT_AT = 1_761_000_000_000L
    }

    private class FakeContactDetailSource : ContactDetailSource {
        val settings = MutableStateFlow<Map<Long, ChatSettings>>(emptyMap())
        val log = MutableStateFlow<List<DecisionLogEntity>>(emptyList())
        val toggles = mutableListOf<Triple<Long, String, Boolean>>()
        var fail = false

        override fun observeSettings(contactId: Long): Flow<ChatSettings?> = settings.map { it[contactId] }

        override fun observeDecisions(): Flow<List<DecisionLogEntity>> = log

        override suspend fun setClipEnabled(
            contactId: Long,
            clipPath: String,
            enabled: Boolean,
        ) {
            if (fail) error("disk full")
            toggles += Triple(contactId, clipPath, enabled)
            settings.update { all ->
                val current = all[contactId] ?: return@update all
                val disabled = if (enabled) current.disabledClips - clipPath else current.disabledClips + clipPath
                all + (contactId to current.copy(disabledClips = disabled))
            }
        }
    }

    private class FakeMessageSimulator : MessageSimulator {
        var path: SimulatePath = SimulatePath.InApp(InAppReason.RebootNeeded)
        var moduleResult: ModuleSimulation = ModuleSimulation.Posted(SENT_AT)
        var inAppResult: InAppSimulation = InAppSimulation.Played("0", HapticPlayback.WhatsApp, 400)
        val posted = mutableListOf<Pair<ChatKey, String>>()
        val inApp = mutableListOf<ChatKey>()

        override suspend fun path(): SimulatePath = path

        override suspend fun throughModule(
            chat: ChatKey,
            title: String,
        ): ModuleSimulation {
            posted += chat to title
            return moduleResult
        }

        override suspend fun inApp(chat: ChatKey): InAppSimulation {
            inApp += chat
            return inAppResult
        }

        override fun stop() = Unit
    }
}
