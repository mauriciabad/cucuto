package app.mauri.cucuto.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The bottom bar of a pushed screen whose step ends in an action (restore, import, backup, check and finish): its
 * buttons at the end, wrapping onto a second line with large fonts. Goes in `Scaffold(bottomBar = …)`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BottomActionBar(
    modifier: Modifier = Modifier,
    content: @Composable FlowRowScope.() -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
