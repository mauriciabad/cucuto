package app.mauri.cucuto.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.BuildConfig
import app.mauri.cucuto.R
import app.mauri.cucuto.navigation.CucutoDestination
import app.mauri.cucuto.ui.components.CucutoTopBar
import app.mauri.cucuto.ui.components.LinkGroup
import app.mauri.cucuto.ui.components.LinkItem
import app.mauri.cucuto.ui.theme.CucutoTheme

/**
 * The Settings tab. The shell (#153) only needs this entry point; Settings (#28) replaces the body with the global
 * switch, feedback and animation settings, backup and restore, about and licenses.
 *
 * Keep these links when replacing it: Setup (where the Home setup card goes once everything is green), Diagnostics,
 * and Developer in debug builds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigate: (CucutoDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { CucutoTopBar(title = stringResource(R.string.tab_settings), scrollBehavior = scrollBehavior) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().consumeWindowInsets(innerPadding),
            contentPadding =
                PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    bottom = innerPadding.calculateBottomPadding() + 24.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "module") {
                LinkGroup(stringResource(R.string.settings_group_module)) {
                    LinkItem(
                        icon = Icons.Rounded.CheckCircle,
                        title = stringResource(R.string.screen_setup),
                        body = stringResource(R.string.settings_setup_body),
                        onClick = { onNavigate(CucutoDestination.Setup) },
                    )
                    LinkItem(
                        icon = Icons.Rounded.Info,
                        title = stringResource(R.string.screen_diagnostics),
                        body = stringResource(R.string.settings_diagnostics_body),
                        onClick = { onNavigate(CucutoDestination.Diagnostics) },
                    )
                }
            }
            if (BuildConfig.DEBUG) {
                item(key = "developer") {
                    LinkGroup(stringResource(R.string.settings_group_developer)) {
                        LinkItem(
                            icon = Icons.Rounded.Build,
                            title = stringResource(R.string.screen_developer),
                            body = stringResource(R.string.settings_developer_body),
                            onClick = { onNavigate(CucutoDestination.Developer) },
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsPreview() {
    CucutoTheme(dynamicColor = false) { SettingsScreen(onNavigate = {}) }
}
