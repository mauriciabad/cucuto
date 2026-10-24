package app.mauri.cucuto.ui.creator

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.R
import app.mauri.cucuto.packs.parseSeedColor
import app.mauri.cucuto.ui.theme.CharacterTheme
import coil3.compose.AsyncImage
import java.io.File
import java.util.Locale

/** Re-themes [content] in a character's seed color (`#RRGGBB`), as the rest of the app shows that character. */
@Composable
internal fun CharacterColors(
    seedColor: String?,
    content: @Composable () -> Unit,
) {
    CharacterTheme(seed = parseSeedColor(seedColor)?.let { Color(it) }, content = content)
}

/** A sprite in the app's cookie shape (the shape the app shows characters in), or a picture icon without one. */
@Composable
internal fun SpriteBadge(
    sprite: File?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Surface(
        shape = MaterialShapes.Cookie9Sided.toShape(),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (sprite != null) {
                AsyncImage(
                    model = sprite,
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(SPRITE_INSET),
                )
            } else {
                Icon(painterResource(R.drawable.ic_creator_image), contentDescription = contentDescription)
            }
        }
    }
}

private val SPRITE_INSET = 6.dp

/** "2.4 s" for a duration in milliseconds. */
internal fun seconds(ms: Long): String = String.format(Locale.getDefault(), "%.1f", ms / 1000.0)
