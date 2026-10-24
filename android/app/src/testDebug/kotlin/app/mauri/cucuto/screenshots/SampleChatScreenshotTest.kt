package app.mauri.cucuto.screenshots

import app.mauri.cucuto.core.data.settings.SampleChatChoice
import app.mauri.cucuto.core.ringtone.variants.RingtoneVariant
import app.mauri.cucuto.home.HomeContent
import app.mauri.cucuto.home.HomeState
import app.mauri.cucuto.home.JourneyStep
import app.mauri.cucuto.home.buildHomeState
import app.mauri.cucuto.navigation.TopLevelTab
import app.mauri.cucuto.sample.SampleChatContent
import app.mauri.cucuto.sample.SampleChatState
import app.mauri.cucuto.ui.ringtone.RingtoneCharacterItem
import app.mauri.cucuto.ui.ringtone.RingtonePackItem
import app.mauri.cucuto.ui.ringtone.RingtoneVariantItem
import org.junit.Test
import java.io.File

/**
 * The sample chat (#285, ADR 0075), the path a Play reviewer takes without root: Home's "Your characters are ready"
 * card with **Try a sample chat**, then the sample chat with Bleep the frog as its voice and its pond song as the
 * ringtone ("Same as the voice"). Sprites come from the demo packs.
 */
class SampleChatScreenshotTest(
    variant: Variant,
) : ScreenshotTest(variant) {
    @Test
    fun homeWaiting() =
        screenshot("home-waiting") {
            val state: HomeState =
                buildHomeState(
                    chats = emptyList(),
                    packs = DemoContent.packs.map { it.voicePack() },
                    query = "",
                    packFilter = null,
                    now = System.currentTimeMillis(),
                )
            InShell(TopLevelTab.Home) {
                HomeContent(
                    state = state,
                    onQueryChange = {},
                    onTogglePack = {},
                    onDismissRecent = {},
                    onOpenChat = {},
                    onOpenPacks = {},
                    journey = JourneyStep.WaitForMessages,
                )
            }
        }

    @Test
    fun sampleChat() =
        screenshot("sample-chat") {
            SampleChatContent(
                state = demoSampleChatState(),
                ringing = null,
                onBack = {},
                onChooseVoice = {},
                onChooseRingtone = {},
                onPlayMessage = {},
                onToggleCall = {},
                onGetDemoPacks = {},
            )
        }
}

/** The sample chat with the demo packs, Bleep the frog as its voice and its pond song as the ringtone. */
internal fun demoSampleChatState(): SampleChatState {
    val critters = DemoContent.critters.voicePack()
    val frog = critters.characters.first { it.id == "frog" }
    val path = "frog/ringtones/pond-song-day.ogg"
    val pondSong =
        RingtoneCharacterItem(
            frog,
            listOf(
                RingtoneVariantItem(RingtoneVariant(path, "Pond song (day)"), File(DemoContent.critters.root, path)),
            ),
        )
    return SampleChatState(
        loading = false,
        packs = DemoContent.packs.map { it.voicePack() },
        ringtonePacks = listOf(RingtonePackItem(critters.packId, critters.name, listOf(pondSong))),
        choice = SampleChatChoice(voice = frog.voice),
    )
}
