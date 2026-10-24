package app.mauri.cucuto.video.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.video.BrandColors
import app.mauri.cucuto.video.CuckooHouse
import app.mauri.cucuto.video.DoorPose
import app.mauri.cucuto.video.ShotScope
import app.mauri.cucuto.video.Spring
import app.mauri.cucuto.video.doorPose
import app.mauri.cucuto.video.tapped

/**
 * Shot 3: set up once. The three things Cucuto needs tick off (Android 17, root, LSPosed: setup is never shown as
 * easier than it is), then the self-test is tapped at [testAt] and turns green at [greenAt], with the door opening.
 */
@Composable
internal fun ShotScope.SetUpScreen(
    testAt: Double,
    greenAt: Double,
) {
    Screen {
        ScreenTitle("Set up once")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Requirement(1, "Android 17", spring(0.8, Spring.FastSpatial))
            Requirement(2, "Root", spring(1.5, Spring.FastSpatial))
            Requirement(3, "LSPosed", spring(2.2, Spring.FastSpatial))
        }
        Spacer(Modifier.weight(1f))
        val green = fade(greenAt)
        Card(color = lerp(MaterialTheme.colorScheme.surfaceContainer, BrandColors.SuccessContainer, green)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CuckooHouse(pose = if (t < greenAt) DoorPose.Closed else doorPose(greenAt), height = 64.dp)
                Column {
                    Text(
                        if (after(greenAt)) "All set" else "Self-test",
                        style = PhoneText.name,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        when {
                            after(greenAt) -> "Cucuto is ready"
                            after(testAt) -> "Checking…"
                            else -> "Not run yet"
                        },
                        style = PhoneText.body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            PillButton("Run self-test", Modifier.fillMaxWidth().tapped(this@SetUpScreen, testAt))
        }
    }
}

@Composable
private fun Requirement(
    number: Int,
    text: String,
    checked: Float,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", style = PhoneText.label, color = MaterialTheme.colorScheme.primary)
        }
        Text(text, style = PhoneText.label, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        CheckBadge(checked, size = 28.dp)
    }
}
