package app.mauri.cucuto.sample

import app.mauri.cucuto.core.data.chats.ChatVoice
import app.mauri.cucuto.core.data.di.WallClock
import app.mauri.cucuto.core.data.settings.SampleChatChoice
import app.mauri.cucuto.core.data.settings.SampleChatStore
import app.mauri.cucuto.core.data.voices.VoiceCharacter
import app.mauri.cucuto.core.ringtone.variants.RingtoneVariant
import app.mauri.cucuto.core.ringtone.variants.RuleContext
import app.mauri.cucuto.packspec.LoopRange
import app.mauri.cucuto.packspec.When
import app.mauri.cucuto.testing.FakeCharacterDirectory
import app.mauri.cucuto.testing.FakeVoicePreview
import app.mauri.cucuto.testing.TestData.CRITTERS
import app.mauri.cucuto.testing.TestData.KITCHEN
import app.mauri.cucuto.testing.TestData.frog
import app.mauri.cucuto.testing.TestData.kettle
import app.mauri.cucuto.testing.TestData.owl
import app.mauri.cucuto.ui.discover.DemoPacksRequests
import app.mauri.cucuto.ui.ringtone.RingSound
import app.mauri.cucuto.ui.ringtone.RingtoneCharacterItem
import app.mauri.cucuto.ui.ringtone.RingtonePackItem
import app.mauri.cucuto.ui.ringtone.RingtonePreview
import app.mauri.cucuto.ui.ringtone.RingtoneVariantItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class SampleChatViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val store = FakeStore()
    private val preview = FakeVoicePreview()
    private val ringer = FakeRinger()
    private val characters = FakeCharacterDirectory()
    private val demoPacks = DemoPacksRequests()

    /** Bleep the frog sings a day and a night version; the kettle has one whistle. */
    private val frogRingtone = ringtoneItem(frog, "day", "night")
    private val kettleRingtone = ringtoneItem(kettle, "whistle")
    private val ringtones =
        MutableStateFlow(
            listOf(
                RingtonePackItem(CRITTERS, "Chiptune critters", listOf(frogRingtone)),
                RingtonePackItem(KITCHEN, "Kitchen orchestra", listOf(kettleRingtone)),
            ),
        )

    /** Noon on a test day: the pack's day rule applies. */
    private val noon =
        WallClock { ZonedDateTime.of(2026, 10, 24, 12, 0, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli() }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = SampleChatViewModel(characters, { ringtones }, store, preview, ringer, noon, demoPacks)

    private fun TestScope.collect(vm: SampleChatViewModel) = backgroundScope.launch { vm.state.collect {} }

    @Test
    fun startsWithNoVoiceAndEveryMessageCharacter() =
        runTest(dispatcher) {
            val vm = viewModel()
            collect(vm)
            val state = vm.state.value
            assertFalse(state.loading)
            assertTrue(state.hasCharacters)
            assertNull(state.character)
            assertNull(state.ringtone)
            assertEquals(listOf(CRITTERS, KITCHEN), state.packs.map { it.packId })
        }

    @Test
    fun choosingAVoiceRemembersItAndPlaysItWithItsVibration() =
        runTest(dispatcher) {
            val vm = viewModel()
            collect(vm)

            vm.chooseVoice(frog.voice)

            assertEquals(frog.voice, store.state.value.voice)
            assertEquals(frog, vm.state.value.character)
            assertEquals(listOf(FakeVoicePreview.Play("frog", withHaptic = true, atNanos = null)), preview.plays)
            assertTrue(vm.state.value.speaking)
            advanceTimeBy(600)
            runCurrent()
            assertFalse(vm.state.value.speaking)
        }

    @Test
    fun playASampleMessagePlaysTheChosenCharacterInTheApp() =
        runTest(dispatcher) {
            store.state.value = SampleChatChoice(voice = owl.voice)
            val vm = viewModel()
            collect(vm)

            vm.playMessage()

            assertEquals(listOf("owl"), preview.plays.map { it.characterId })
            assertTrue(preview.plays.single().withHaptic)
        }

    @Test
    fun withoutAVoiceThereIsNothingToPlay() =
        runTest(dispatcher) {
            val vm = viewModel()
            collect(vm)
            vm.playMessage()
            vm.toggleCall()
            assertTrue(preview.plays.isEmpty())
            assertTrue(ringer.sounds.isEmpty())
        }

    @Test
    fun aClipThatCannotPlayIsReported() =
        runTest(dispatcher) {
            preview.durationMs = null
            store.state.value = SampleChatChoice(voice = frog.voice)
            val vm = viewModel()
            collect(vm)
            vm.playMessage()
            assertTrue(vm.state.value.playFailed)
            assertFalse(vm.state.value.speaking)
        }

    @Test
    fun theRingtoneFollowsTheVoiceAndRingsThePacksRuleForNow() =
        runTest(dispatcher) {
            store.state.value = SampleChatChoice(voice = frog.voice)
            val vm = viewModel()
            collect(vm)
            assertTrue(vm.state.value.ringtoneFollowsVoice)

            vm.toggleCall()

            assertEquals(listOf("frog/day.ogg"), ringer.sounds.map { it.file.path.removePrefix("/packs/") })
            assertEquals("sample-call", vm.ringing.value?.key)

            vm.toggleCall()
            assertNull(vm.ringing.value)
            assertTrue(ringer.stops > 0)
        }

    @Test
    fun aPickedRingtoneWinsAndSameAsTheVoiceGoesBack() =
        runTest(dispatcher) {
            store.state.value = SampleChatChoice(voice = frog.voice)
            val vm = viewModel()
            collect(vm)

            vm.chooseRingtone(kettle.voice)
            assertEquals(kettle.voice, store.state.value.ringtone)
            assertFalse(vm.state.value.ringtoneFollowsVoice)
            vm.toggleCall()
            assertEquals(
                "kettle/whistle.ogg",
                ringer.sounds
                    .last()
                    .file.path
                    .removePrefix("/packs/"),
            )

            vm.chooseRingtone(null)
            assertNull(vm.ringing.value)
            assertNull(store.state.value.ringtone)
            assertTrue(vm.state.value.ringtoneFollowsVoice)
        }

    @Test
    fun aVoiceWithoutRingtonesNeedsARingtonePick() =
        runTest(dispatcher) {
            store.state.value = SampleChatChoice(voice = owl.voice)
            val vm = viewModel()
            collect(vm)
            assertFalse(vm.state.value.ringtoneFollowsVoice)
            assertNull(vm.state.value.ringtone)
        }

    @Test
    fun aChoiceWhosePackIsGoneCountsAsNone() =
        runTest(dispatcher) {
            store.state.value =
                SampleChatChoice(voice = ChatVoice("gone.pack", "ghost"), ringtone = ChatVoice("gone.pack", "ghost"))
            val vm = viewModel()
            collect(vm)
            assertNull(vm.state.value.character)
            assertNull(vm.state.value.ringtone)
        }

    @Test
    fun withoutPacksItOffersTheDemoPacks() =
        runTest(dispatcher) {
            characters.packs.value = emptyList()
            ringtones.value = emptyList()
            val vm = viewModel()
            collect(vm)
            assertFalse(vm.state.value.hasCharacters)

            vm.requestDemoPacks()
            assertTrue(demoPacks.take())
        }

    @Test
    fun theSampleCallPicksTheNightVersionAtNight() {
        val sound = sampleCallSound(frogRingtone, RuleContext(LocalDateTime.of(2026, 10, 24, 23, 0)))
        assertEquals("/packs/frog/night.ogg", sound?.file?.path)
    }

    private fun ringtoneItem(
        character: VoiceCharacter,
        vararg names: String,
    ) = RingtoneCharacterItem(
        character,
        names.map { name ->
            val condition = if (name == "day" || name == "night") When(time = JsonPrimitive(name)) else null
            RingtoneVariantItem(
                RingtoneVariant("${character.id}/$name.ogg", condition = condition, loop = LoopRange(null, null)),
                File("/packs/${character.id}/$name.ogg"),
            )
        },
    )

    private class FakeStore : SampleChatStore {
        val state = MutableStateFlow(SampleChatChoice())
        override val choice: Flow<SampleChatChoice> = state

        override suspend fun setVoice(voice: ChatVoice) = state.update { it.copy(voice = voice) }

        override suspend fun setRingtone(ringtone: ChatVoice?) = state.update { it.copy(ringtone = ringtone) }
    }

    private class FakeRinger : RingtonePreview {
        val sounds = mutableListOf<RingSound>()
        var stops = 0

        override suspend fun ring(sound: RingSound): Long? {
            sounds += sound
            return 8_000
        }

        override fun pause() = Unit

        override fun resume() = Unit

        override fun stop() {
            stops++
        }
    }
}
