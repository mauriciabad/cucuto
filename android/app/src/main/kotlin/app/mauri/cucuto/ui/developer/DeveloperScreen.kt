package app.mauri.cucuto.ui.developer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.cucuto.R
import app.mauri.cucuto.core.data.settings.OnboardingSettings
import app.mauri.cucuto.navigation.CucutoDestination
import app.mauri.cucuto.ui.components.CucutoTopBar
import app.mauri.cucuto.ui.components.LinkGroup
import app.mauri.cucuto.ui.components.LinkItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Settings → Developer, debug builds only (the shell does not register it in release builds): the design gallery,
 * Diagnostics (which holds the debug test voice) and a reset of the first-run state to replay onboarding and the
 * Home journey.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperScreen(
    onNavigate: (CucutoDestination) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DeveloperViewModel = hiltViewModel(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            CucutoTopBar(
                title = stringResource(R.string.screen_developer),
                scrollBehavior = scrollBehavior,
                onBack = onBack,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
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
            item(key = "tools") {
                LinkGroup(stringResource(R.string.developer_group_tools)) {
                    LinkItem(
                        icon = Icons.Rounded.Face,
                        title = stringResource(R.string.screen_gallery),
                        body = stringResource(R.string.developer_gallery_body),
                        onClick = { onNavigate(CucutoDestination.Gallery) },
                    )
                    LinkItem(
                        icon = Icons.Rounded.Info,
                        title = stringResource(R.string.screen_diagnostics),
                        body = stringResource(R.string.developer_diagnostics_body),
                        onClick = { onNavigate(CucutoDestination.Diagnostics) },
                    )
                    LinkItem(
                        icon = Icons.Rounded.Refresh,
                        title = stringResource(R.string.developer_reset_first_run),
                        body = stringResource(R.string.developer_reset_first_run_body),
                        onClick = {
                            viewModel.resetFirstRun()
                            scope.launch { snackbar.showSnackbar(resources.getString(R.string.developer_reset_done)) }
                        },
                    )
                }
            }
        }
    }
}

/** Test tools that change app state. */
@HiltViewModel
class DeveloperViewModel
    @Inject
    constructor(
        private val onboarding: OnboardingSettings,
    ) : ViewModel() {
        /** Onboarding opens again right away (the app returns to its first-run stage); packs and voices stay. */
        fun resetFirstRun() {
            viewModelScope.launch { onboarding.reset() }
        }
    }
