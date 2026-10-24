package app.mauri.cucuto.ui.motion

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #339: a ringtone badge ([SharedCompanion]) on an avatar ([SharedElement]) flies with it as one group and stays
 * drawn above it in the middle of the transition, instead of hiding under the avatar in the overlay.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class SharedCompanionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theBadgeFliesAboveTheAvatar() {
        var detail by mutableStateOf(false)
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.White).testTag(ROOT)) {
                SharedElementsHost {
                    AnimatedContent(detail, label = "page") { isDetail ->
                        CompositionLocalProvider(LocalEntryAnimatedScope provides this) {
                            Box(Modifier.fillMaxSize()) {
                                if (isDetail) Group(100.dp, 96.dp, 40.dp) else Group(0.dp, 48.dp, 20.dp)
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val start = blueBounds()

        compose.mainClock.autoAdvance = false
        detail = true
        val frames =
            (0 until 30).map {
                compose.mainClock.advanceTimeByFrame()
                blueBounds()
            }

        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        val end = blueBounds()

        // In every frame the badge is whole and opaque: drawn above the avatar, never under it nor fading in place.
        frames.forEach { assertTrue("badge covered: $it", it.count >= it.area * 9 / 10) }
        // It travels with the avatar and grows on the way, never jumping back.
        assertTrue(
            frames.any { it.left in (start.left + 1) until end.left && it.area in (start.area + 1) until end.area },
        )
        frames.zipWithNext().forEach { (a, b) -> assertTrue("jumped back: $a -> $b", b.left >= a.left) }
    }

    @Composable
    private fun Group(
        at: Dp,
        avatar: Dp,
        badge: Dp,
    ) {
        Box(Modifier.offset(at, at)) {
            SharedElement(AVATAR) { Box(Modifier.size(avatar).background(Color.Red)) }
            SharedCompanion(BADGE, Modifier.align(Alignment.BottomEnd)) {
                Box(Modifier.size(badge).background(Color.Blue))
            }
        }
    }

    private data class Blue(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val count: Int,
    ) {
        val area: Int get() = (right - left + 1) * (bottom - top + 1)
    }

    private fun blueBounds(): Blue {
        val pixels = compose.onNodeWithTag(ROOT).captureToImage().toPixelMap()
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = -1
        var bottom = -1
        var count = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val c = pixels[x, y]
                if (c.blue > 0.9f && c.red < 0.1f && c.green < 0.1f) {
                    count++
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        return Blue(left, top, right, bottom, count)
    }

    private companion object {
        const val ROOT = "root"
        const val AVATAR = "avatar"
        const val BADGE = "badge"
    }
}
