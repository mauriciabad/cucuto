package app.mauri.cucuto.ui.ringtone

import app.mauri.cucuto.core.data.chats.Chat
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.chats.ChatVoice
import app.mauri.cucuto.core.data.db.PhoneRingtoneEntity
import app.mauri.cucuto.core.data.db.RingtoneMode
import app.mauri.cucuto.core.ringtone.variants.RingtoneVariant
import app.mauri.cucuto.packspec.When
import app.mauri.cucuto.testing.TestData.ADA
import app.mauri.cucuto.testing.TestData.chat
import app.mauri.cucuto.testing.TestData.frog
import app.mauri.cucuto.testing.TestData.kettle
import app.mauri.cucuto.testing.TestData.owl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
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

@OptIn(ExperimentalCoroutinesApi::class)
class RingtonePickerViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val source = FakeSource()
    private val preview = FakePreview()

    /** 2026-10-24 at noon, local time: the day version wins "Day and night" and the pack's rules. */
    private val noon =
        LocalDateTime
            .parse("2026-10-24T12:00")
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private val frogItem =
        RingtoneCharacterItem(
            frog,
            listOf(variant("frog/day.ogg", "Day", "day", "day"), variant("frog/night.ogg", "Night", "night", "night")),
        )
    private val owlItem = RingtoneCharacterItem(owl, listOf(variant("owl/hoot.ogg", "Hoot")))
    private val kettleItem =
        RingtoneCharacterItem(
            kettle,
            listOf(
                variant("kettle/reel.ogg", "Reel"),
                variant("kettle/radio.ogg", "Radio"),
                variant("kettle/live.ogg", "Live"),
            ),
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        source.packs.value =
            listOf(
                RingtonePackItem(frog.packId, "Chiptune critters", listOf(frogItem, owlItem)),
                RingtonePackItem(kettle.packId, "Kitchen orchestra", listOf(kettleItem)),
            )
        source.chat.value = chat(ADA, "Ada", voice = frog.voice)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun variant(
        file: String,
        label: String,
        tag: String? = null,
        time: String? = null,
    ) = RingtoneVariantItem(
        RingtoneVariant(
            file = file,
            label = label,
            tags = listOfNotNull(tag),
            condition = time?.let { When(time = JsonPrimitive(it)) },
        ),
        File("/packs/$file"),
    )

    private fun viewModel(chat: ChatKey = ADA) = RingtonePickerViewModel(chat, source, preview) { noon }

    private fun TestScope.collect(vm: RingtonePickerViewModel) = backgroundScope.launch { vm.state.collect {} }

    @Test
    fun withNothingStoredTheVoicesOwnRingtoneIsShownAndCanBeSaved() =
        runTest(dispatcher) {
            val vm = viewModel()
            collect(vm)
            val state = vm.state.value
            assertFalse(state.loading)
            assertNull(state.saved)
            assertEquals(RingtoneSelection(frog.voice, followsVoice = true), state.selection)
            assertEquals(frogItem, state.selectedCharacter)
            // Two versions tagged day and night: every rule but season.
            assertEquals(
                listOf(RingtoneMode.PACK, RingtoneMode.RANDOM, RingtoneMode.TIME, RingtoneMode.FIXED),
                state.modes,
            )
            assertTrue(state.canSave)
        }

    @Test
    fun tappingACoverPicksItAndPlaysTheVersionItsRuleRingsWithNow() =
        runTest(dispatcher) {
            val vm = viewModel()
            collect(vm)
            vm.select(frogItem)
            assertEquals(listOf("frog/day.ogg"), preview.plays)
            assertEquals(PlayingVariant(frog.voice, "frog/day.ogg"), vm.state.value.playing)

            // The record stops by itself when the file ends.
            advanceTimeBy(preview.duration + 1)
            runCurrent()
            assertNull(vm.state.value.playing)
            assertTrue(preview.stops > 0)
        }

    @Test
    fun tappingThePlayingCoverStopsIt() =
        runTest(dispatcher) {
            val vm = viewModel()
            collect(vm)
            vm.select(frogItem)
            vm.select(frogItem)
            assertNull(vm.state.value.playing)
            assertEquals(1, preview.plays.size)
        }

    @Test
    fun eachVersionPlaysOnItsOwnAndStopsWhenTappedAgain() =
        runTest(dispatcher) {
            val vm = viewModel()
            collect(vm)
            vm.togglePreview(frogItem, "frog/night.ogg")
            assertEquals(PlayingVariant(frog.voice, "frog/night.ogg"), vm.state.value.playing)
            vm.togglePreview(frogItem, "frog/night.ogg")
            assertNull(vm.state.value.playing)
            vm.togglePreview(kettleItem, "kettle/live.ogg")
            assertEquals(listOf("frog/night.ogg", "kettle/live.ogg"), preview.plays)
        }

    @Test
    fun previewsLongerThanThirtySecondsStopThere() =
        runTest(dispatcher) {
            preview.duration = 360_000
            val vm = viewModel()
            collect(vm)
            vm.togglePreview(kettleItem, "kettle/reel.ogg")
            advanceTimeBy(RingtonePickerViewModel.MAX_PREVIEW_MS + 1)
            runCurrent()
            assertNull(vm.state.value.playing)
        }

    @Test
    fun anotherCharacterMixesAndSavesItsOwnCharacter() =
        runTest(dispatcher) {
            val vm = viewModel()
            collect(vm)
            vm.select(kettleItem)
            val selection = vm.state.value.selection!!
            assertEquals(kettle.voice, selection.voice)
            assertFalse(selection.followsVoice)
            vm.setVariant("kettle/radio.ogg")

            assertTrue(vm.save())
            assertEquals(listOf(Saved(1, kettle.voice, RingtoneMode.FIXED, "kettle/radio.ogg")), source.saved)
            assertTrue(preview.stops > 0)
            assertEquals(
                kettle.voice,
                vm.state.value.assigned
                    ?.voice,
            )
            // Saved: nothing left to save, and a second tap does nothing.
            assertFalse(vm.save())
        }

    @Test
    fun theMessageCharacterFollowsTheVoice() =
        runTest(dispatcher) {
            source.saved(PhoneRingtoneEntity(1, kettle.packId, kettle.id, RingtoneMode.PACK, updatedAt = 1))
            val vm = viewModel()
            collect(vm)
            assertEquals(RingtoneSelection(kettle.voice, followsVoice = false), vm.state.value.saved)

            vm.select(frogItem)
            vm.setMode(RingtoneMode.TIME)
            vm.save()
            assertEquals(Saved(1, null, RingtoneMode.TIME, null), source.saved.single())
        }

    @Test
    fun theRuleIsKeptWhenTheNewCharacterHasItElseThePacksRules() =
        runTest(dispatcher) {
            val vm = viewModel()
            collect(vm)
            vm.select(kettleItem)
            vm.setMode(RingtoneMode.RANDOM)
            vm.select(frogItem)
            assertEquals(
                RingtoneMode.RANDOM,
                vm.state.value.selection
                    ?.mode,
            )
            vm.setMode(RingtoneMode.TIME)
            // The kettle has no day and night versions.
            vm.select(kettleItem)
            assertEquals(
                RingtoneMode.PACK,
                vm.state.value.selection
                    ?.mode,
            )
        }

    @Test
    fun oneVersionPicksTheFirstUntilAnotherIsChosen() =
        runTest(dispatcher) {
            val vm = viewModel()
            collect(vm)
            vm.setMode(RingtoneMode.FIXED)
            assertEquals(
                "frog/day.ogg",
                vm.state.value.selection
                    ?.variant,
            )
            vm.setVariant("frog/night.ogg")
            assertEquals(
                RingtoneMode.FIXED,
                vm.state.value.selection
                    ?.mode,
            )
            vm.setMode(RingtoneMode.PACK)
            assertNull(
                vm.state.value.selection
                    ?.variant,
            )
            // A file the character does not have is ignored.
            vm.setVariant("kettle/reel.ogg")
            assertEquals(
                RingtoneMode.PACK,
                vm.state.value.selection
                    ?.mode,
            )
        }

    @Test
    fun aSingleRingtoneSavesThePacksRule() =
        runTest(dispatcher) {
            val vm = viewModel()
            collect(vm)
            vm.select(frogItem)
            vm.setMode(RingtoneMode.RANDOM)
            vm.select(owlItem)
            assertEquals(emptyList<RingtoneMode>(), vm.state.value.modes)
            vm.save()
            assertEquals(Saved(1, owl.voice, RingtoneMode.PACK, null), source.saved.single())
        }

    @Test
    fun randomPerCallAsksForThePhonePermissionFirst() =
        runTest(dispatcher) {
            val vm = viewModel()
            collect(vm)
            vm.setMode(RingtoneMode.RANDOM)
            source.phoneState = false
            assertTrue(vm.needsPhonePermission())
            source.phoneState = true
            assertFalse(vm.needsPhonePermission())
        }

    @Test
    fun whatIsStoredIsNotSavedAgain() =
        runTest(dispatcher) {
            source.saved(PhoneRingtoneEntity(1, null, null, RingtoneMode.TIME, updatedAt = 1))
            val vm = viewModel()
            collect(vm)
            assertEquals(
                RingtoneSelection(frog.voice, followsVoice = true, mode = RingtoneMode.TIME),
                vm.state.value.saved,
            )
            assertFalse(vm.state.value.canSave)
            vm.setMode(RingtoneMode.PACK)
            assertTrue(vm.state.value.canSave)
            vm.setMode(RingtoneMode.TIME)
            assertFalse(vm.state.value.canSave)
        }

    @Test
    fun aFailedSaveLetsTheUserTryAgain() =
        runTest(dispatcher) {
            source.fail = true
            val vm = viewModel()
            collect(vm)
            vm.save()
            assertTrue(vm.state.value.failed)
            assertNull(vm.state.value.assigned)
            assertTrue(vm.state.value.canSave)
        }

    @Test
    fun aChatWithoutContactCannotSave() =
        runTest(dispatcher) {
            source.chat.value = chat(ADA, "Ada", voice = null, contactId = null)
            val vm = viewModel()
            collect(vm)
            assertNull(vm.state.value.selection)
            vm.select(kettleItem)
            assertFalse(vm.state.value.canSave)
            assertFalse(vm.save())
            assertTrue(source.saved.isEmpty())
        }

    @Test
    fun leavingStopsThePreview() =
        runTest(dispatcher) {
            val vm = viewModel()
            collect(vm)
            vm.select(frogItem)
            vm.stopPreview()
            assertNull(vm.state.value.playing)
            assertTrue(preview.stops > 0)
        }

    @Test
    fun aRowWithoutCharacterFollowsTheVoiceAndNothingWithoutAVoice() {
        val row = PhoneRingtoneEntity(1, null, null, RingtoneMode.FIXED, "frog/night.ogg", updatedAt = 1)
        assertEquals(
            RingtoneSelection(frog.voice, followsVoice = true, mode = RingtoneMode.FIXED, variant = "frog/night.ogg"),
            RingtonePickerViewModel.saved(row, frog.voice),
        )
        assertNull(RingtonePickerViewModel.saved(row, null))
        assertNull(RingtonePickerViewModel.saved(null, frog.voice))
    }

    data class Saved(
        val contactId: Long,
        val voice: ChatVoice?,
        val mode: RingtoneMode,
        val variant: String?,
    )

    private class FakeSource : RingtonePickerSource {
        val chat = MutableStateFlow<Chat?>(null)
        val packs = MutableStateFlow<List<RingtonePackItem>>(emptyList())
        private val row = MutableStateFlow<PhoneRingtoneEntity?>(null)
        val saved = mutableListOf<Saved>()
        var phoneState = true
        var fail = false

        fun saved(entity: PhoneRingtoneEntity) {
            row.value = entity
        }

        override fun observe(chat: ChatKey): Flow<RingtonePickerData> =
            combine(this.chat, packs, row) { c, p, r -> RingtonePickerData(c, r, p) }

        override suspend fun save(
            contactId: Long,
            voice: ChatVoice?,
            mode: RingtoneMode,
            variant: String?,
        ) {
            if (fail) error("disk full")
            saved += Saved(contactId, voice, mode, variant)
            row.value =
                PhoneRingtoneEntity(contactId, voice?.packId, voice?.characterId, mode, variant, updatedAt = 2)
        }

        override fun canReadPhoneState() = phoneState
    }

    private class FakePreview : RingtonePreview {
        val plays = mutableListOf<String>()
        var stops = 0
        var duration = 8_000L

        override suspend fun play(file: File): Long? {
            plays += file.path.removePrefix("/packs/")
            return duration
        }

        override fun stop() {
            stops++
        }
    }
}
