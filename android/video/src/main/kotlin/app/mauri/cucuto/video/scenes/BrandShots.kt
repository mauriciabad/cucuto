package app.mauri.cucuto.video.scenes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mauri.cucuto.video.CuckooHouse
import app.mauri.cucuto.video.ShotScope
import app.mauri.cucuto.video.Spring
import app.mauri.cucuto.video.Svg
import app.mauri.cucuto.video.doorPose
import app.mauri.cucuto.video.lerp

/**
 * Shot 2: the brand's signature, full frame. The cuckoo door swings open at [opensAt], the bird pops out and calls
 * twice, and the wordmark rises in below (brand/motion.md).
 */
@Composable
internal fun ShotScope.TheDoor(
    opensAt: Double,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val enter = spring(0.0, Spring.SlowSpatial)
        CuckooHouse(
            pose = doorPose(opensAt),
            height = 250.dp,
            withWaves = true,
            modifier =
                Modifier.graphicsLayer {
                    val scale = lerp(0.85f, 1f, enter)
                    scaleX = scale
                    scaleY = scale
                    // The waves stick out on the right: shift so the house itself is centered.
                    translationX = 250.dp.toPx() * (54f - 48f) / 48f / 2
                },
        )
        Spacer(Modifier.height(28.dp))
        Svg(
            "brand/logo/cucuto-wordmark-light.svg",
            320.dp,
            Modifier.reveal(spring(opensAt + 0.55, Spring.SlowSpatial), lift = 24.dp),
        )
    }
}

/** Shot 10: the logo, the line, the address and the fine print, on the cream stage. */
@Composable
internal fun ShotScope.EndCard(
    logoAt: Double,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val logo = spring(logoAt, Spring.FastSpatial)
        Svg(
            "brand/logo/cucuto-logo-light.svg",
            440.dp,
            Modifier.graphicsLayer {
                val scale = lerp(0.8f, 1f, logo)
                scaleX = scale
                scaleY = scale
                alpha = logo.coerceIn(0f, 1f)
            },
        )
        Spacer(Modifier.height(30.dp))
        Text(
            "Give each contact a voice.",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.reveal(spring(logoAt + 0.3, Spring.SlowSpatial)),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "cucuto.mauri.app",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.reveal(spring(logoAt + 0.6, Spring.SlowSpatial)),
        )
        Spacer(Modifier.height(26.dp))
        Text(
            "Free and open source · Android 17 · Needs root and LSPosed\nNot affiliated with WhatsApp or Meta",
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 26.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.reveal(spring(logoAt + 0.9, Spring.SlowSpatial)),
        )
    }
}
