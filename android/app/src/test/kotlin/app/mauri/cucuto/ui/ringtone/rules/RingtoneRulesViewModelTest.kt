package app.mauri.cucuto.ui.ringtone.rules

import app.mauri.cucuto.core.data.chats.Chat
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.chats.ChatVoice
import app.mauri.cucuto.core.data.db.PhoneRingtoneEntity
import app.mauri.cucuto.core.data.db.RingtoneChoice
import app.mauri.cucuto.core.data.db.RingtoneMode
import app.mauri.cucuto.core.data.db.RingtoneRuleEntry
import app.mauri.cucuto.core.data.db.RingtoneRules
import app.mauri.cucuto.core.ringtone.variants.RingtoneVariant
import app.mauri.cucuto.packspec.When
import app.mauri.cucuto.testing.TestData.ADA
import app.mauri.cucuto.testing.TestData.chat
import app.mauri.cucuto.testing.TestData.frog
import app.mauri.cucuto.testing.TestData.kettle
import app.mauri.cucuto.ui.ringtone.RingSound
import app.mauri.cucuto.ui.ringtone.RingtonePreview
import app.mauri.cucuto.ui.ringtone.RingtoneVariantItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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
class RingtoneRulesViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val source = FakeSource()
    private val preview = FakePreview()

    /** 2026-10-24 at noon, local time: the day version rings by the pack's rules. */
    private val noon =
        LocalDateTime
            .parse("2026-10-24T12:00")
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private val day = entry("frog/day.ogg", "Day", "day")
    private val night = entry("frog/night.ogg", "Night", "night")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        source.entries.value = listOf(day, night)
        source.chat.value = chat(ADA, "Ada", voice = frog.voice)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun entry(
        file: String,
        label: String,
        time: String,
    ) = RingtoneVariantItem(
        RingtoneVariant(file, label, tags = listOf(time), condition = When(time = JsonPrimitive(time))),
        File("/packs/$file"),
    )

    private fun viewModel(
        chat: ChatKey? = ADA,
        voice: ChatVoice = frog.voice,
    ) = RingtoneRulesViewModel(voice, chat, source, preview) { noon }

    private fun TestScope.collect(vm: RingtoneRulesViewModel) = backgroundScope.launch { vm.state.collect {} }

    @Test
    fun theVoicesOwnRingtoneFollowsThePacksRulesAndCanBeEdited() =
        runTest(dispatcher) {
            val vm = viewModel()
            collect(vm)
            val state = vm.state.value
            assertTrue(state.editable)
            assertEquals(RingtoneMode.PACK, state.mode)
            assertEquals(day.variant, state.ringingNow)
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
            assertFalse(state.canSave)
            assertFalse(state.canReset)
        }

    @Test
    fun ownRulesThatSwapTheVersionsAreSavedForTheContact() =
        runTest(dispatcher) {
            val vm = viewModel()
            collect(vm)
            vm.chooseWhen(0, WhenChoice.NIGHT)
            vm.chooseWhen(1, WhenChoice.DAY)
            val state = vm.state.value
            assertEquals(RingtoneMode.CUSTOM, state.mode)
            assertEquals(night.variant.file, state.ringingNow?.file)
            assertTrue(state.canSave)
            assertTrue(vm.save())
            val saved = source.saved.single()
            assertEquals(RingtoneMode.CUSTOM, saved.mode)
            // Following the voice: no character of its own (ADR 0063).
            assertNull(saved.character)
            assertTrue(saved.followsVoice)
            assertEquals(listOf("frog/day.ogg", "frog/night.ogg"), saved.rules.map { it.variant })
            assertEquals("""{"time":"night"}""", saved.rules[0].condition.toString())
            assertTrue(vm.state.value.done)
        }

    @Test
    fun ownRulesEqualToThePacksAreSavedAsThePacksRules() =
        runTest(dispatcher) {
            source.row.value = PhoneRingtoneEntity(1, mode = RingtoneMode.RANDOM, updatedAt = 1)
            val vm = viewModel()
            collect(vm)
            assertEquals(RingtoneMode.RANDOM, vm.state.value.mode)
            vm.setMode(RingtoneMode.CUSTOM)
            assertEquals(
                RingtoneMode.PACK,
                vm.state.value.selection
                    ?.mode,
            )
            assertTrue(vm.save())
            assertEquals(RingtoneMode.PACK, source.saved.single().mode)
            assertEquals(emptyList<RingtoneRuleEntry>(), source.saved.single().rules)
        }

    @Test
    fun storedOwnRulesComeBackAndResetGoesBackToThePacksAtOnce() =
        runTest(dispatcher) {
            val rules = listOf(RingtoneRuleEntry("frog/night.ogg"))
            source.row.value =
                PhoneRingtoneEntity(1, mode = RingtoneMode.CUSTOM, rules = RingtoneRules.encode(rules), updatedAt = 1)
            val vm = viewModel()
            collect(vm)
            val state = vm.state.value
            assertEquals(RingtoneMode.CUSTOM, state.mode)
            assertEquals(listOf(true, false), state.draft.rows.map { it.on })
            assertEquals(night.variant.file, state.ringingNow?.file)
            assertFalse(state.canSave)
            assertTrue(state.canReset)

            assertTrue(vm.reset())
            assertEquals(RingtoneMode.PACK, source.saved.single().mode)
            assertEquals(1, vm.state.value.resets)
            assertFalse(vm.state.value.done)
            assertEquals(RingtoneMode.PACK, vm.state.value.mode)
            assertFalse(vm.state.value.canReset)
        }

    @Test
    fun oneVersionAndTheLastVersionOnStay() =
        runTest(dispatcher) {
            val vm = viewModel()
            collect(vm)
            vm.setVariant("frog/night.ogg")
            assertEquals(RingtoneMode.FIXED, vm.state.value.mode)
            assertEquals(night.variant, vm.state.value.ringingNow)
            vm.toggleRow(0, false)
            vm.toggleRow(1, false)
            assertEquals(
                listOf(false, true),
                vm.state.value.draft.rows
                    .map { it.on },
            )
            assertEquals(
                listOf("frog/night.ogg"),
                vm.state.value.selection
                    ?.rules
                    ?.map { it.variant },
            )
        }

    @Test
    fun withoutAChatOrForAnotherCharacterTheRulesAreOnlyShown() =
        runTest(dispatcher) {
            val packs = viewModel(chat = null)
            collect(packs)
            assertFalse(packs.state.value.editable)
            assertFalse(packs.state.value.otherCharacter)
            packs.setMode(RingtoneMode.RANDOM)
            assertEquals(RingtoneMode.PACK, packs.state.value.mode)

            val other = viewModel(voice = kettle.voice)
            collect(other)
            assertFalse(other.state.value.editable)
            assertTrue(other.state.value.otherCharacter)
            assertFalse(other.save())
        }

    @Test
    fun androidDefaultAndLeaveAsIsRingNoCharacterEvenWhenTheVoiceHasRingtones() =
        runTest(dispatcher) {
            // The voice (frog) has ringtones, but the stored choice rings no character (ADR 0073): the screen
            // must not offer to edit frog's rules, or saving would silently replace the choice.
            for (choice in listOf(RingtoneChoice.ANDROID_DEFAULT, RingtoneChoice.UNCHANGED)) {
                source.row.value = PhoneRingtoneEntity(1, updatedAt = 1, followsVoice = false, choice = choice)
                val vm = viewModel()
                collect(vm)
                val state = vm.state.value
                assertNull(choice.name, state.saved)
                assertFalse(choice.name, state.editable)
                assertTrue(choice.name, state.otherCharacter)
                vm.setMode(RingtoneMode.RANDOM)
                assertFalse(choice.name, vm.save())
                assertFalse(choice.name, vm.reset())
            }
            assertTrue(source.saved.isEmpty())
        }

    @Test
    fun aFailedSaveStaysAndSaysSo() =
        runTest(dispatcher) {
            source.fail = true
            val vm = viewModel()
            collect(vm)
            vm.setMode(RingtoneMode.TIME)
            assertTrue(vm.save())
            assertTrue(vm.state.value.failed)
            assertFalse(vm.state.value.done)
            assertTrue(vm.state.value.canSave)
        }

    @Test
    fun versionsRingLikeTheCallAndRandomAsksForThePhonePermission() =
        runTest(dispatcher) {
            val vm = viewModel()
            collect(vm)
            vm.togglePreview("frog/night.ogg")
            assertEquals(listOf("frog/night.ogg"), preview.plays)
            assertEquals(
                "frog/night.ogg",
                vm.state.value.preview
                    ?.key,
            )
            vm.setMode(RingtoneMode.RANDOM)
            assertNull(vm.state.value.ringingNow)
            source.phoneState = false
            assertTrue(vm.needsPhonePermission())
        }

    data class Saved(
        val contactId: Long?,
        val character: ChatVoice?,
        val followsVoice: Boolean,
        val mode: RingtoneMode,
        val variant: String?,
        val rules: List<RingtoneRuleEntry>,
    )

    private class FakeSource : RingtoneRulesSource {
        val chat = MutableStateFlow<Chat?>(null)
        val entries = MutableStateFlow<List<RingtoneVariantItem>>(emptyList())
        val row = MutableStateFlow<PhoneRingtoneEntity?>(null)
        val saved = mutableListOf<Saved>()
        var phoneState = true
        var fail = false

        override fun observe(
            voice: ChatVoice,
            chat: ChatKey?,
        ): Flow<RingtoneRulesData> =
            combine(this.chat, entries, row) { c, e, r ->
                val character = listOf(frog, kettle).first { it.voice == voice }
                RingtoneRulesData(
                    character,
                    "Chiptune critters",
                    if (voice ==
                        frog.voice
                    ) {
                        e
                    } else {
                        emptyList()
                    },
                    c.takeIf { chat != null },
                    r.takeIf { chat != null },
                )
            }

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

        override suspend fun ring(sound: RingSound): Long? {
            plays += sound.file.path.removePrefix("/packs/")
            return 90_000L
        }

        override fun pause() = Unit

        override fun resume() = Unit

        override fun stop() = Unit
    }
}
