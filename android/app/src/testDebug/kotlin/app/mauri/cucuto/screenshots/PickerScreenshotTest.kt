package app.mauri.cucuto.screenshots

import app.mauri.cucuto.picker.PickerContent
import app.mauri.cucuto.picker.PickerState
import org.junit.Test

/** The character picker for a recent sender, with the frog chosen once (a second tap assigns it). */
class PickerScreenshotTest(
    variant: Variant,
) : ScreenshotTest(variant) {
    @Test
    fun picker() =
        screenshot("picker", web = "picker", store = 2) {
            val chat = DemoContent.chats().first { it.voice == null }
            PickerContent(
                state =
                    PickerState(
                        loading = false,
                        chatKey = chat.key,
                        chat = chat,
                        packs = DemoContent.packs.map { it.voicePack() },
                        selected = DemoContent.critters.voice("frog"),
                    ),
                assignBeat = null,
                onTap = {},
                onUnassign = {},
                onBack = {},
                onOpenPacks = {},
            )
        }
}
