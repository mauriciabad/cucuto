package app.mauri.cucuto.screenshots

import app.mauri.cucuto.home.HomeContent
import app.mauri.cucuto.home.buildHomeState
import org.junit.Test

/** Home: the chats with their characters and the recent senders strip waiting for a voice. */
class HomeScreenshotTest(
    variant: Variant,
) : ScreenshotTest(variant) {
    @Test
    fun home() =
        screenshot("home", web = "home", store = 1) {
            HomeContent(
                state =
                    buildHomeState(
                        chats = DemoContent.chats(),
                        packs = DemoContent.packs.map { it.voicePack() },
                        query = "",
                        packFilter = null,
                        now = System.currentTimeMillis(),
                    ),
                onQueryChange = {},
                onTogglePack = {},
                onDismissRecent = {},
                onOpenChat = {},
                onOpenPacks = {},
                onBack = {},
            )
        }
}
