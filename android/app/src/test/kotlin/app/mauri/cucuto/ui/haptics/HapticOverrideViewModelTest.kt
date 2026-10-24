package app.mauri.cucuto.ui.haptics

import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.routing.HapticOverride
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import app.mauri.cucuto.testing.FakeCharacterDirectory
import app.mauri.cucuto.testing.FakeChatHapticOverrides
import app.mauri.cucuto.testing.FakeVoicePreview
import app.mauri.cucuto.testing.TestData.ADA
import app.mauri.cucuto.testing.TestData.BEA
import app.mauri.cucuto.testing.TestData.frog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class HapticOverrideViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val overrides = FakeChatHapticOverrides(mapOf(ADA to frog.voice))
    private val preview = FakeVoicePreview()
    private val pattern = Haptic.Custom(listOf(HapticEvent.Preset(0, HapticPreset.CLICK, 800)))

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(chat: ChatKey = ADA) =
        HapticOverrideViewModel(chat, overrides, FakeCharacterDirectory(), preview).also { vm ->
            backgroundScope.launch { vm.state.collect {} }
        }

    @Test
    fun aChatWithoutAVoiceHasNothingToChoose() =
        runTest(dispatcher) {
            val state = viewModel(BEA).state.value
            assertFalse(state.loading)
            assertFalse(state.hasVoice)
            assertNull(state.character)
        }

    @Test
    fun startsWithThePacksHaptic() =
        runTest(dispatcher) {
            val state = viewModel().state.value
            assertTrue(state.hasVoice)
            assertEquals(HapticChoice.Pack, state.choice)
            assertEquals("frog", state.character?.id)
            assertEquals(frog.haptic, state.effective)
        }

    @Test
    fun choicesAreStoredForTheRoutingTable() =
        runTest(dispatcher) {
            val vm = viewModel()
            assertEquals(HapticChoiceResult.Stored, vm.choose(HapticChoice.Off))
            assertEquals(HapticOverride.Off, overrides.overrideOf(ADA))
            assertEquals(HapticChoice.Off, vm.state.value.choice)
            assertEquals(Haptic.Off, vm.state.value.effective)

            vm.choose(HapticChoice.Auto)
            assertEquals(HapticOverride.Auto, overrides.overrideOf(ADA))
            vm.choose(HapticChoice.Pack)
            assertEquals(HapticOverride.PackDefault, overrides.overrideOf(ADA))
        }

    @Test
    fun customWithoutAPatternOpensTheEditor() =
        runTest(dispatcher) {
            val vm = viewModel()
            assertEquals(HapticChoiceResult.OpenEditor, vm.choose(HapticChoice.Custom))
            assertEquals(HapticOverride.PackDefault, overrides.overrideOf(ADA))
        }

    @Test
    fun switchingAwayFromCustomAndBackKeepsThePattern() =
        runTest(dispatcher) {
            overrides.set(ADA, HapticOverride.Custom(pattern))
            val vm = viewModel()
            assertEquals(HapticChoice.Custom, vm.state.value.choice)
            assertEquals(pattern, vm.state.value.effective)

            vm.choose(HapticChoice.Auto)
            assertEquals(pattern, vm.state.value.custom)
            assertEquals(HapticChoiceResult.Stored, vm.choose(HapticChoice.Custom))
            assertEquals(HapticOverride.Custom(pattern), overrides.overrideOf(ADA))
        }

    @Test
    fun previewPlaysAClipWithTheChosenVibration() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.choose(HapticChoice.Auto)
            vm.preview()
            vm.choose(HapticChoice.Off)
            vm.preview()
            assertEquals(listOf("frog" to Haptic.Auto, "frog" to Haptic.Off), preview.hapticPlays)
        }

    @Test
    fun aFailedChoiceSaysSo() =
        runTest(dispatcher) {
            val vm = viewModel()
            overrides.fail = true
            vm.choose(HapticChoice.Off)
            assertTrue(vm.state.value.failed)
            vm.dismissError()
            assertFalse(vm.state.value.failed)
        }
}
