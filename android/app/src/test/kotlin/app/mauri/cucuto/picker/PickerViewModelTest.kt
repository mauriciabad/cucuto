package app.mauri.cucuto.picker

import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.chats.ChatVoice
import app.mauri.cucuto.core.data.settings.PickerKind
import app.mauri.cucuto.core.data.settings.RememberedFolds
import app.mauri.cucuto.core.data.voices.VoicePack
import app.mauri.cucuto.testing.FakeCharacterDirectory
import app.mauri.cucuto.testing.FakeChatDirectory
import app.mauri.cucuto.testing.FakePickerFoldSettings
import app.mauri.cucuto.testing.FakeVoicePreview
import app.mauri.cucuto.testing.TestData
import app.mauri.cucuto.testing.TestData.ADA
import app.mauri.cucuto.testing.TestData.CRITTERS
import app.mauri.cucuto.testing.TestData.KITCHEN
import app.mauri.cucuto.testing.TestData.character
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PickerViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val directory = FakeChatDirectory(listOf(chat(ADA, "Ada")))
    private val preview = FakeVoicePreview()
    private val folds = FakePickerFoldSettings()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        chat: ChatKey? = ADA,
        packs: List<VoicePack> = TestData.packs,
    ) = PickerViewModel(chat, directory, FakeCharacterDirectory(packs), preview, folds)

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

    /** A large pack (folded by default) with a ringtone-only song that must never speak for messages. */
    private val bigPacks =
        TestData.packs +
            VoicePack(
                "app.mauri.test.creatures",
                "Pocket creatures",
                List(20) { character("c$it", "Creature $it", packId = "app.mauri.test.creatures") } +
                    character("song", "Only a song", packId = "app.mauri.test.creatures", clips = 0)
                        .copy(ringtoneCount = 1),
            )

    @Test
    fun largePacksStartFoldedAndAFoldIsRememberedForNextTime() =
        runTest(dispatcher) {
            val vm = viewModel(packs = bigPacks)
            collect(vm)
            val creatures = "app.mauri.test.creatures"
            assertEquals(
                listOf(true, true, false),
                vm.state.value.sections
                    .map { it.expanded },
            )
            assertEquals(
                20,
                vm.state.value.sections
                    .last()
                    .total,
            )

            vm.toggleFold(creatures)
            vm.toggleFold(CRITTERS)

            assertEquals(
                listOf(false, true, true),
                vm.state.value.sections
                    .map { it.expanded },
            )
            assertEquals(
                RememberedFolds(expanded = setOf(creatures), folded = setOf(CRITTERS)),
                folds.folds.value[PickerKind.Voice],
            )
            // A new visit opens as the user left it.
            val again = viewModel(packs = bigPacks)
            collect(again)
            assertEquals(
                listOf(false, true, true),
                again.state.value.sections
                    .map { it.expanded },
            )
        }

    @Test
    fun theChatsCurrentPackOpensUnfolded() =
        runTest(dispatcher) {
            val creature = ChatVoice("app.mauri.test.creatures", "c3")
            directory.chats.value = listOf(chat(ADA, "Ada", voice = creature))
            folds.remember(PickerKind.Voice, creature.packId, expanded = false)
            val vm = viewModel(packs = bigPacks)
            collect(vm)
            assertTrue(
                vm.state.value.sections
                    .last()
                    .expanded,
            )
        }

    @Test
    fun searchFindsCharactersInAFoldedPack() =
        runTest(dispatcher) {
            val vm = viewModel(packs = bigPacks)
            collect(vm)

            vm.search("creature 1")

            val sections = vm.state.value.sections
            assertEquals(listOf("app.mauri.test.creatures"), sections.map { it.packId })
            assertEquals(listOf("Creature 1") + List(10) { "Creature 1$it" }, sections.single().shown.map { it.name })
            vm.search("")
            assertFalse(
                vm.state.value.sections
                    .last()
                    .expanded,
            )
        }

    @Test
    fun surpriseFromAPackPicksOnlyCharactersWithClipsAndNeverRepeats() =
        runTest(dispatcher) {
            val vm = viewModel(packs = bigPacks)
            collect(vm)
            var previous: ChatVoice? = null
            repeat(60) {
                val pick = vm.surprise("app.mauri.test.creatures", atNanos = 7L)
                assertEquals("app.mauri.test.creatures", pick?.packId)
                assertNotEquals("song", pick?.id)
                assertNotEquals(previous, pick?.voice)
                assertEquals(pick?.voice, vm.state.value.selected)
                assertTrue(vm.state.value.rolled)
                previous = pick?.voice
            }
            assertEquals(
                60,
                vm.state.value.roll
                    ?.count,
            )
            assertTrue(preview.plays.all { it.withHaptic && it.atNanos == 7L })
            assertTrue(directory.assigned.isEmpty())
        }

    @Test
    fun surpriseFromEveryPackSkipsTheCurrentVoiceAndShowsThePick() =
        runTest(dispatcher) {
            directory.chats.value = listOf(chat(ADA, "Ada", voice = frog.voice))
            val vm = viewModel()
            collect(vm)
            vm.filterPack(KITCHEN)
            vm.search("whistle")
            val seen = mutableSetOf<String>()
            repeat(40) {
                val pick = vm.surprise(null)!!
                seen += pick.id
                val state = vm.state.value
                assertEquals("", state.query)
                assertTrue(state.packFilter == null || state.packFilter == pick.packId)
                assertTrue(
                    state.sections
                        .first { it.packId == pick.packId }
                        .shown
                        .any { it.voice == pick.voice },
                )
            }
            assertEquals(setOf("frog", "owl", "kettle"), seen)
            // The very first roll never lands on the voice the chat already has: covered by never repeating.
        }

    @Test
    fun aRolledCharacterIsAssignedLikeASecondTap() =
        runTest(dispatcher) {
            val vm = viewModel()
            collect(vm)
            val pick = vm.surprise(KITCHEN)!!
            assertEquals(kettle.voice, pick.voice)

            assertEquals(PickerTap.Assign, vm.tap(pick))
            vm.assign(pick, clipAtNanos = null)

            assertEquals(listOf(Triple(ADA, KITCHEN, "kettle")), directory.assigned)
            assertFalse(vm.state.value.rolled)
            assertNull(vm.surprise(null))
        }
}
