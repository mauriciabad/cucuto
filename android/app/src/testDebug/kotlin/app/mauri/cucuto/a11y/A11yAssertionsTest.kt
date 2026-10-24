package app.mauri.cucuto.a11y

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The touch-target check (#226): a control under 48 dp fails when it has no room to stretch to 48 dp, on the first
 * screenful and further down scrolling lists, columns and rows.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class A11yAssertionsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun passesASmallControlWithRoomToStretch() {
        compose.showScreen { Column(Modifier.padding(24.dp)) { Tiny("Alone") } }
        compose.assertTouchTargets("alone")
    }

    @Test
    fun findsCrowdedSmallControlsOnScreen() {
        compose.showScreen { TinyPair() }
        assertFails()
    }

    @Test
    fun findsCrowdedSmallControlsFarDownALazyList() {
        compose.showScreen {
            LazyColumn(Modifier.fillMaxSize()) {
                items(ROWS) { i -> if (i == ROWS - 1) TinyPair() else Text("Row $i", Modifier.height(64.dp)) }
            }
        }
        assertFails()
    }

    @Test
    fun findsCrowdedSmallControlsAtTheEndOfAScrollingColumn() {
        compose.showScreen {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Box(Modifier.height(3_000.dp))
                TinyPair()
            }
        }
        assertFails()
    }

    @Test
    fun findsCrowdedSmallControlsAtTheEndOfARowFurtherDown() {
        compose.showScreen {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Box(Modifier.height(2_000.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    Box(Modifier.width(2_000.dp).height(48.dp))
                    TinyPair()
                }
            }
        }
        assertFails()
    }

    @Test
    fun passesWhenEveryControlIsBigEnough() {
        compose.showScreen {
            LazyColumn(Modifier.fillMaxSize()) {
                items(ROWS) { i -> Text("Row $i", Modifier.height(64.dp).clickable {}) }
            }
        }
        compose.assertTouchTargets("big rows")
    }

    private fun assertFails() {
        val error = assertThrows(AssertionError::class.java) { compose.assertTouchTargets("test") }
        assertTrue(error.message, error.message.orEmpty().contains("Tiny"))
    }

    /** Two 24 dp controls side by side: neither has room for a 48 dp touch area. */
    @Composable
    private fun TinyPair() {
        Row {
            Tiny("Tiny")
            Tiny("Tiny too")
        }
    }

    @Composable
    private fun Tiny(label: String) {
        Box(Modifier.size(24.dp).semantics { contentDescription = label }.clickable {})
    }

    private companion object {
        const val ROWS = 60
    }
}
