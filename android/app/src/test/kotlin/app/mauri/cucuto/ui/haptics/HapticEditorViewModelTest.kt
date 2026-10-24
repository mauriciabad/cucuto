package app.mauri.cucuto.ui.haptics

import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.routing.HapticOverride
import app.mauri.cucuto.core.data.voices.VoicePack
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import app.mauri.cucuto.testing.FakeCharacterDirectory
import app.mauri.cucuto.testing.FakeChatHapticOverrides
import app.mauri.cucuto.testing.FakeVoicePreview
import app.mauri.cucuto.testing.TestData.ADA
import app.mauri.cucuto.testing.TestData.BEA
import app.mauri.cucuto.testing.TestData.CRITTERS
import app.mauri.cucuto.testing.TestData.frog
import app.mauri.cucuto.testing.TestData.owl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
import java.io.File
import app.mauri.cucuto.packspec.HapticEvent as PackHapticEvent

@OptIn(ExperimentalCoroutinesApi::class)
class HapticEditorViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val packPattern = Haptic.Custom(listOf(HapticEvent.Preset(0, HapticPreset.LOW_TICK, 900)))
    private val owlWithPattern = owl.copy(haptic = packPattern)
    private val characters =
        FakeCharacterDirectory(listOf(VoicePack(CRITTERS, "Chiptune critters", listOf(frog, owlWithPattern))))
    private val overrides = FakeChatHapticOverrides(mapOf(ADA to frog.voice, BEA to owl.voice))
    private val preview = FakeVoicePreview()
    private val results = HapticEditorResults()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(chat: ChatKey? = ADA) = viewModel(chat?.let(HapticTarget::Chat))

    private fun viewModel(target: HapticTarget?) =
        HapticEditorViewModel(target, overrides, characters, preview, results)

    @Test
    fun startsFromTheStoredPatternElseThePacksElseEmpty() =
        runTest(dispatcher) {
            assertNull(
                viewModel(ADA)
                    .state.value.draft.haptic,
            )
            assertEquals(
                packPattern,
                viewModel(BEA)
                    .state.value.draft.haptic,
            )

            val mine = Haptic.Custom(listOf(HapticEvent.Preset(40, HapticPreset.CLICK, 700)))
            overrides.set(BEA, HapticOverride.Custom(mine))
            val state = viewModel(BEA).state.value
            assertEquals(mine, state.draft.haptic)
            assertFalse(state.loading)
            assertFalse(state.dirty)
            assertEquals("owl", state.character?.id)
        }

    @Test
    fun tappingTheTimelineDropsTheToolOrSelects() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.selectTool(HapticTool.Envelope)
            assertTrue(vm.tapTimeline(atMs = 300, toleranceMs = 20))
            assertEquals(
                HapticDraft.DEFAULT_ENVELOPE.copy(atMs = 300),
                vm.state.value.draft.events
                    .single(),
            )
            assertTrue(vm.state.value.dirty)

            vm.select(null)
            assertTrue(vm.tapTimeline(atMs = 400, toleranceMs = 20))
            assertEquals(0, vm.state.value.draft.selected)
            assertEquals(1, vm.state.value.draft.events.size)

            vm.moveSelected(1000)
            assertEquals(
                1000,
                vm.state.value.draft.events
                    .single()
                    .atMs,
            )
            vm.undo()
            assertEquals(
                300,
                vm.state.value.draft.events
                    .single()
                    .atMs,
            )
        }

    @Test
    fun tapTheRhythmReplacesTheTimeline() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.add(HapticTool.Click, 0)
            vm.startRecording()
            assertTrue(vm.state.value.recording != null)
            // Editing waits until the recording is done.
            assertFalse(vm.add(HapticTool.Tick, 500))

            assertTrue(vm.press(10_000))
            assertTrue(
                vm.state.value.recording!!
                    .pressed,
            )
            vm.release(10_030)
            vm.press(10_300)
            vm.release(10_700)
            assertEquals(
                2,
                vm.state.value.recording!!
                    .events.size,
            )
            assertEquals(
                700,
                vm.state.value.recording!!
                    .elapsedMs,
            )
            assertFalse(vm.state.value.canSave)
            vm.stopRecording(nowMs = 11_000)

            val state = vm.state.value
            assertNull(state.recording)
            assertEquals(listOf(0, 300), state.draft.events.map { it.atMs })
            assertTrue(state.draft.events[1] is HapticEvent.Envelope)
            assertTrue(state.canSave)
            // The click from before comes back with Undo.
            vm.undo()
            assertEquals(
                listOf(HapticEvent.Preset(0, HapticPreset.CLICK, HapticDraft.DEFAULT_CLICK_SCALE)),
                vm.state.value.draft.events,
            )
        }

    @Test
    fun aRecordingWithoutTapsKeepsTheTimeline() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.add(HapticTool.Tick, 100)
            vm.startRecording()
            vm.stopRecording(nowMs = 5_000)
            assertEquals(
                listOf(100),
                vm.state.value.draft.events
                    .map { it.atMs },
            )
            assertFalse(vm.press(6_000))
        }

    @Test
    fun previewsThePatternAloneOrWithAClip() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.preview(withClip = false)
            assertTrue(preview.vibrations.isEmpty())

            vm.add(HapticTool.Click, 0)
            vm.preview(withClip = false)
            val pattern = vm.state.value.draft.haptic!!
            assertEquals(listOf(pattern), preview.vibrations)
            vm.preview(withClip = true)
            assertEquals(listOf("frog" to (pattern as Haptic)), preview.hapticPlays)
            assertEquals(2, vm.state.value.playback)
            assertEquals(HapticTimeline.PRESET_SLOT_MS, vm.state.value.playbackMs)
        }

    @Test
    fun savingStoresACustomOverride() =
        runTest(dispatcher) {
            val vm = viewModel()
            assertFalse(vm.state.value.canSave)
            vm.add(HapticTool.Click, 0)
            vm.add(HapticTool.Tick, 200)
            vm.save()
            val state = vm.state.value
            assertTrue(state.saved)
            assertFalse(state.dirty)
            assertEquals(HapticOverride.Custom(state.draft.haptic!!), overrides.overrideOf(ADA))
        }

    @Test
    fun aFailedSaveSaysSo() =
        runTest(dispatcher) {
            overrides.fail = true
            val vm = viewModel()
            vm.add(HapticTool.Click, 0)
            vm.save()
            assertTrue(vm.state.value.failed)
            assertFalse(vm.state.value.saved)
            vm.dismissError()
            assertFalse(vm.state.value.failed)
        }

    @Test
    fun theScratchPadOnlyPreviews() =
        runTest(dispatcher) {
            val vm = viewModel(chat = null)
            assertFalse(vm.state.value.loading)
            vm.add(HapticTool.Click, 0)
            assertFalse(vm.state.value.canSave)
            assertFalse(vm.state.value.canPreviewWithClip)
            vm.save()
            assertFalse(vm.state.value.saved)
        }

    @Test
    fun aCreatorCharacterStartsFromItsPatternAndHandsTheResultBack() =
        runTest(dispatcher) {
            val target =
                HapticTarget.CreatorCharacter(
                    characterKey = "k1",
                    name = "Kettle",
                    pattern = listOf(PackHapticEvent(at = 40, preset = "TICK", scale = 0.6)),
                    clip = "/data/creator/media/clip-1.ogg",
                )
            val vm = viewModel(target)
            val start = vm.state.value
            assertFalse("nothing to load", start.loading)
            assertFalse(start.needsVoice)
            assertEquals(listOf(HapticEvent.Preset(40, HapticPreset.TICK, 600)), start.draft.events)
            assertEquals("Kettle", start.previewName)

            vm.preview(withClip = true)
            assertEquals(File("/data/creator/media/clip-1.ogg"), preview.clipPlays.single().first)
            assertEquals(start.draft.haptic, preview.clipPlays.single().second)

            vm.add(HapticTool.Click, 300)
            vm.save()
            val saved = vm.state.value
            assertTrue(saved.saved)
            assertFalse(saved.dirty)
            assertEquals(CreatorHapticResult("k1", saved.draft.haptic!!), results.creatorResults.first())
            // Nothing is stored for any chat.
            assertFalse(overrides.overrideOf(ADA) is HapticOverride.Custom)
            assertFalse(overrides.overrideOf(BEA) is HapticOverride.Custom)
        }

    @Test
    fun aNewCreatorPatternStartsEmptyAndHasNoClipWithoutClips() =
        runTest(dispatcher) {
            val vm = viewModel(HapticTarget.CreatorCharacter(characterKey = "k2", name = "Toaster"))
            assertTrue(
                vm.state.value.draft.events
                    .isEmpty(),
            )
            assertFalse(vm.state.value.canSave)
            assertNull(vm.state.value.previewName)
            vm.add(HapticTool.Click, 0)
            assertTrue(vm.state.value.canSave)
            assertFalse(vm.state.value.canPreviewWithClip)
        }
}
