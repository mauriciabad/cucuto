package app.mauri.cucuto.video.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.video.Cast
import app.mauri.cucuto.video.Cookie
import app.mauri.cucuto.video.Person
import app.mauri.cucuto.video.ShotScope
import app.mauri.cucuto.video.Spring
import app.mauri.cucuto.video.tapped

/**
 * Shot 5: everyone who messaged shows up, each still on WhatsApp's tone. Ada's row lights up at [highlightAt] and
 * offers to give her a voice, tapped at [tapAt].
 */
@Composable
internal fun ShotScope.RecentChatsScreen(
    highlightAt: Double,
    tapAt: Double,
) {
    Screen {
        ScreenTitle("Recent chats")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Cast.ada, Cast.bea, Cast.leo).forEachIndexed { i, person ->
                val highlight = if (i == 0) fade(highlightAt) else 0f
                ChatRow(
                    person,
                    highlight,
                    Modifier.reveal(spring(0.2 + 0.15 * i, Spring.DefaultSpatial)),
                ) {
                    if (i == 0 && highlight > 0f) {
                        val shown = spring(highlightAt, Spring.DefaultSpatial)
                        PillButton(
                            "Give a voice",
                            Modifier
                                .fillMaxWidth()
                                .expandIn(shown)
                                .reveal(shown)
                                .tapped(this@RecentChatsScreen, tapAt),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatRow(
    person: Person,
    highlight: Float,
    modifier: Modifier = Modifier,
    extra: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(
                lerp(
                    MaterialTheme.colorScheme.surfaceContainerLow,
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    highlight,
                ),
            ).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Cookie(48.dp, person.tint) {
                Text(person.name.take(1), style = PhoneText.name, color = MaterialTheme.colorScheme.onSurface)
            }
            Column {
                Text(person.name, style = PhoneText.name, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "No voice yet",
                    style = PhoneText.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        extra()
    }
}
