package app.mauri.cucuto.screenshots

import app.mauri.cucuto.home.HomeContent
import app.mauri.cucuto.home.previewHomeStateWithQuestion
import app.mauri.cucuto.navigation.TopLevelTab
import app.mauri.cucuto.ui.contacts.AddFromContactsContent
import app.mauri.cucuto.ui.contacts.AddFromContactsState
import app.mauri.cucuto.ui.contacts.previewContactsState
import org.junit.Test

/**
 * "Add a chat from contacts" (ADR 0065): the explainer, the list of WhatsApp entries, and Home with a chat waiting for
 * its first message and a "Who is this?" question. Made-up names and numbers only.
 */
class AddFromContactsScreenshotTest(
    variant: Variant,
) : ScreenshotTest(variant) {
    @Test
    fun explainer() =
        screenshot("add-from-contacts") { AddFromContactsContent(AddFromContactsState(), {}, {}, {}, {}, {}, {}) }

    @Test
    fun list() =
        screenshot("add-from-contacts-list") { AddFromContactsContent(previewContactsState(), {}, {}, {}, {}, {}, {}) }

    @Test
    fun homeFromContacts() =
        screenshot("home-from-contacts") {
            InShell(TopLevelTab.Home) {
                HomeContent(
                    state = previewHomeStateWithQuestion(),
                    onQueryChange = {},
                    onTogglePack = {},
                    onDismissRecent = {},
                    onOpenChat = {},
                    onOpenPacks = {},
                )
            }
        }
}
