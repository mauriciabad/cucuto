package app.mauri.cucuto.ui.ringtone

import app.mauri.cucuto.core.data.chats.Chat
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.chats.ChatVoice
import app.mauri.cucuto.core.data.db.PhoneRingtoneEntity
import app.mauri.cucuto.core.data.db.RingtoneMode
import app.mauri.cucuto.core.data.db.RingtoneRuleEntry
import app.mauri.cucuto.core.data.db.RingtoneRules
import app.mauri.cucuto.core.data.settings.PickerKind
import app.mauri.cucuto.core.ringtone.variants.RingtoneVariant
import app.mauri.cucuto.packspec.LoopRange
import app.mauri.cucuto.packspec.When
import app.mauri.cucuto.testing.FakePickerFoldSettings
import app.mauri.cucuto.testing.TestData.ADA
import app.mauri.cucuto.testing.TestData.CLUB
import app.mauri.cucuto.testing.TestData.chat
import app.mauri.cucuto.testing.TestData.frog
import app.mauri.cucuto.testing.TestData.kettle
import app.mauri.cucuto.testing.TestData.owl
import app.mauri.cucuto.ui.components.PackChip
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
import org.junit.Assert.assertNotEquals
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
    private val folds = FakePickerFoldSettings()

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

    private fun viewModel(chat: ChatKey = ADA) = RingtonePickerViewModel(chat, source, preview, { noon }, folds)

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
    fun ownRulesShowAsTheirOwnChipAndComeBackWhenPickedAgain() =
        runTest(dispatcher) {
            // Night by day (ADR 0074): stored on the rules screen.
            val rules = listOf(RingtoneRuleEntry("frog/night.ogg"))
            source.saved(
                PhoneRingtoneEntity(
                    1,
                    mode = RingtoneMode.CUSTOM,
                    rules = RingtoneRules.encode(rules),
                    updatedAt = 1,
                ),
            )
            val vm = viewModel()
            collect(vm)
            val state = vm.state.value
            assertEquals(
                listOf(
                    RingtoneMode.PACK,
                    RingtoneMode.CUSTOM,
                    RingtoneMode.RANDOM,
                    RingtoneMode.TIME,
                    RingtoneMode.FIXED,
                ),
                state.modes,
            )
            assertEquals(RingtoneMode.CUSTOM, state.selection?.mode)
            assertEquals(rules, state.selection?.rules)
            assertFalse(state.canSave)

            vm.setMode(RingtoneMode.TIME)
            assertEquals(
                emptyList<RingtoneRuleEntry>(),
                vm.state.value.selection
                    ?.rules,
            )
            vm.setMode(RingtoneMode.CUSTOM)
            assertEquals(
                rules,
                vm.state.value.selection
                    ?.rules,
            )
            assertFalse(vm.state.value.canSave)

            // Another character never offers them.
            vm.select(kettleItem)
            assertFalse(RingtoneMode.CUSTOM in vm.state.value.modes)
        }

    @Test
    fun aSaveOnTheRulesScreenReplacesAnUnsavedChoice() =
        runTest(dispatcher) {
            source.saved(PhoneRingtoneEntity(1, updatedAt = 1))
            val vm = viewModel()
            collect(vm)
            vm.setMode(RingtoneMode.RANDOM)
            assertTrue(vm.state.value.canSave)
            // The rules screen on top saves the user's own rules.
            source.saved(
                PhoneRingtoneEntity(
                    1,
                    mode = RingtoneMode.CUSTOM,
                    rules = RingtoneRules.encode(listOf(RingtoneRuleEntry("frog/night.ogg"))),
                    updatedAt = 2,
                ),
            )
            assertNull(vm.state.value.edited)
            assertEquals(
                RingtoneMode.CUSTOM,
                vm.state.value.selection
                    ?.mode,
            )
        }

    @Test
    fun tappingACoverPicksItAndPlaysTheVersionItsRuleRingsWithNow() =
        runTest(dispatcher) {
            val vm = viewModel()
            collect(vm)
            vm.select(frogItem)
            assertEquals(listOf("frog/day.ogg"), preview.plays)
            assertEquals(PlayingVariant(frog.voice, "frog/day.ogg"), vm.state.value.playing)

            // It rings like the call: still going long after the song's length, until stopped (#242).
            advanceTimeBy(10 * 60_000)
            runCurrent()
            assertEquals(PlayingVariant(frog.voice, "frog/day.ogg"), vm.state.value.playing)
            vm.stopPreview()
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
    fun eachVersionRingsOnItsOwnPausesAndResumesAndStops() =
        runTest(dispatcher) {
            val vm = viewModel()
            collect(vm)
            vm.togglePreview(frogItem, "frog/night.ogg")
            assertEquals(PlayingVariant(frog.voice, "frog/night.ogg"), vm.state.value.playing)
            vm.togglePreview(frogItem, "frog/night.ogg")
            assertTrue(
                vm.state.value.preview!!
                    .paused,
            )
            assertEquals(1, preview.pauses)
            vm.togglePreview(frogItem, "frog/night.ogg")
            assertFalse(
                vm.state.value.preview!!
                    .paused,
            )
            assertEquals(1, preview.resumes)
            vm.togglePreview(kettleItem, "kettle/live.ogg")
            assertEquals(listOf("frog/night.ogg", "kettle/live.ogg"), preview.plays)
            vm.stopPreview()
            assertNull(vm.state.value.playing)
        }

    @Test
    fun theProgressGoesRoundOneRingCycle() =
        runTest(dispatcher) {
            preview.cycle = 92_000
            val vm = viewModel()
            collect(vm)
            vm.togglePreview(kettleItem, "kettle/reel.ogg")
            assertEquals(
                92_000L,
                vm.state.value.preview!!
                    .cycleMs,
            )
        }

    @Test
    fun previewsRingWithTheVersionsLoopAndFadeIn() =
        runTest(dispatcher) {
            val looped =
                RingtoneCharacterItem(
                    kettle,
                    listOf(
                        RingtoneVariantItem(
                            RingtoneVariant(
                                file = "kettle/loop.ogg",
                                label = "Loop",
                                loop = LoopRange(4_000L, 12_000L),
                            ),
                            File("/packs/kettle/loop.ogg"),
                        ),
                    ),
                )
            source.packs.value = listOf(RingtonePackItem(kettle.packId, "Kitchen orchestra", listOf(looped)))
            val vm = viewModel()
            collect(vm)
            vm.select(looped)
            // The preview gets the loop section and fade-in, so it rings the intro once and then the loop (#242).
            assertEquals(LoopRange(4_000L, 12_000L), preview.sounds.single().loop)
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
            assertEquals(
                listOf(Saved(1, kettle.voice, followsVoice = false, RingtoneMode.FIXED, "kettle/radio.ogg")),
                source.saved,
            )
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
            assertTrue(
                vm.state.value.selection!!
                    .followsVoice,
            )
            vm.setMode(RingtoneMode.TIME)
            vm.save()
            assertEquals(Saved(1, null, followsVoice = true, RingtoneMode.TIME, null), source.saved.single())
        }

    @Test
    fun aRingtoneKeptWhileTheVoiceHasNoneStillFollowsUntilTheUserPicksAnother() =
        runTest(dispatcher) {
            // The voice (a bird without ringtones) is not in the ringtone grid; the kettle stayed from before.
            source.chat.value = chat(ADA, "Ada", voice = ChatVoice(frog.packId, "tweet"))
            source.saved(PhoneRingtoneEntity(1, kettle.packId, kettle.id, updatedAt = 1, followsVoice = true))
            val vm = viewModel()
            collect(vm)
            assertEquals(RingtoneSelection(kettle.voice, followsVoice = true), vm.state.value.saved)

            // Tapping the kept cover keeps it as it is: nothing to save.
            vm.select(kettleItem)
            assertTrue(
                vm.state.value.selection!!
                    .followsVoice,
            )
            assertFalse(vm.state.value.canSave)

            // Another character is the user's own pick, which voice changes leave alone.
            vm.select(owlItem)
            vm.save()
            assertEquals(Saved(1, owl.voice, followsVoice = false, RingtoneMode.PACK, null), source.saved.single())
        }

    @Test
    fun aRingtoneThePersonPickedIsNotTheVoices() =
        runTest(dispatcher) {
            source.saved(PhoneRingtoneEntity(1, kettle.packId, kettle.id, RingtoneMode.PACK, updatedAt = 1))
            val vm = viewModel()
            collect(vm)
            assertFalse(
                vm.state.value.saved!!
                    .followsVoice,
            )
            vm.select(kettleItem)
            assertFalse(
                vm.state.value.selection!!
                    .followsVoice,
            )
            assertFalse(vm.state.value.canSave)
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
            assertEquals(Saved(1, owl.voice, followsVoice = false, RingtoneMode.PACK, null), source.saved.single())
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
    fun aChatWithoutVoiceOrContactCanStillGetARingtone() =
        runTest(dispatcher) {
            source.chat.value = chat(ADA, "Ada", voice = null, contactId = null)
            val vm = viewModel()
            collect(vm)
            assertNull(vm.state.value.selection)
            vm.select(kettleItem)
            assertTrue(vm.state.value.canSave)
            assertTrue(vm.save())
            // No contact yet: the source creates it (null here).
            assertEquals(
                Saved(null, kettle.voice, followsVoice = false, RingtoneMode.PACK, null),
                source.saved.single(),
            )
        }

    @Test
    fun aGroupHasNoPhoneRingtone() =
        runTest(dispatcher) {
            source.chat.value = chat(CLUB, "Club", voice = frog.voice)
            val vm = viewModel(CLUB)
            collect(vm)
            vm.select(kettleItem)
            assertFalse(vm.state.value.canSave)
            assertFalse(vm.save())
        }

    @Test
    fun packChipsOfferOnlyPacksWithRingtonesWithTheirCounts() =
        runTest(dispatcher) {
            val vm = viewModel()
            collect(vm)
            assertEquals(
                listOf(
                    PackChip(frog.packId, "Chiptune critters", 2),
                    PackChip(kettle.packId, "Kitchen orchestra", 1),
                ),
                vm.state.value.chips,
            )
            vm.filterPack(kettle.packId)
            assertEquals(
                listOf(kettle.packId),
                vm.state.value.visiblePacks
                    .map { it.packId },
            )
            vm.filterPack(null)
            assertEquals(2, vm.state.value.visiblePacks.size)

            // One pack with ringtones needs no filter.
            source.packs.value = source.packs.value.take(1)
            assertEquals(emptyList<PackChip>(), vm.state.value.chips)
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

    @Test
    fun surpriseRingsACharacterWithRingtonesAndNeverTheSelectedOne() =
        runTest(dispatcher) {
            val vm = viewModel()
            collect(vm)
            var previous =
                vm.state.value.selection
                    ?.voice
            assertEquals(frog.voice, previous)
            val seen = mutableSetOf<ChatVoice>()
            repeat(30) {
                val pick = vm.surprise(null)!!
                assertNotEquals(previous, pick.voice)
                assertEquals(
                    pick.voice,
                    vm.state.value.selection
                        ?.voice,
                )
                assertEquals(
                    pick.voice,
                    vm.state.value.playing
                        ?.voice,
                )
                assertTrue(vm.state.value.rolled)
                previous = pick.voice
                seen += pick.voice
            }
            assertEquals(setOf(frog.voice, owl.voice, kettle.voice), seen)
            assertEquals(30, preview.plays.size)
        }

    @Test
    fun surpriseFromAPackWithOneCharacterRingsItAgainAndShowsIt() =
        runTest(dispatcher) {
            val vm = viewModel()
            collect(vm)
            vm.filterPack(frog.packId)
            vm.search("owl")

            assertEquals(kettleItem, vm.surprise(kettle.packId))
            assertEquals(kettleItem, vm.surprise(kettle.packId))

            val state = vm.state.value
            assertEquals(listOf("kettle/reel.ogg", "kettle/reel.ogg"), preview.plays)
            assertEquals("", state.query)
            assertNull(state.packFilter)
            assertEquals(2, state.roll?.count)
            assertTrue(state.sections.any { section -> kettleItem in section.shown })
        }

    @Test
    fun foldingAPackIsRememberedForTheRingtonePicker() =
        runTest(dispatcher) {
            val vm = viewModel()
            collect(vm)
            vm.toggleFold(kettle.packId)
            assertFalse(
                vm.state.value.sections
                    .first { it.packId == kettle.packId }
                    .expanded,
            )
            assertEquals(setOf(kettle.packId), folds.folds.value[PickerKind.Ringtone]?.folded)
            assertNull(folds.folds.value[PickerKind.Voice])
        }

    data class Saved(
        val contactId: Long?,
        val voice: ChatVoice?,
        val followsVoice: Boolean,
        val mode: RingtoneMode,
        val variant: String?,
        val rules: List<RingtoneRuleEntry> = emptyList(),
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
            chat: ChatKey,
            contactId: Long?,
            character: ChatVoice?,
            followsVoice: Boolean,
            mode: RingtoneMode,
            variant: String?,
            rules: List<RingtoneRuleEntry>,
        ) {
            if (fail) error("disk full")
            saved += Saved(contactId, character, followsVoice, mode, variant, rules)
            row.value =
                PhoneRingtoneEntity(
                    contactId ?: 1,
                    character?.packId,
                    character?.characterId,
                    mode,
                    variant,
                    updatedAt = 2,
                    followsVoice = followsVoice,
                    rules = RingtoneRules.encode(rules),
                )
        }

        override fun canReadPhoneState() = phoneState
    }

    private class FakePreview : RingtonePreview {
        val plays = mutableListOf<String>()
        val sounds = mutableListOf<RingSound>()
        var stops = 0
        var pauses = 0
        var resumes = 0
        var cycle = 90_000L

        override suspend fun ring(sound: RingSound): Long? {
            plays += sound.file.path.removePrefix("/packs/")
            sounds += sound
            return cycle
        }

        override fun pause() {
            pauses++
        }

        override fun resume() {
            resumes++
        }

        override fun stop() {
            stops++
        }
    }
}
