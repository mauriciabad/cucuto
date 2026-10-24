package app.mauri.cucuto.ui.settings.restore

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mauri.cucuto.core.data.db.GroupPolicy
import app.mauri.cucuto.ui.theme.CucutoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The restore conflict screen on Robolectric, with made-up contacts. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class RestoreContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val toggled = mutableListOf<String>()
    private var applied = 0

    private val rows =
        listOf(
            ConflictRow(
                "1",
                "Ada",
                VoiceLabel("Bleep the frog", "Chiptune critters", null),
                VoiceLabel("Hoot the owl", "Chiptune critters", null),
            ),
            ConflictRow(
                "2",
                null,
                VoiceLabel(null, null, GroupPolicy.GROUP),
                VoiceLabel("ghost", "app.mauri.gone", GroupPolicy.PER_SENDER),
            ),
        )

    private fun show(step: RestoreStep) {
        compose.setContent {
            CucutoTheme(dynamicColor = false) {
                RestoreContent(
                    step = step,
                    actions = RestoreActions(onToggleConflict = { toggled += it }, onApplyConflicts = { applied++ }),
                )
            }
        }
    }

    @Test
    fun conflictsShowBothVoicesAndKeepThisPhonesByDefault() {
        show(RestoreStep.Conflicts(rows))
        compose.onNodeWithText("Here: Bleep the frog (Chiptune critters)").assertIsDisplayed()
        compose.onNodeWithText("Backup: Hoot the owl (Chiptune critters)").assertIsDisplayed()
        compose.onNodeWithText("Unnamed chat").assertIsDisplayed()
        compose.onNodeWithText("Here: no voice · one voice for the group").assertIsDisplayed()
        compose.onNodeWithText("Backup: ghost (app.mauri.gone) · each sender's own voice").assertIsDisplayed()

        compose.onNodeWithText("Ada").performClick()
        assertEquals(listOf("1"), toggled)

        compose.onNodeWithText("Keep this phone's voices").performClick()
        assertEquals(1, applied)
    }

    @Test
    fun tickedConflictsChangeTheButton() {
        show(RestoreStep.Conflicts(rows, selected = setOf("1", "2")))
        compose.onNodeWithText("Use the backup's voice for 2").assertIsDisplayed()
    }
}
