package app.mauri.cucuto.screenshots

import app.mauri.cucuto.core.data.db.RingtoneMode
import app.mauri.cucuto.core.ringtone.variants.RingtoneVariant
import app.mauri.cucuto.ui.ringtone.RingtoneCharacterItem
import app.mauri.cucuto.ui.ringtone.RingtonePackItem
import app.mauri.cucuto.ui.ringtone.RingtonePickerActions
import app.mauri.cucuto.ui.ringtone.RingtonePickerContent
import app.mauri.cucuto.ui.ringtone.RingtonePickerState
import app.mauri.cucuto.ui.ringtone.RingtoneSelection
import app.mauri.cucuto.ui.ringtone.RingtoneVariantItem
import org.junit.Test
import java.io.File

/**
 * The ringtone picker (#53) for Iris, whose voice is Bleep the frog: the frog's pond song rings by day and night, and
 * the kettle's reel is there to mix in. Sprites and ringtone files come from the demo packs.
 */
class RingtonePickerScreenshotTest(
    variant: Variant,
) : ScreenshotTest(variant) {
    @Test
    fun ringtonePicker() =
        screenshot("ringtone-picker") {
            val chat = DemoContent.chats().first { it.voice == DemoContent.critters.voice("frog") }
            val critters = DemoContent.critters.voicePack()
            val kitchen = DemoContent.kitchen.voicePack()
            val frog =
                RingtoneCharacterItem(
                    critters.characters.first { it.id == "frog" },
                    listOf(
                        variant(DemoContent.critters, "frog/ringtones/pond-song-day.ogg", "Pond song (day)", "day"),
                        variant(
                            DemoContent.critters,
                            "frog/ringtones/pond-song-night.ogg",
                            "Pond song (night)",
                            "night",
                        ),
                    ),
                )
            val kettle =
                RingtoneCharacterItem(
                    kitchen.characters.first { it.id == "kettle" },
                    listOf(variant(DemoContent.kitchen, "kettle/ringtones/kettle-reel.ogg", "Kettle reel")),
                )
            RingtonePickerContent(
                state =
                    RingtonePickerState(
                        loading = false,
                        chatKey = chat.key,
                        chat = chat,
                        packs =
                            listOf(
                                RingtonePackItem(critters.packId, critters.name, listOf(frog)),
                                RingtonePackItem(kitchen.packId, kitchen.name, listOf(kettle)),
                            ),
                        saved = RingtoneSelection(frog.voice, followsVoice = true, mode = RingtoneMode.TIME),
                    ),
                beat = null,
                actions = RingtonePickerActions(),
            )
        }

    private fun variant(
        pack: DemoPack,
        path: String,
        label: String,
        tag: String? = null,
    ) = RingtoneVariantItem(RingtoneVariant(path, label, listOfNotNull(tag)), File(pack.root, path))
}
