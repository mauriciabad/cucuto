package app.mauri.cucuto.a11y

import androidx.compose.runtime.Composable
import app.mauri.cucuto.core.data.chats.ChatVoice
import app.mauri.cucuto.core.data.db.RepositoryAuthType
import app.mauri.cucuto.core.data.db.RingtoneChoice
import app.mauri.cucuto.core.repo.RepositorySnapshot
import app.mauri.cucuto.packspec.RepositoryIndex
import app.mauri.cucuto.packspec.SpecJson
import app.mauri.cucuto.picker.PackFolds
import app.mauri.cucuto.picker.PickerContent
import app.mauri.cucuto.picker.SurpriseRoll
import app.mauri.cucuto.picker.previewPickerState
import app.mauri.cucuto.sample.SampleChatContent
import app.mauri.cucuto.sample.SampleChatState
import app.mauri.cucuto.ui.discover.DiscoverActions
import app.mauri.cucuto.ui.discover.DiscoverContent
import app.mauri.cucuto.ui.discover.DiscoverUiState
import app.mauri.cucuto.ui.discover.RepoLoad
import app.mauri.cucuto.ui.discover.SavedRepository
import app.mauri.cucuto.ui.discover.sections
import app.mauri.cucuto.ui.ringtone.RingtonePickerActions
import app.mauri.cucuto.ui.ringtone.RingtonePickerContent
import app.mauri.cucuto.ui.ringtone.RingtonePickerState
import app.mauri.cucuto.ui.ringtone.RingtoneSelection
import app.mauri.cucuto.ui.ringtone.previewRingtonePickerState
import app.mauri.cucuto.ui.ringtone.rules.RingtoneRulesActions
import app.mauri.cucuto.ui.ringtone.rules.RingtoneRulesContent
import app.mauri.cucuto.ui.ringtone.rules.previewRingtoneRulesState
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * The states of the screens added on 2026-10-24 that the base list leaves out (#226), with made-up content: the pickers
 * with a folded pack, a search and a "Surprise me" pick (ADR 0072), the ringtone picker as step 2 of the sound flow and
 * with "Android default" chosen (ADR 0073), the rules screen without a chat (ADR 0074), the sample chat before any
 * pack (ADR 0075), and Discover with drawn vibrations (#301, ADR 0069). [A11yScreens.all] lists them with the others.
 */
internal object A11yFlowScreens {
    val all: List<Pair<String, @Composable () -> Unit>> =
        listOf(
            "picker-rolled" to { Picker(rolledPicker()) },
            "picker-search" to { Picker(previewPickerState().copy(query = "bee")) },
            "ringtone-picker-step" to { RingtonePicker(previewRingtonePickerState(), step = true) },
            "ringtone-picker-default" to {
                RingtonePicker(previewRingtonePickerState().copy(pending = RingtoneChoice.ANDROID_DEFAULT))
            },
            "ringtone-picker-rolled" to { RingtonePicker(rolledRingtonePicker()) },
            "ringtone-rules-view-only" to {
                RingtoneRulesContent(
                    state = previewRingtoneRulesState().copy(chatKey = null, chat = null),
                    actions = RingtoneRulesActions(),
                )
            },
            "sample-chat-empty" to {
                SampleChatContent(
                    state = SampleChatState(loading = false),
                    ringing = null,
                    onBack = {},
                    onChooseVoice = {},
                    onChooseRingtone = {},
                    onPlayMessage = {},
                    onToggleCall = {},
                    onGetDemoPacks = {},
                )
            },
            "discover-haptics" to { DiscoverHaptics() },
        )

    /** Buzz the bee came from "Surprise me" (the bar offers Re-roll and Use), with the kitchen pack folded. */
    fun rolledPicker() =
        previewPickerState().let { state ->
            val bee = ChatVoice("app.mauri.demo.critters", "bee")
            state.copy(
                selected = bee,
                roll = SurpriseRoll(packId = null, pick = bee, count = 1),
                folds = PackFolds().with("app.mauri.demo.kitchen", expanded = false),
            )
        }

    /** Tootle the kettle came from "Surprise me" for Iris's ringtone (the save bar offers Re-roll). */
    fun rolledRingtonePicker(): RingtonePickerState =
        previewRingtonePickerState().let { state ->
            val kettle = ChatVoice("app.mauri.demo.kitchen", "kettle")
            state.copy(
                edited = RingtoneSelection(kettle, followsVoice = false),
                pending = RingtoneChoice.CHARACTER,
                roll = SurpriseRoll(packId = null, pick = kettle, count = 1),
            )
        }

    /** A pack whose characters vibrate their own pattern, follow the voice, or don't vibrate. */
    @Composable
    private fun DiscoverHaptics() {
        val repo =
            SavedRepository(
                1,
                "https://packs.example.com/repository.json",
                "Friends' packs",
                false,
                true,
                RepositoryAuthType.NONE,
            )
        val snapshot =
            RepositorySnapshot(
                indexUrl = repo.url.toHttpUrl(),
                baseUrl = repo.url.toHttpUrl(),
                index = SpecJson.decodeFromString(RepositoryIndex.serializer(), HAPTICS_INDEX),
                warnings = emptyList(),
                etag = null,
            )
        val load = mapOf(repo.id to RepoLoad(snapshot = snapshot))
        DiscoverContent(
            state =
                DiscoverUiState(
                    sections = sections(listOf(repo), load, emptyList(), "", null),
                    repositories = listOf(repo),
                ),
            previews = { _, _ -> null },
            actions = DiscoverActions(),
            onOpenRepositories = {},
        )
    }

    @Composable
    private fun Picker(state: app.mauri.cucuto.picker.PickerState) {
        PickerContent(state = state, assignBeat = null, onTap = {}, onUnassign = {}, onBack = {}, onOpenPacks = {})
    }

    @Composable
    private fun RingtonePicker(
        state: RingtonePickerState,
        step: Boolean = false,
    ) {
        RingtonePickerContent(state = state, beat = null, actions = RingtonePickerActions(), step = step)
    }
}

/** One pack whose characters have their own pattern, follow the voice, and don't vibrate (`x-haptic`). */
private val HAPTICS_INDEX =
    """
    { "formatVersion": 1, "name": "Friends' packs", "packs": [ {
      "id": "app.example.cats", "type": "pack", "name": "Cats", "version": "1.0.0", "author": "Test author",
      "license": "CC0-1.0", "tags": [], "cover": "previews/cats/cover.png",
      "characters": [
        { "id": "c1", "name": "Tabby", "sprite": "previews/cats/c1.png", "sample": "previews/cats/c1.ogg",
          "x-haptic": { "mode": "custom", "events": [ { "at": 0, "preset": "CLICK" }, { "at": 200, "preset": "TICK" } ] } },
        { "id": "c2", "name": "Purr", "sample": "previews/cats/c2.ogg", "x-haptic": { "mode": "auto" } },
        { "id": "c3", "name": "Mew", "sample": "previews/cats/c3.ogg", "x-haptic": { "mode": "none" } }
      ],
      "download": { "url": "packs/cats-1.0.0.soundpack.zip", "size": 4096, "sha256": "SHA" }
    } ] }
    """.replace("SHA", "0".repeat(64))
