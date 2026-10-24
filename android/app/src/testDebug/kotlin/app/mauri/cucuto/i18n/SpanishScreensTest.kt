package app.mauri.cucuto.i18n

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mauri.cucuto.home.HomeContent
import app.mauri.cucuto.home.HomeState
import app.mauri.cucuto.home.JourneyStep
import app.mauri.cucuto.home.previewHomeState
import app.mauri.cucuto.picker.PickerContent
import app.mauri.cucuto.picker.previewPickerState
import app.mauri.cucuto.ui.settings.SettingsActions
import app.mauri.cucuto.ui.settings.SettingsContent
import app.mauri.cucuto.ui.settings.SettingsState
import app.mauri.cucuto.ui.status.ModuleCheck
import app.mauri.cucuto.ui.status.PacksCheck
import app.mauri.cucuto.ui.status.ScopeCheck
import app.mauri.cucuto.ui.status.SelfTestMessage
import app.mauri.cucuto.ui.status.StatusCard
import app.mauri.cucuto.ui.status.StatusReport
import app.mauri.cucuto.ui.status.WhatsAppCheck
import app.mauri.cucuto.ui.theme.CucutoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Key screens in Spanish (#107) on a narrow phone (360 dp, the smallest common width): they render without crashing,
 * show the Spanish strings, and no text is clipped or ellipsized (Spanish runs about 20 % longer than English).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-w360dp-h800dp")
// Real text measurement (Skia and minikin), so overflow is the device's, not the legacy shadows' guess.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpanishScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { CucutoTheme(dynamicColor = false) { content() } }
        compose.waitForIdle()
    }

    @Test
    fun homeWithChatsAndRecentSenders() {
        show { HomeUnderTest(previewHomeState()) }
        compose.onNodeWithText("Remitentes recientes").assertIsDisplayed()
        compose.onNodeWithText("Asignar una voz", useUnmergedTree = true).assertExists()
        assertNoClippedText()
    }

    @Test
    fun homeEmpty() {
        show { HomeUnderTest(HomeState(loading = false)) }
        compose.onNodeWithText("Aún no hay chats").assertIsDisplayed()
        assertNoClippedText()
    }

    @Test
    fun homeFirstRunOffersTheDemoPacks() {
        show { HomeUnderTest(HomeState(loading = false), JourneyStep.ImportPacks) }
        compose.onNodeWithText("Dale voz a tus chats").assertIsDisplayed()
        compose.onNodeWithText("Consigue los packs de demostración").assertExists()
        compose.onNodeWithText("Elegir un archivo de Descargas").assertExists()
        assertNoClippedText()
    }

    @Test
    fun homeInvitesTheFirstSender() {
        val state = previewHomeState()
        show { HomeUnderTest(state, JourneyStep.GiveFirstVoice(state.recent.first())) }
        compose.onNodeWithText("Dale una voz a Ada").assertIsDisplayed()
        compose.onNodeWithText("Elegir una voz").assertExists()
        assertNoClippedText()
    }

    @Test
    fun characterPicker() {
        show {
            PickerContent(
                state = previewPickerState(),
                assignBeat = null,
                onTap = {},
                onUnassign = {},
                onBack = {},
                onOpenPacks = {},
            )
        }
        compose.onNodeWithText("Elige una voz").assertIsDisplayed()
        assertNoClippedText()
    }

    @Test
    fun statusCardWithEveryCheckFailing() {
        val report =
            StatusReport(
                module = ModuleCheck.NotEnabled,
                scope = ScopeCheck.ExtraApps(listOf("WhatsApp")),
                whatsApp = WhatsAppCheck.Missing,
                packs = PacksCheck.None,
            )
        show {
            // The card sits in Home's scrolling list, so it may be taller than the screen.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                StatusCard(report, beat = null, selfTest = SelfTestMessage.TooSoon(7), onAction = {})
            }
        }
        compose.onNodeWithText("Preparando Cucuto").assertIsDisplayed()
        compose.onNodeWithText("Importar un pack").assertExists()
        assertNoClippedText()
    }

    @Test
    fun settingsWithTheLanguageRow() {
        show { SettingsContent(state = SettingsState(), systemRemovesAnimations = false, actions = SettingsActions()) }
        compose.onNodeWithText("Ajustes").assertIsDisplayed()
        compose.onNodeWithText("Idioma de la app").assertExists()
        compose.onNodeWithText("El mismo que el móvil").assertExists()
        assertNoClippedText()
    }

    @Composable
    private fun HomeUnderTest(
        state: HomeState,
        journey: JourneyStep = JourneyStep.None,
    ) {
        HomeContent(
            state = state,
            onQueryChange = {},
            onTogglePack = {},
            onDismissRecent = {},
            onOpenChat = {},
            onOpenPacks = {},
            journey = journey,
        )
    }

    /** Every laid-out text: none may overflow its bounds or hit its maxLines (an ellipsis hides part of it). */
    private fun assertNoClippedText() {
        val clipped =
            compose
                .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .mapNotNull { node ->
                    val layouts = mutableListOf<TextLayoutResult>()
                    node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                    layouts
                        .firstOrNull()
                        ?.takeIf(::isClipped)
                        ?.layoutInput
                        ?.text
                        ?.text
                }
        assertEquals("Clipped Spanish text", emptyList<String>(), clipped)
    }

    /**
     * Cut off by maxLines or a too-short box, or a line wider than the text's box. (TextLayoutResult.hasVisualOverflow
     * also flags every wrap-content text narrower than its max width, so it can't be used as is.)
     */
    private fun isClipped(layout: TextLayoutResult): Boolean {
        val paragraph = layout.multiParagraph
        val widest = (0 until layout.lineCount).maxOfOrNull { layout.getLineRight(it) - layout.getLineLeft(it) } ?: 0f
        return paragraph.didExceedMaxLines ||
            layout.size.height < paragraph.height ||
            widest > layout.size.width + 0.5f
    }
}
