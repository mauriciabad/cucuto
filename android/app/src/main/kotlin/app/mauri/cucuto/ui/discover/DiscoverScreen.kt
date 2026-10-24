package app.mauri.cucuto.ui.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.R
import app.mauri.cucuto.navigation.CucutoDestination
import app.mauri.cucuto.packs.ui.PACKS_WEBSITE_URL
import app.mauri.cucuto.ui.components.CuckooDoorStill
import app.mauri.cucuto.ui.components.CucutoTopBar
import app.mauri.cucuto.ui.theme.CucutoTheme

/**
 * The Discover tab. The shell (#153) only needs this entry point; Discover (#36) replaces the body with pack
 * repositories (browse, preview, download). Until then it points to the free packs on the website and to importing a
 * file on the Packs tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    onNavigate: (CucutoDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val uriHandler = LocalUriHandler.current
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { CucutoTopBar(title = stringResource(R.string.tab_discover), scrollBehavior = scrollBehavior) },
    ) { innerPadding ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier =
                Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding)
                    .padding(horizontal = 32.dp, vertical = 24.dp),
        ) {
            CuckooDoorStill(seconds = DOOR_OPEN_SECONDS, modifier = Modifier.size(160.dp))
            Text(
                text = stringResource(R.string.discover_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.discover_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = { uriHandler.openUri(PACKS_WEBSITE_URL) },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.discover_website)) }
            OutlinedButton(
                onClick = { onNavigate(CucutoDestination.Packs) },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.discover_import)) }
        }
    }
}

/** Long after the door's timeline ends: the settled open pose. */
private const val DOOR_OPEN_SECONDS = 3f

@Preview(showBackground = true)
@Composable
private fun DiscoverPreview() {
    CucutoTheme(dynamicColor = false) { DiscoverScreen(onNavigate = {}) }
}
