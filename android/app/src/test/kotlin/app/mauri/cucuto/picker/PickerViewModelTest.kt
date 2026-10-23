package app.mauri.cucuto.picker

import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.chats.ChatVoice
import app.mauri.cucuto.testing.FakeCharacterDirectory
import app.mauri.cucuto.testing.FakeChatDirectory
import app.mauri.cucuto.testing.FakeVoicePreview
import app.mauri.cucuto.testing.TestData.ADA
import app.mauri.cucuto.testing.TestData.CRITTERS
import app.mauri.cucuto.testing.TestData.KITCHEN
import app.mauri.cucuto.testing.TestData.chat
import app.mauri.cucuto.testing.TestData.frog
import app.mauri.cucuto.testing.TestData.kettle
import app.mauri.cucuto.testing.TestData.owl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
class PickerViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val directory = FakeChatDirectory(listOf(chat(ADA, "Ada")))
    private val preview = FakeVoicePreview()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(chat: ChatKey? = ADA) = PickerViewModel(chat, directory, FakeCharacterDirectory(), preview)

    /** Keeps [PickerViewModel.state] subscribed, as the screen does. */
    private fun TestScope.collect(vm: PickerViewModel) = backgroundScope.launch { vm.state.collect {} }

    @Test
    fun loadsTheChatAndEveryPack() =
        runTest(dispatcher) {
            val vm = viewModel()
            collect(vm)
            val state = vm.state.value
            assertFalse(state.loading)
            assertEquals("Ada", state.chat?.displayName)
            assertEquals(listOf(CRITTERS, KITCHEN), state.packs.map { it.packId })
            assertTrue(state.canAssign)
            assertEquals("Whistle the kettle", state.character(ChatVoice(KITCHEN, "kettle"))?.name)
        }

    @Test
    fun theFirstTapPreviewsWithTheHapticAndTheSpriteSpeaksForTheClip() =
        runTest(dispatcher) {
            val vm = viewModel()
            collect(vm)

            assertEquals(PickerTap.Preview, vm.tap(frog))

            assertEquals(listOf(FakeVoicePreview.Play("frog", withHaptic = true, atNanos = null)), preview.plays)
            assertEquals(frog.voice, vm.state.value.selected)
            assertEquals(frog.voice, vm.state.value.speaking)
            advanceTimeBy(600)
            runCurrent()
            assertNull(vm.state.value.speaking)
            assertTrue(directory.assigned.isEmpty())
        }

    @Test
    fun tappingAnotherCharacterMovesTheSelection() =
        runTest(dispatcher) {
            val vm = viewModel()
            collect(vm)
            vm.tap(frog)
            assertEquals(PickerTap.Preview, vm.tap(owl))
            assertEquals(owl.voice, vm.state.value.selected)
            assertEquals(listOf("frog", "owl"), preview.plays.map { it.characterId })
        }

    @Test
    fun theSecondTapAssignsAndTheClipWaitsForTheBeat() =
        runTest(dispatcher) {
            val vm = viewModel()
            collect(vm)
            vm.tap(kettle)

            assertEquals(PickerTap.Assign, vm.tap(kettle))
            vm.assign(kettle, clipAtNanos = 42L)

            assertEquals(listOf(Triple(ADA, KITCHEN, "kettle")), directory.assigned)
            assertEquals(FakeVoicePreview.Play("kettle", withHaptic = false, atNanos = 42L), preview.plays.last())
            val state = vm.state.value
            assertEquals(kettle.voice, state.assigned)
            assertEquals(kettle.voice, state.currentVoice)
            assertEquals(
                kettle.voice,
                directory.chats
                    .first()
                    .single()
                    .voice,
            )
            // Once assigned, taps only preview: the screen is closing.
            assertEquals(PickerTap.Preview, vm.tap(kettle))
            vm.assign(owl, null)
            assertEquals(1, directory.assigned.size)
        }

    @Test
    fun aFailedAssignmentIsReportedAndCanBeRetried() =
        runTest(dispatcher) {
            directory.failAssign = true
            val vm = viewModel()
            collect(vm)
            vm.tap(frog)
            vm.assign(frog, null)

            assertTrue(vm.state.value.failed)
            assertNull(vm.state.value.assigned)

            directory.failAssign = false
            assertEquals(PickerTap.Assign, vm.tap(frog))
            vm.assign(frog, null)
            assertFalse(vm.state.value.failed)
            assertEquals(1, directory.assigned.size)
        }

    @Test
    fun withoutAChatTheGridOnlyPreviews() =
        runTest(dispatcher) {
            val vm = viewModel(chat = null)
            collect(vm)
            assertFalse(vm.state.value.canAssign)
            assertEquals(PickerTap.Preview, vm.tap(frog))
            assertEquals(PickerTap.Preview, vm.tap(frog))
            vm.assign(frog, null)
            assertTrue(directory.assigned.isEmpty())
            assertEquals(2, preview.plays.size)
        }

    @Test
    fun aCharacterWithNothingToPlayDoesNotSpeak() =
        runTest(dispatcher) {
            preview.durationMs = null
            val vm = viewModel()
            collect(vm)
            vm.tap(frog)
            assertEquals(frog.voice, vm.state.value.selected)
            assertNull(vm.state.value.speaking)
        }

    @Test
    fun unassignRemovesTheVoice() =
        runTest(dispatcher) {
            directory.chats.value = listOf(chat(ADA, "Ada", ChatVoice(CRITTERS, "frog")))
            val vm = viewModel()
            collect(vm)
            assertEquals(frog.voice, vm.state.value.currentVoice)

            vm.unassign()

            assertEquals(listOf(ADA), directory.unassigned)
            assertNull(vm.state.value.currentVoice)
        }
}
